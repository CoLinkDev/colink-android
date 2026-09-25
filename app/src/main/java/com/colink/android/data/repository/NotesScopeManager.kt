package com.colink.android.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.colink.android.data.local.db.CoLinkDatabase
import com.colink.android.data.local.db.dao.NoteAttachmentDao
import com.colink.android.data.local.db.dao.NoteDao
import com.colink.android.data.local.db.dao.NoteSyncKvDao
import com.colink.android.data.local.db.dao.NoteTagDao
import com.colink.android.data.local.db.entity.decodeIds
import com.colink.android.data.local.db.entity.encodeIds
import com.colink.android.domain.model.NoteSyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val LOCAL_ACCOUNT_SCOPE = "__local__"

internal fun noteAccountScope(serverUrl: String, userId: String): String =
    "${serverUrl.trim().trimEnd('/')}\n${userId.trim()}"

private data class AttachmentTransfer(
    val sourceId: String,
    val targetId: String,
)

@Singleton
class NotesScopeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: CoLinkDatabase,
    private val noteDao: NoteDao,
    private val noteTagDao: NoteTagDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val noteSyncKvDao: NoteSyncKvDao,
) {
    private val transferMutex = Mutex()

    suspend fun prepare(scope: String) {
        if (scope != LOCAL_ACCOUNT_SCOPE) {
            transfer(LOCAL_ACCOUNT_SCOPE, scope)
        }
    }

    suspend fun releaseAccount(serverUrl: String, userId: String) {
        transfer(noteAccountScope(serverUrl, userId), LOCAL_ACCOUNT_SCOPE)
    }

    private suspend fun transfer(sourceScope: String, targetScope: String) {
        if (sourceScope == targetScope) return
        transferMutex.withLock {
            val stagedTargets = mutableListOf<File>()
            val attachments = try {
                database.withTransaction {
                    transferRecords(sourceScope, targetScope).also { transfers ->
                        stageAttachmentFiles(
                            sourceScope = sourceScope,
                            targetScope = targetScope,
                            transfers = transfers,
                            stagedTargets = stagedTargets,
                        )
                    }
                }
            } catch (error: Throwable) {
                stagedTargets.forEach(File::delete)
                throw error
            }
            deleteTransferredSources(sourceScope, attachments)
        }
    }

    private suspend fun transferRecords(
        sourceScope: String,
        targetScope: String,
    ): List<AttachmentTransfer> {
        val targetTagNames = noteTagDao.listTags(targetScope, NoteSyncState.PendingDelete.name)
            .associate { it.nameNormalized to it.tagId }
            .toMutableMap()
        val targetTagIds = noteTagDao.listAllRows(targetScope).mapTo(mutableSetOf()) { it.tagId }
        val tagMapping = mutableMapOf<String, String>()
        for (source in noteTagDao.listAllRows(sourceScope)) {
            if (source.deleted || source.syncState == NoteSyncState.PendingDelete.name) continue
            val duplicateId = targetTagNames[source.nameNormalized]
            if (duplicateId != null) {
                tagMapping[source.tagId] = duplicateId
                continue
            }
            val targetId = if (source.tagId in targetTagIds) UUID.randomUUID().toString() else source.tagId
            noteTagDao.upsertScoped(
                source.copy(
                    accountScope = targetScope,
                    tagId = targetId,
                    revision = 0,
                    baseRevision = 0,
                    syncState = NoteSyncState.Pending.name,
                    deleted = false,
                ),
            )
            tagMapping[source.tagId] = targetId
            targetTagNames[source.nameNormalized] = targetId
            targetTagIds += targetId
        }

        val targetAttachmentIds = noteAttachmentDao.listAllRows(targetScope)
            .mapTo(mutableSetOf()) { it.attachmentId }
        val attachmentMapping = mutableMapOf<String, String>()
        val attachmentTransfers = mutableListOf<AttachmentTransfer>()
        for (source in noteAttachmentDao.listAllRows(sourceScope)) {
            if (source.deleted || source.syncState == NoteSyncState.PendingDelete.name) continue
            val existing = if (source.attachmentId in targetAttachmentIds) {
                noteAttachmentDao.find(targetScope, source.attachmentId)
            } else {
                null
            }
            val targetId = when {
                existing == null -> source.attachmentId
                existing.kind == source.kind && existing.size == source.size && existing.sha256 == source.sha256 -> {
                    source.attachmentId
                }
                else -> UUID.randomUUID().toString()
            }
            if (existing == null || targetId != source.attachmentId) {
                noteAttachmentDao.upsertScoped(
                    source.copy(
                        accountScope = targetScope,
                        attachmentId = targetId,
                        syncState = NoteSyncState.Pending.name,
                        deleted = false,
                    ),
                )
                targetAttachmentIds += targetId
            }
            attachmentMapping[source.attachmentId] = targetId
            attachmentTransfers += AttachmentTransfer(source.attachmentId, targetId)
        }

        val targetNoteIds = noteDao.listAllRows(targetScope).mapTo(mutableSetOf()) { it.noteId }
        for (source in noteDao.listAllRows(sourceScope)) {
            if (source.deleted || source.syncState == NoteSyncState.PendingDelete.name) continue
            val targetId = if (source.noteId in targetNoteIds) UUID.randomUUID().toString() else source.noteId
            noteDao.upsertScoped(
                source.copy(
                    accountScope = targetScope,
                    noteId = targetId,
                    tagIds = remapEncodedIds(source.tagIds, tagMapping),
                    attachmentIds = remapEncodedIds(source.attachmentIds, attachmentMapping),
                    revision = 0,
                    baseRevision = 0,
                    ancestorRevision = 0,
                    syncState = NoteSyncState.Pending.name,
                    conflictKind = null,
                    conflictTitle = null,
                    conflictMarkdown = null,
                    conflictTagIds = null,
                    conflictAttachmentIds = null,
                    conflictRevision = null,
                    ancestorTitle = "",
                    ancestorMarkdown = "",
                    ancestorTagIds = "[]",
                    ancestorAttachmentIds = "[]",
                    deleted = false,
                ),
            )
            targetNoteIds += targetId
        }

        noteDao.deleteScope(sourceScope)
        noteTagDao.deleteScope(sourceScope)
        noteAttachmentDao.deleteScope(sourceScope)
        noteSyncKvDao.deleteScope(sourceScope)
        if (targetScope == LOCAL_ACCOUNT_SCOPE) {
            noteSyncKvDao.deleteScope(LOCAL_ACCOUNT_SCOPE)
        }
        return attachmentTransfers
    }

    private fun stageAttachmentFiles(
        sourceScope: String,
        targetScope: String,
        transfers: List<AttachmentTransfer>,
        stagedTargets: MutableList<File>,
    ) {
        val sourceDirectory = attachmentCacheDirectory(sourceScope)
        val legacyDirectory = File(context.filesDir, "notes-attachments")
        val targetDirectory = attachmentCacheDirectory(targetScope)
        for (transfer in transfers) {
            val scopedSource = File(sourceDirectory, transfer.sourceId)
            val legacySource = File(legacyDirectory, transfer.sourceId)
            val source = when {
                scopedSource.isFile -> scopedSource
                legacySource.isFile -> legacySource
                else -> continue
            }
            val target = File(targetDirectory, transfer.targetId)
            if (!target.isFile) {
                stagedTargets += target
                source.copyTo(target)
            }
        }
    }

    private fun deleteTransferredSources(
        sourceScope: String,
        transfers: List<AttachmentTransfer>,
    ) {
        val sourceDirectory = attachmentCacheDirectory(sourceScope)
        val legacyDirectory = File(context.filesDir, "notes-attachments")
        for (transfer in transfers) {
            val scopedSource = File(sourceDirectory, transfer.sourceId)
            if (scopedSource.isFile) {
                scopedSource.delete()
            } else {
                val legacySource = File(legacyDirectory, transfer.sourceId)
                if (legacySource.isFile) {
                    legacySource.delete()
                }
            }
        }
    }

    fun attachmentCacheDirectory(scope: String): File {
        val scopeDigest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "notes-attachments/$scopeDigest").apply { mkdirs() }
    }

    fun attachmentCacheFile(scope: String, attachmentId: String): File {
        val target = File(attachmentCacheDirectory(scope), attachmentId)
        val legacy = File(File(context.filesDir, "notes-attachments"), attachmentId)
        val local = File(attachmentCacheDirectory(LOCAL_ACCOUNT_SCOPE), attachmentId)
        val fallback = when {
            legacy.isFile -> legacy
            scope != LOCAL_ACCOUNT_SCOPE && local.isFile -> local
            else -> null
        }
        if (!target.exists() && fallback != null) {
            if (!fallback.renameTo(target)) {
                fallback.copyTo(target)
                fallback.delete()
            }
        }
        return target
    }

    private fun remapEncodedIds(raw: String, mapping: Map<String, String>): String =
        encodeIds(decodeIds(raw).map { mapping[it] ?: it }.distinct().sorted())
}
