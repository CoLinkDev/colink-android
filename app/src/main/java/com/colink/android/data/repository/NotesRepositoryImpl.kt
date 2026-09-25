package com.colink.android.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.colink.android.data.local.db.CoLinkDatabase
import com.colink.android.data.local.db.dao.NoteAttachmentDao
import com.colink.android.data.local.db.dao.NoteDao
import com.colink.android.data.local.db.dao.NoteSyncKvDao
import com.colink.android.data.local.db.dao.NoteTagDao
import com.colink.android.data.local.db.entity.NoteAttachmentEntity
import com.colink.android.data.local.db.entity.NoteEntity
import com.colink.android.data.local.db.entity.NoteSyncKvEntity
import com.colink.android.data.local.db.entity.NoteTagEntity
import com.colink.android.data.local.db.entity.decodeIds
import com.colink.android.data.local.db.entity.encodeIds
import com.colink.android.data.notes.NoteMerger
import com.colink.android.data.remote.api.NotesApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.ApiException
import com.colink.android.data.remote.dto.AttachmentDto
import com.colink.android.data.remote.dto.NoteCreateRequestDto
import com.colink.android.data.remote.dto.NoteDto
import com.colink.android.data.remote.dto.NoteUpdateRequestDto
import com.colink.android.data.remote.dto.TagCreateRequestDto
import com.colink.android.data.remote.dto.TagUpdateRequestDto
import com.colink.android.data.remote.dto.requireData
import com.colink.android.data.remote.dto.requireOk
import com.colink.android.domain.model.Note
import com.colink.android.domain.model.NoteAttachment
import com.colink.android.domain.model.NoteSyncState
import com.colink.android.domain.model.NoteTag
import com.colink.android.domain.model.NotesStorage
import com.colink.android.domain.model.NotesSyncOutcome
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.NotesRepository
import com.colink.android.data.local.datastore.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private const val NOTES_PATH = "/api/v1/notes"
private const val NOTE_TAGS_PATH = "/api/v1/note-tags"
private const val NOTE_ATTACHMENTS_PATH = "/api/v1/note-attachments"
private const val NOTES_SNAPSHOT_PATH = "/api/v1/notes/sync/snapshot"
private const val NOTES_CHANGES_PATH = "/api/v1/notes/sync/changes"
private const val NOTES_STORAGE_PATH = "/api/v1/notes/storage"

private const val CURSOR_KEY = "notes_sync_cursor"
private const val SYNC_PAGE_LIMIT = 200
private const val CODE_NOTE_NOT_FOUND = 6001
private const val CODE_REVISION_CONFLICT = 6002
private const val CODE_TAG_NOT_FOUND = 6003
private const val CODE_TAG_NAME_CONFLICT = 6004
private const val CODE_ATTACHMENT_NOT_FOUND = 6005
private const val CODE_INVALID_NOTE_REFERENCE = 6008
private const val CODE_ATTACHMENT_ID_UNAVAILABLE = 6011
private const val MAX_ATTACHMENT_REKEY_ATTEMPTS = 3

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: CoLinkDatabase,
    private val noteDao: NoteDao,
    private val noteTagDao: NoteTagDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val noteSyncKvDao: NoteSyncKvDao,
    private val notesApi: NotesApi,
    private val settingsDataStore: SettingsDataStore,
    private val authRepository: AuthRepository,
    private val notesScopeManager: NotesScopeManager,
) : NotesRepository {

    private val syncMutex = Mutex()
    private val accountScopes = combine(settingsDataStore.settings, settingsDataStore.session) { settings, session ->
        session?.let { noteAccountScope(settings.serverUrl, it.userId) } ?: LOCAL_ACCOUNT_SCOPE
    }.distinctUntilChanged()

    override val notes: Flow<List<Note>> =
        accountScopes.flatMapLatest { scope ->
            flow {
                prepareScope(scope)
                emitAll(
                    noteDao.observeNotes(scope, NoteSyncState.PendingDelete.name)
                        .map { entities -> entities.map { it.toDomain() } },
                )
            }
        }

    override val tags: Flow<List<NoteTag>> =
        accountScopes.flatMapLatest { scope ->
            flow {
                prepareScope(scope)
                emitAll(
                    noteTagDao.observeTags(scope, NoteSyncState.PendingDelete.name)
                        .map { entities -> entities.map { it.toDomain() } },
                )
            }
        }

    private suspend fun currentScope(): String {
        val settings = settingsDataStore.currentSettings()
        val session = settingsDataStore.currentSession()
        val scope = session?.let { noteAccountScope(settings.serverUrl, it.userId) } ?: LOCAL_ACCOUNT_SCOPE
        prepareScope(scope)
        return scope
    }

    private suspend fun prepareScope(scope: String) {
        notesScopeManager.prepare(scope)
    }

    private fun attachmentCacheDirectory(scope: String): File =
        notesScopeManager.attachmentCacheDirectory(scope)

    private fun attachmentCacheFile(scope: String, attachmentId: String): File =
        notesScopeManager.attachmentCacheFile(scope, attachmentId)

    override suspend fun refreshLocals() {
        currentScope()
    }

    override suspend fun getNote(noteId: String): Note? {
        val scope = currentScope()
        return noteDao.find(scope, noteId)?.toDomain()
    }

    override suspend fun upsertNote(
        noteId: String?,
        title: String,
        markdown: String,
        tagIds: List<String>,
        attachmentIds: List<String>,
    ): Result<Note> =
        runCatching {
            val scope = currentScope()
            val now = System.currentTimeMillis()
            val id = noteId ?: UUID.randomUUID().toString()
            val existing = noteDao.find(scope, id)
            require(existing?.syncState != NoteSyncState.Conflict.name) { "resolve the conflict before editing this note" }
            require(existing?.syncState != NoteSyncState.ConflictDelete.name) { "note is pending deletion" }

            val normalizedTags = normalizeIds(tagIds)
            val normalizedAttachments = normalizeIds(attachmentIds)

            val entity = existing?.copy(
                title = title,
                markdown = markdown,
                tagIds = encodeIds(normalizedTags),
                attachmentIds = encodeIds(normalizedAttachments),
                syncState = NoteSyncState.Pending.name,
                updatedAt = now,
            ) ?: NoteEntity.newPending(id, title, markdown, normalizedTags, normalizedAttachments, now)

            noteDao.upsert(scope, entity)
            entity.toDomain()
        }

    override suspend fun deleteNote(noteId: String): Result<Unit> =
        runCatching {
            val scope = currentScope()
            val existing = noteDao.find(scope, noteId) ?: error("note not found")
            if (existing.revision == 0L) {
                noteDao.delete(scope, noteId)
            } else {
                noteDao.upsert(
                    scope,
                    existing.copy(
                        syncState = NoteSyncState.PendingDelete.name,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

    override suspend fun createTag(name: String): Result<NoteTag> =
        runCatching {
            val scope = currentScope()
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "tag name must not be empty" }
            val existing = noteTagDao.findByNormalizedName(scope, normalizeTagName(trimmed))
            if (existing != null) {
                return@runCatching existing.toDomain()
            }
            val now = System.currentTimeMillis()
            val entity = NoteTagEntity(
                tagId = UUID.randomUUID().toString(),
                name = trimmed,
                nameNormalized = normalizeTagName(trimmed),
                revision = 0,
                baseRevision = 0,
                syncState = NoteSyncState.Pending.name,
                deleted = false,
                createdAt = now,
                updatedAt = now,
            )
            noteTagDao.upsert(scope, entity)
            entity.toDomain()
        }

    override suspend fun renameTag(tagId: String, name: String): Result<NoteTag> =
        runCatching {
            val scope = currentScope()
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "tag name must not be empty" }
            val existing = noteTagDao.find(scope, tagId) ?: error("tag not found")
            noteTagDao.findByNormalizedName(scope, normalizeTagName(trimmed))?.let { duplicate ->
                require(duplicate.tagId == tagId) { "tag name already exists" }
            }
            val updated = existing.copy(
                name = trimmed,
                nameNormalized = normalizeTagName(trimmed),
                syncState = NoteSyncState.Pending.name,
                updatedAt = System.currentTimeMillis(),
            )
            noteTagDao.upsert(scope, updated)
            updated.toDomain()
        }

    override suspend fun deleteTag(tagId: String): Result<Unit> =
        runCatching {
            val scope = currentScope()
            val existing = noteTagDao.find(scope, tagId) ?: error("tag not found")
            if (existing.revision == 0L) {
                database.withTransaction {
                    removeTagFromNotes(scope, tagId, onlySynced = false)
                    noteTagDao.delete(scope, tagId)
                }
            } else {
                noteTagDao.upsert(
                    scope,
                    existing.copy(
                        syncState = NoteSyncState.PendingDelete.name,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

    override suspend fun stageAttachment(uri: String, kind: String, fileName: String): Result<NoteAttachment> =
        runCatching {
            val scope = currentScope()
            require(kind == "image" || kind == "file") { "invalid attachment kind" }
            val source = context.contentResolver.openInputStream(Uri.parse(uri))
                ?: error("cannot open selected file")
            val attachmentId = UUID.randomUUID().toString()

            val target = attachmentCacheFile(scope, attachmentId)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                source.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            size += read
                        }
                    }
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }

            val entity = NoteAttachmentEntity(
                attachmentId = attachmentId,
                kind = kind,
                fileName = fileName.ifBlank { "attachment" },
                mediaType = context.contentResolver.getType(Uri.parse(uri)).orEmpty(),
                size = size,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                syncState = NoteSyncState.Pending.name,
                deleted = false,
                createdAt = System.currentTimeMillis(),
            )
            noteAttachmentDao.upsert(scope, entity)
            entity.toDomain()
        }

    override suspend fun listAttachments(): List<NoteAttachment> {
        val scope = currentScope()
        return noteAttachmentDao.listAttachments(scope).map { it.toDomain() }
    }

    override suspend fun removeAttachment(attachmentId: String): Result<Unit> =
        runCatching {
            val scope = currentScope()
            val existing = noteAttachmentDao.find(scope, attachmentId) ?: error("attachment not found")
            if (existing.syncState != NoteSyncState.Pending.name) {
                val serverUrl = settingsDataStore.currentSettings().serverUrl
                authRepository.currentSession().getOrNull()?.let { session ->
                    notesApi.deleteAttachment(
                        url = apiEndpoint(serverUrl, "$NOTE_ATTACHMENTS_PATH/$attachmentId"),
                        authorization = bearer(session.accessToken),
                    ).requireOk()
                }
            }
            noteAttachmentDao.delete(scope, attachmentId)
            attachmentCacheFile(scope, attachmentId).delete()
        }

    override suspend fun ensureAttachmentCached(attachmentId: String): Result<File> =
        runCatching {
            val scope = currentScope()
            val metadata = noteAttachmentDao.find(scope, attachmentId) ?: error("attachment not found")
            require(!metadata.deleted) { "attachment not found" }
            val target = attachmentCacheFile(scope, attachmentId)
            if (target.isFile &&
                (metadata.size <= 0L || target.length() == metadata.size) &&
                (metadata.sha256.isEmpty() || sha256File(target) == metadata.sha256)
            ) {
                return@runCatching target
            }
            if (target.exists()) {
                target.delete()
            }

            val serverUrl = settingsDataStore.currentSettings().serverUrl
            val session = authRepository.currentSession().getOrNull() ?: error("attachment not found")
            val response = notesApi.downloadAttachment(
                url = apiEndpoint(serverUrl, "$NOTE_ATTACHMENTS_PATH/$attachmentId/content"),
                authorization = bearer(session.accessToken),
            )
            if (!response.isSuccessful) {
                error("attachment download failed: ${response.code()}")
            }
            val temp = File(target.parentFile, "$attachmentId.part")
            temp.delete()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val body = response.body() ?: error("empty attachment body")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            size += read
                        }
                    }
                }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                if (metadata.size >= 0L && size != metadata.size) {
                    error("attachment size mismatch")
                }
                if (metadata.sha256.isNotEmpty() && actualDigest != metadata.sha256) {
                    error("attachment checksum mismatch")
                }
                temp.renameTo(target) || error("cannot persist attachment cache")
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
            target
        }

    override suspend fun fetchStorage(): Result<NotesStorage> =
        runCatching {
            val serverUrl = settingsDataStore.currentSettings().serverUrl
            val session = authRepository.currentSession().getOrThrow()
            val dto = notesApi.storage(
                url = apiEndpoint(serverUrl, NOTES_STORAGE_PATH),
                authorization = bearer(session.accessToken),
            ).requireData()
            NotesStorage(
                usedBytes = dto.usedBytes,
                limitBytes = dto.limitBytes,
                remainingBytes = dto.remainingBytes,
                attachmentBytes = dto.attachmentBytes,
                markdownBytes = dto.markdownBytes,
            )
        }

    // ------------------------------------------------------------------
    // Synchronization engine
    // ------------------------------------------------------------------

    override suspend fun sync(): Result<NotesSyncOutcome> = syncMutex.withLock {
        runCatching {
            beginCounters()

            val session = authRepository.currentSession().getOrNull()
                ?: return@runCatching NotesSyncOutcome(
                    status = "offline",
                    pushedNotes = 0,
                    pushedTags = 0,
                    pushedAttachments = 0,
                    pulledNotes = 0,
                    pulledTags = 0,
                    conflicts = 0,
                    repairedReferences = 0,
                )

            val serverUrl = settingsDataStore.currentSettings().serverUrl
            val scope = noteAccountScope(serverUrl, session.userId)
            prepareScope(scope)
            val token = bearer(session.accessToken)

            pushAttachments(scope, serverUrl, token)
            pushTags(scope, serverUrl, token)
            pushNotes(scope, serverUrl, token)

            val cursor = noteSyncKvDao.read(scope, CURSOR_KEY)
            if (cursor == null) {
                pullSnapshot(scope, serverUrl, token)
            } else {
                try {
                    pullIncremental(scope, serverUrl, token, cursor)
                } catch (error: ApiException) {
                    if (error.code == CODE_SYNC_CURSOR_EXPIRED) {
                        noteSyncKvDao.delete(scope, CURSOR_KEY)
                        pullSnapshot(scope, serverUrl, token)
                    } else {
                        throw error
                    }
                }
            }

            // Second push pass for notes whose base revision advanced during
            // the pull (clean three-way merges).
            pushAttachments(scope, serverUrl, token)
            pushTags(scope, serverUrl, token)
            pushNotes(scope, serverUrl, token)

            NotesSyncOutcome(
                status = "ok",
                pushedNotes = pushedNotesTotal,
                pushedTags = pushedTagsTotal,
                pushedAttachments = pushedAttachmentsTotal,
                pulledNotes = pulledNotesTotal,
                pulledTags = pulledTagsTotal,
                conflicts = conflictsTotal,
                repairedReferences = repairedReferencesTotal,
            )
        }
    }

    // Counters tracked across the two push passes.
    private var pushedNotesTotal = 0
    private var pushedTagsTotal = 0
    private var pushedAttachmentsTotal = 0
    private var pulledNotesTotal = 0
    private var pulledTagsTotal = 0
    private var conflictsTotal = 0
    private var repairedReferencesTotal = 0

    private fun beginCounters() {
        pushedNotesTotal = 0
        pushedTagsTotal = 0
        pushedAttachmentsTotal = 0
        pulledNotesTotal = 0
        pulledTagsTotal = 0
        conflictsTotal = 0
        repairedReferencesTotal = 0
    }

    private suspend fun pushAttachments(scope: String, serverUrl: String, token: String) {
        val pending = ArrayDeque(
            noteAttachmentDao.listAttachments(scope).filter {
                (it.syncState == NoteSyncState.Pending.name || it.syncState == NoteSyncState.Conflict.name) && !it.deleted
            }.map { it to 0 },
        )
        val attempted = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (record, rekeyAttempts) = pending.removeFirst()
            if (!attempted.add(record.attachmentId)) continue
            val file = attachmentCacheFile(scope, record.attachmentId)
            if (!file.exists()) {
                removeCurrentAttachmentReferences(scope, record.attachmentId)
                noteAttachmentDao.delete(scope, record.attachmentId)
                repairedReferencesTotal++
                continue
            }
            if (record.syncState == NoteSyncState.Conflict.name) {
                check(rekeyAttempts < MAX_ATTACHMENT_REKEY_ATTEMPTS) {
                    "attachment ID unavailable after $MAX_ATTACHMENT_REKEY_ATTEMPTS replacement attempts"
                }
                pending.addLast(rekeyAttachment(scope, record) to rekeyAttempts + 1)
                continue
            }

            try {
                val dto = notesApi.uploadAttachment(
                    url = apiEndpoint(serverUrl, NOTE_ATTACHMENTS_PATH),
                    authorization = token,
                    attachmentId = textPart("attachmentId", record.attachmentId),
                    kind = textPart("kind", record.kind),
                    sha256 = textPart("sha256", record.sha256),
                    file = MultipartBody.Part.createFormData(
                        "file",
                        record.fileName,
                        file.asRequestBody(record.mediaType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()),
                    ),
                ).requireData()
                noteAttachmentDao.upsert(
                    scope,
                    record.copy(syncState = NoteSyncState.Synced.name, mediaType = dto.mediaType),
                )
                pushedAttachmentsTotal++
            } catch (error: ApiException) {
                if (error.code == CODE_ATTACHMENT_ID_UNAVAILABLE) {
                    // A timed-out upload may already exist. Adopt matching
                    // content; tombstones and true collisions require rekeying.
                    val metadata = try {
                        notesApi.attachmentMetadata(
                            url = apiEndpoint(serverUrl, "$NOTE_ATTACHMENTS_PATH/${record.attachmentId}"),
                            authorization = token,
                        ).requireData()
                    } catch (metaError: ApiException) {
                        if (metaError.code == CODE_ATTACHMENT_NOT_FOUND) {
                            check(rekeyAttempts < MAX_ATTACHMENT_REKEY_ATTEMPTS) {
                                "attachment ID unavailable after $MAX_ATTACHMENT_REKEY_ATTEMPTS replacement attempts"
                            }
                            pending.addLast(rekeyAttachment(scope, record) to rekeyAttempts + 1)
                            continue
                        } else {
                            throw metaError
                        }
                    }
                    if (metadata.kind == record.kind &&
                        metadata.size == record.size &&
                        metadata.sha256 == record.sha256
                    ) {
                        noteAttachmentDao.upsert(
                            scope,
                            record.copy(syncState = NoteSyncState.Synced.name, mediaType = metadata.mediaType),
                        )
                        pushedAttachmentsTotal++
                    } else {
                        check(rekeyAttempts < MAX_ATTACHMENT_REKEY_ATTEMPTS) {
                            "attachment ID unavailable after $MAX_ATTACHMENT_REKEY_ATTEMPTS replacement attempts"
                        }
                        pending.addLast(rekeyAttachment(scope, record) to rekeyAttempts + 1)
                    }
                } else {
                    throw error
                }
            }
        }
    }

    private suspend fun pushTags(scope: String, serverUrl: String, token: String) {
        val rows = noteTagDao.listAllRows(scope).filter { !it.deleted }

        for (record in rows) {
            when (record.syncState) {
                NoteSyncState.PendingDelete.name -> {
                    try {
                        notesApi.deleteTag(
                            url = apiEndpoint(serverUrl, "$NOTE_TAGS_PATH/${record.tagId}?baseRevision=${record.baseRevision}"),
                            authorization = token,
                        ).requireOk()
                        noteTagDao.delete(scope, record.tagId)
                        removeTagFromNotes(scope, record.tagId, onlySynced = false)
                        pushedTagsTotal++
                    } catch (error: ApiException) {
                        when (error.code) {
                            CODE_REVISION_CONFLICT -> {
                                val cloud = notesApi.listTags(
                                    url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
                                    authorization = token,
                                ).requireData().tags.find { it.tagId == record.tagId }
                                if (cloud == null) {
                                    noteTagDao.delete(scope, record.tagId)
                                    removeTagFromNotes(scope, record.tagId, onlySynced = false)
                                } else {
                                    noteTagDao.upsert(
                                        scope,
                                        record.copy(
                                            name = cloud.name,
                                            nameNormalized = normalizeTagName(cloud.name.trim()),
                                            revision = cloud.revision,
                                            baseRevision = cloud.revision,
                                            syncState = NoteSyncState.Conflict.name,
                                            createdAt = parseTimestampMillis(cloud.createdAt, record.createdAt),
                                            updatedAt = parseTimestampMillis(cloud.updatedAt, System.currentTimeMillis()),
                                        ),
                                    )
                                    conflictsTotal++
                                }
                            }
                            CODE_TAG_NOT_FOUND -> {
                                noteTagDao.delete(scope, record.tagId)
                                removeTagFromNotes(scope, record.tagId, onlySynced = false)
                            }
                            else -> throw error
                        }
                    }
                }
                NoteSyncState.Pending.name -> {
                    if (record.baseRevision == 0L) {
                        try {
                            val dto = notesApi.createTag(
                                url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
                                authorization = token,
                                request = TagCreateRequestDto(tagId = record.tagId, name = record.name),
                            ).requireData()
                            noteTagDao.upsert(
                                scope,
                                record.copy(
                                    revision = dto.revision,
                                    baseRevision = dto.revision,
                                    syncState = NoteSyncState.Synced.name,
                                    createdAt = parseTimestampMillis(dto.createdAt, record.createdAt),
                                    updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
                                ),
                            )
                            pushedTagsTotal++
                        } catch (error: ApiException) {
                            if (error.code == CODE_TAG_NAME_CONFLICT || error.code == 4002) {
                                handleTagCreateConflict(scope, serverUrl, token, record)
                            } else {
                                throw error
                            }
                        }
                    } else {
                        try {
                            val dto = notesApi.updateTag(
                                url = apiEndpoint(serverUrl, "$NOTE_TAGS_PATH/${record.tagId}"),
                                authorization = token,
                                request = TagUpdateRequestDto(
                                    baseRevision = record.baseRevision,
                                    name = record.name,
                                ),
                            ).requireData()
                            noteTagDao.upsert(
                                scope,
                                record.copy(
                                    name = dto.name,
                                    revision = dto.revision,
                                    baseRevision = dto.revision,
                                    syncState = NoteSyncState.Synced.name,
                                    createdAt = parseTimestampMillis(dto.createdAt, record.createdAt),
                                    updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
                                ),
                            )
                            pushedTagsTotal++
                        } catch (error: ApiException) {
                            when (error.code) {
                                CODE_REVISION_CONFLICT -> {
                                    val cloud = try {
                                        notesApi.listTags(
                                            url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
                                            authorization = token,
                                        ).requireData()
                                    } catch (listError: ApiException) {
                                        throw listError
                                    }
                                    val cloudTag = cloud.tags.find { it.tagId == record.tagId }
                                    if (cloudTag != null) {
                                        // Keep the cloud name when the renames raced.
                                        val raced = cloudTag.name != record.name
                                        noteTagDao.upsert(
                                            scope,
                                            record.copy(
                                                name = cloudTag.name,
                                                nameNormalized = normalizeTagName(cloudTag.name.trim()),
                                                revision = cloudTag.revision,
                                                baseRevision = cloudTag.revision,
                                                syncState = NoteSyncState.Synced.name,
                                                createdAt = parseTimestampMillis(cloudTag.createdAt, record.createdAt),
                                                updatedAt = parseTimestampMillis(cloudTag.updatedAt, System.currentTimeMillis()),
                                            ),
                                        )
                                        if (raced) conflictsTotal++ else pushedTagsTotal++
                                    }
                                }
                                CODE_TAG_NOT_FOUND -> {
                                    noteTagDao.delete(scope, record.tagId)
                                    removeTagFromNotes(scope, record.tagId, onlySynced = false)
                                }
                                else -> throw error
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleTagCreateConflict(scope: String, serverUrl: String, token: String, record: NoteTagEntity) {
        val cloudTags = try {
            notesApi.listTags(
                url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
                authorization = token,
            ).requireData()
        } catch (error: ApiException) {
            throw error
        }

        val normalized = normalizeTagName(record.name.trim())
        val existing = cloudTags.tags.find { normalizeTagName(it.name.trim()) == normalized }
        if (existing != null) {
            if (existing.tagId == record.tagId) {
                noteTagDao.upsert(
                    scope,
                    record.copy(
                        name = existing.name,
                        nameNormalized = normalizeTagName(existing.name.trim()),
                        revision = existing.revision,
                        baseRevision = existing.revision,
                        syncState = NoteSyncState.Synced.name,
                        createdAt = parseTimestampMillis(existing.createdAt, record.createdAt),
                        updatedAt = parseTimestampMillis(existing.updatedAt, System.currentTimeMillis()),
                    ),
                )
                pushedTagsTotal++
            } else {
                database.withTransaction {
                    retargetTagReferences(scope, record.tagId, existing.tagId)
                    noteTagDao.delete(scope, record.tagId)
                }
                conflictsTotal++
            }
            return
        }

        val replacement = record.copy(
            tagId = UUID.randomUUID().toString(),
            revision = 0,
            baseRevision = 0,
            syncState = NoteSyncState.Pending.name,
            updatedAt = System.currentTimeMillis(),
        )
        database.withTransaction {
            noteTagDao.upsert(scope, replacement)
            retargetTagReferences(scope, record.tagId, replacement.tagId)
            noteTagDao.delete(scope, record.tagId)
        }

        val dto = notesApi.createTag(
            url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
            authorization = token,
            request = TagCreateRequestDto(tagId = replacement.tagId, name = replacement.name),
        ).requireData()
        noteTagDao.upsert(
            scope,
            replacement.copy(
                name = dto.name,
                nameNormalized = normalizeTagName(dto.name.trim()),
                revision = dto.revision,
                baseRevision = dto.revision,
                syncState = NoteSyncState.Synced.name,
                createdAt = parseTimestampMillis(dto.createdAt, replacement.createdAt),
                updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
            ),
        )
        pushedTagsTotal++
    }

    private suspend fun pushNotes(scope: String, serverUrl: String, token: String) {
        val pending = noteDao.listPending(
            scope = scope,
            pendingState = NoteSyncState.Pending.name,
            pendingDeleteState = NoteSyncState.PendingDelete.name,
        ).filter { !it.deleted }
        for (record in pending) {
            when (record.syncState) {
                NoteSyncState.PendingDelete.name -> pushNoteDelete(scope, serverUrl, token, record)
                NoteSyncState.Pending.name -> {
                    if (record.baseRevision == 0L) {
                        pushNoteCreate(scope, serverUrl, token, record)
                    } else {
                        pushNoteUpdate(scope, serverUrl, token, record)
                    }
                }
            }
        }
    }

    private suspend fun pushNoteCreate(
        scope: String,
        serverUrl: String,
        token: String,
        record: NoteEntity,
        allowReferenceRecovery: Boolean = true,
    ) {
        try {
            val dto = notesApi.createNote(
                url = apiEndpoint(serverUrl, NOTES_PATH),
                authorization = token,
                request = NoteCreateRequestDto(
                    noteId = record.noteId,
                    title = record.title,
                    markdown = record.markdown,
                    tagIds = decodeIds(record.tagIds),
                    attachmentIds = decodeIds(record.attachmentIds),
                ),
            ).requireData()
            markNoteSynced(scope, record, dto)
            pushedNotesTotal++
        } catch (error: ApiException) {
            when (error.code) {
                4002 -> {
                    // Uncertain outcome: inspect the server state.
                    val cloud = try {
                        notesApi.getNote(
                            url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                            authorization = token,
                        ).requireData()
                    } catch (getError: ApiException) {
                        if (getError.code == CODE_NOTE_NOT_FOUND) {
                            // Id still free: retry once with the original id.
                            try {
                                val dto = notesApi.createNote(
                                    url = apiEndpoint(serverUrl, NOTES_PATH),
                                    authorization = token,
                                    request = NoteCreateRequestDto(
                                        noteId = record.noteId,
                                        title = record.title,
                                        markdown = record.markdown,
                                        tagIds = decodeIds(record.tagIds),
                                        attachmentIds = decodeIds(record.attachmentIds),
                                    ),
                                ).requireData()
                                markNoteSynced(scope, record, dto)
                                pushedNotesTotal++
                            } catch (retryError: ApiException) {
                                throw retryError
                            }
                            return
                        } else {
                            throw getError
                        }
                    }
                    if (cloudContentMatches(record, cloud)) {
                        markNoteSynced(scope, record, cloud)
                        pushedNotesTotal++
                    } else {
                        enterConflict(scope, record, cloud, CONFLICT_KIND_EDIT)
                        conflictsTotal++
                    }
                }
                CODE_INVALID_NOTE_REFERENCE -> {
                    if (!allowReferenceRecovery) throw error
                    recoverInvalidReferences(scope, serverUrl, token, record)
                }
                else -> throw error
            }
        }
    }

    private suspend fun pushNoteUpdate(
        scope: String,
        serverUrl: String,
        token: String,
        record: NoteEntity,
        allowReferenceRecovery: Boolean = true,
    ) {
        try {
            val dto = notesApi.updateNote(
                url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                authorization = token,
                request = NoteUpdateRequestDto(
                    baseRevision = record.baseRevision,
                    title = record.title,
                    markdown = record.markdown,
                    tagIds = decodeIds(record.tagIds),
                    attachmentIds = decodeIds(record.attachmentIds),
                ),
            ).requireData()
            markNoteSynced(scope, record, dto)
            pushedNotesTotal++
        } catch (error: ApiException) {
            when (error.code) {
                CODE_REVISION_CONFLICT -> {
                    val cloud = try {
                        notesApi.getNote(
                            url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                            authorization = token,
                        ).requireData()
                    } catch (getError: ApiException) {
                        throw getError
                    }
                    if (cloudContentMatches(record, cloud)) {
                        markNoteSynced(scope, record, cloud)
                        pushedNotesTotal++
                    } else {
                        attemptMergedPush(scope, serverUrl, token, record, cloud)
                    }
                }
                CODE_NOTE_NOT_FOUND -> {
                    // Cloud deleted while local edits are pending.
                    noteDao.upsert(
                        scope,
                        record.copy(
                            syncState = NoteSyncState.Conflict.name,
                            conflictKind = CONFLICT_KIND_CLOUD_DELETED,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    conflictsTotal++
                }
                CODE_INVALID_NOTE_REFERENCE -> {
                    if (!allowReferenceRecovery) throw error
                    recoverInvalidReferences(scope, serverUrl, token, record)
                }
                else -> throw error
            }
        }
    }

    private suspend fun pushNoteDelete(scope: String, serverUrl: String, token: String, record: NoteEntity) {
        try {
            notesApi.deleteNote(
                url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}?baseRevision=${record.baseRevision}"),
                authorization = token,
            ).requireOk()
            noteDao.delete(scope, record.noteId)
            pushedNotesTotal++
        } catch (error: ApiException) {
            when (error.code) {
                CODE_REVISION_CONFLICT -> {
                    val cloud = try {
                        notesApi.getNote(
                            url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                            authorization = token,
                        ).requireData()
                    } catch (getError: ApiException) {
                        if (getError.code == CODE_NOTE_NOT_FOUND) {
                            noteDao.delete(scope, record.noteId)
                            pushedNotesTotal++
                            return
                        } else {
                            throw getError
                        }
                    }
                    persistCloudAttachments(scope, cloud.attachments)
                    noteDao.upsert(
                        scope,
                        record.copy(
                            syncState = NoteSyncState.ConflictDelete.name,
                            conflictKind = CONFLICT_KIND_DELETE,
                            conflictTitle = cloud.title,
                            conflictMarkdown = cloud.markdown,
                            conflictTagIds = encodeIds(normalizeIds(cloud.tagIds)),
                            conflictAttachmentIds = encodeIds(cloud.attachments.map { it.attachmentId }),
                            conflictRevision = cloud.revision,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    conflictsTotal++
                }
                CODE_NOTE_NOT_FOUND -> {
                    noteDao.delete(scope, record.noteId)
                    pushedNotesTotal++
                }
                else -> throw error
            }
        }
    }

    private suspend fun attemptMergedPush(scope: String, serverUrl: String, token: String, record: NoteEntity, cloud: NoteDto) {
        val merge = NoteMerger.mergeNote(
            ancestorTitle = record.ancestorTitle,
            ancestorMarkdown = record.ancestorMarkdown,
            ancestorTagIds = decodeIds(record.ancestorTagIds),
            ancestorAttachmentIds = decodeIds(record.ancestorAttachmentIds),
            localTitle = record.title,
            localMarkdown = record.markdown,
            localTagIds = decodeIds(record.tagIds),
            localAttachmentIds = decodeIds(record.attachmentIds),
            cloudTitle = cloud.title,
            cloudMarkdown = cloud.markdown,
            cloudTagIds = normalizeIds(cloud.tagIds),
            cloudAttachmentIds = cloud.attachments.map { it.attachmentId },
        )

        val mergedMarkdown = merge.markdown.resolved
        if (mergedMarkdown == null || !merge.title.isResolved) {
            noteDao.upsert(
                scope,
                record.copy(
                    syncState = NoteSyncState.Conflict.name,
                    conflictKind = CONFLICT_KIND_EDIT,
                    conflictTitle = cloud.title,
                    conflictMarkdown = cloud.markdown,
                    conflictTagIds = encodeIds(normalizeIds(cloud.tagIds)),
                    conflictAttachmentIds = encodeIds(cloud.attachments.map { it.attachmentId }),
                    conflictRevision = cloud.revision,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            conflictsTotal++
            return
        }

        val updated = record.copy(
            title = merge.title.resolved ?: record.title,
            markdown = mergedMarkdown,
            tagIds = encodeIds(merge.mergedTagIds),
            attachmentIds = encodeIds(merge.mergedAttachmentIds),
            baseRevision = cloud.revision,
            updatedAt = System.currentTimeMillis(),
        )
        noteDao.upsert(scope, updated)

        try {
            val dto = notesApi.updateNote(
                url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                authorization = token,
                request = NoteUpdateRequestDto(
                    baseRevision = cloud.revision,
                    title = updated.title,
                    markdown = updated.markdown,
                    tagIds = decodeIds(updated.tagIds),
                    attachmentIds = decodeIds(updated.attachmentIds),
                ),
            ).requireData()
            markNoteSynced(scope, updated, dto)
            pushedNotesTotal++
        } catch (error: ApiException) {
            if (error.code == CODE_INVALID_NOTE_REFERENCE) {
                recoverInvalidReferences(scope, serverUrl, token, updated)
            } else {
                throw error
            }
        }
    }

    private suspend fun recoverInvalidReferences(scope: String, serverUrl: String, token: String, record: NoteEntity) {
        refreshTagList(scope, serverUrl, token)

        val liveTags = noteTagDao.listTags(scope, NoteSyncState.PendingDelete.name)
            .filter { !it.deleted && it.syncState != NoteSyncState.PendingDelete.name }
            .map { it.tagId }
            .toHashSet()
        var updated = record.copy(
            tagIds = encodeIds(decodeIds(record.tagIds).filter { liveTags.contains(it) }),
        )
        noteDao.upsert(scope, updated)

        for (attachmentId in decodeIds(updated.attachmentIds)) {
            try {
                val metadata = notesApi.attachmentMetadata(
                    url = apiEndpoint(serverUrl, "$NOTE_ATTACHMENTS_PATH/$attachmentId"),
                    authorization = token,
                ).requireData()
                persistCloudAttachments(scope, listOf(metadata))
            } catch (error: ApiException) {
                if (error.code != CODE_ATTACHMENT_NOT_FOUND) throw error
                val local = noteAttachmentDao.find(scope, attachmentId)
                val file = attachmentCacheFile(scope, attachmentId)
                if (local != null && file.isFile) {
                    val replacement = rekeyAttachment(scope, local)
                    pushSingleAttachment(scope, serverUrl, token, replacement)
                } else {
                    removeCurrentAttachmentReferences(scope, attachmentId)
                    noteAttachmentDao.delete(scope, attachmentId)
                    repairedReferencesTotal++
                }
                updated = noteDao.find(scope, record.noteId) ?: updated
            }
        }

        if (updated.baseRevision == 0L) {
            noteDao.upsert(scope, updated)
            pushNoteCreate(scope, serverUrl, token, updated, allowReferenceRecovery = false)
            return
        }

        val cloud = try {
            notesApi.getNote(
                url = apiEndpoint(serverUrl, "$NOTES_PATH/${record.noteId}"),
                authorization = token,
            ).requireData()
        } catch (error: ApiException) {
            if (error.code == CODE_NOTE_NOT_FOUND) {
                noteDao.upsert(
                    scope,
                    updated.copy(
                        syncState = NoteSyncState.Conflict.name,
                        conflictKind = CONFLICT_KIND_CLOUD_DELETED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                conflictsTotal++
                return
            } else {
                throw error
            }
        }

        if (updated.baseRevision != cloud.revision) {
            val merge = NoteMerger.mergeNote(
                ancestorTitle = updated.ancestorTitle,
                ancestorMarkdown = updated.ancestorMarkdown,
                ancestorTagIds = decodeIds(updated.ancestorTagIds),
                ancestorAttachmentIds = decodeIds(updated.ancestorAttachmentIds),
                localTitle = updated.title,
                localMarkdown = updated.markdown,
                localTagIds = decodeIds(updated.tagIds),
                localAttachmentIds = decodeIds(updated.attachmentIds),
                cloudTitle = cloud.title,
                cloudMarkdown = cloud.markdown,
                cloudTagIds = normalizeIds(cloud.tagIds),
                cloudAttachmentIds = cloud.attachments.map { it.attachmentId },
            )
            val mergedMarkdown = merge.markdown.resolved
            if (mergedMarkdown == null || !merge.title.isResolved) {
                enterConflict(scope, updated, cloud, CONFLICT_KIND_EDIT)
                conflictsTotal++
                return
            }
            updated = updated.copy(
                title = merge.title.resolved ?: updated.title,
                markdown = mergedMarkdown,
                tagIds = encodeIds(merge.mergedTagIds),
                attachmentIds = encodeIds(merge.mergedAttachmentIds),
                baseRevision = cloud.revision,
            )
            noteDao.upsert(scope, updated)
        } else {
            noteDao.upsert(scope, updated)
        }

        // Resubmit exactly once with the repaired references and current revision.
        pushNoteUpdate(scope, serverUrl, token, updated, allowReferenceRecovery = false)
    }

    private suspend fun refreshTagList(scope: String, serverUrl: String, token: String) {
        val cloudTags = try {
            notesApi.listTags(
                url = apiEndpoint(serverUrl, NOTE_TAGS_PATH),
                authorization = token,
            ).requireData()
        } catch (error: ApiException) {
            throw error
        }

        val cloudIds = cloudTags.tags.map { it.tagId }.toHashSet()
        val now = System.currentTimeMillis()
        for (tag in cloudTags.tags) {
            val existing = noteTagDao.find(scope, tag.tagId)
            when {
                existing == null -> noteTagDao.upsert(
                    scope,
                    NoteTagEntity(
                        tagId = tag.tagId,
                        name = tag.name,
                        nameNormalized = normalizeTagName(tag.name.trim()),
                        revision = tag.revision,
                        baseRevision = tag.revision,
                        syncState = NoteSyncState.Synced.name,
                        deleted = false,
                        createdAt = parseTimestampMillis(tag.createdAt, now),
                        updatedAt = parseTimestampMillis(tag.updatedAt, now),
                    ),
                )
                existing.syncState == NoteSyncState.Synced.name -> noteTagDao.upsert(
                    scope,
                    existing.copy(
                        name = tag.name,
                        nameNormalized = normalizeTagName(tag.name.trim()),
                        revision = tag.revision,
                        baseRevision = tag.revision,
                        updatedAt = parseTimestampMillis(tag.updatedAt, now),
                    ),
                )
            }
        }
        for (tag in noteTagDao.listAllRows(scope)) {
            if (tag.syncState == NoteSyncState.Synced.name && !cloudIds.contains(tag.tagId)) {
                noteTagDao.delete(scope, tag.tagId)
                removeTagFromNotes(scope, tag.tagId, onlySynced = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    private suspend fun pullSnapshot(scope: String, serverUrl: String, token: String) {
        var pageToken: String? = null
        var cursor = ""
        val seenNotes = mutableSetOf<String>()
        val seenTags = mutableSetOf<String>()

        while (true) {
            val page = notesApi.snapshot(
                url = apiEndpoint(serverUrl, NOTES_SNAPSHOT_PATH),
                authorization = token,
                pageToken = pageToken,
                limit = SYNC_PAGE_LIMIT,
            ).requireData()

            for (tag in page.tags) {
                applyTagUpsert(scope, tag)
                seenTags.add(tag.tagId)
            }
            for (note in page.notes) {
                applyNoteUpsert(scope, note)
                seenNotes.add(note.noteId)
            }

            val next = page.nextPageToken
            if (next == null) {
                cursor = page.cursor
                break
            }
            pageToken = next
        }

        database.withTransaction {
            reconcileSnapshot(scope, seenNotes, seenTags)
            noteSyncKvDao.write(scope, NoteSyncKvEntity(key = CURSOR_KEY, value = cursor))
        }
        pullIncremental(scope, serverUrl, token, cursor)
    }

    private suspend fun pullIncremental(scope: String, serverUrl: String, token: String, initialCursor: String) {
        var cursor = initialCursor
        while (true) {
            val page = notesApi.changes(
                url = apiEndpoint(serverUrl, NOTES_CHANGES_PATH),
                authorization = token,
                cursor = cursor,
                limit = SYNC_PAGE_LIMIT,
            ).requireData()

            database.withTransaction {
                for (entry in page.changes) {
                    when (entry.type to entry.operation) {
                        "note" to "upsert" -> applyNoteUpsert(
                            scope,
                            entry.note ?: error("note upsert change is missing note"),
                        )
                        "note" to "delete" -> applyNoteDelete(
                            scope,
                            entry.noteId ?: error("note delete change is missing noteId"),
                            entry.revision ?: error("note delete change is missing revision"),
                        )
                        "tag" to "upsert" -> applyTagUpsert(
                            scope,
                            entry.tag ?: error("tag upsert change is missing tag"),
                        )
                        "tag" to "delete" -> {
                            checkNotNull(entry.revision) { "tag delete change is missing revision" }
                            applyTagDelete(
                                scope,
                                entry.tagId ?: error("tag delete change is missing tagId"),
                            )
                        }
                        else -> error("unknown notes change entry")
                    }
                }
                noteSyncKvDao.write(scope, NoteSyncKvEntity(key = CURSOR_KEY, value = page.nextCursor))
            }

            cursor = page.nextCursor

            if (!page.hasMore) {
                break
            }
        }
    }

    private suspend fun applyNoteUpsert(scope: String, dto: NoteDto) {
        persistCloudAttachments(scope, dto.attachments)
        val existing = noteDao.find(scope, dto.noteId)

        if (existing == null) {
            noteDao.upsert(scope, cloudToEntity(dto, System.currentTimeMillis()))
            pulledNotesTotal++
            return
        }

        when (existing.syncState) {
            NoteSyncState.PendingDelete.name -> {
                if (dto.revision != existing.baseRevision) {
                    noteDao.upsert(
                        scope,
                        existing.copy(
                            syncState = NoteSyncState.ConflictDelete.name,
                            conflictKind = CONFLICT_KIND_DELETE,
                            conflictTitle = dto.title,
                            conflictMarkdown = dto.markdown,
                            conflictTagIds = encodeIds(normalizeIds(dto.tagIds)),
                            conflictAttachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
                            conflictRevision = dto.revision,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    conflictsTotal++
                }
            }
            NoteSyncState.Synced.name -> {
                if (existing.revision >= dto.revision) {
                    return
                }
                noteDao.upsert(scope, cloudToEntity(dto, existing.createdAt))
                pulledNotesTotal++
            }
            else -> {
                // Local pending or conflicted edits: attempt a three-way merge.
                if (dto.revision <= existing.baseRevision) {
                    return
                }
                val merge = NoteMerger.mergeNote(
                    ancestorTitle = existing.ancestorTitle,
                    ancestorMarkdown = existing.ancestorMarkdown,
                    ancestorTagIds = decodeIds(existing.ancestorTagIds),
                    ancestorAttachmentIds = decodeIds(existing.ancestorAttachmentIds),
                    localTitle = existing.title,
                    localMarkdown = existing.markdown,
                    localTagIds = decodeIds(existing.tagIds),
                    localAttachmentIds = decodeIds(existing.attachmentIds),
                    cloudTitle = dto.title,
                    cloudMarkdown = dto.markdown,
                    cloudTagIds = normalizeIds(dto.tagIds),
                    cloudAttachmentIds = dto.attachments.map { it.attachmentId },
                )
                val mergedMarkdown = merge.markdown.resolved
                if (mergedMarkdown != null && merge.title.isResolved) {
                    noteDao.upsert(
                        scope,
                        existing.copy(
                            title = merge.title.resolved ?: existing.title,
                            markdown = mergedMarkdown,
                            tagIds = encodeIds(merge.mergedTagIds),
                            attachmentIds = encodeIds(merge.mergedAttachmentIds),
                            baseRevision = dto.revision,
                            ancestorTitle = dto.title,
                            ancestorMarkdown = dto.markdown,
                            ancestorTagIds = encodeIds(normalizeIds(dto.tagIds)),
                            ancestorAttachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
                            ancestorRevision = dto.revision,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    pulledNotesTotal++
                } else {
                    enterConflict(scope, existing, dto, CONFLICT_KIND_EDIT)
                    conflictsTotal++
                }
            }
        }
    }

    private suspend fun applyNoteDelete(scope: String, noteId: String, revision: Long) {
        val existing = noteDao.find(scope, noteId) ?: return
        when (existing.syncState) {
            NoteSyncState.Pending.name, NoteSyncState.Conflict.name -> {
                if (revision > existing.baseRevision) {
                    noteDao.upsert(
                        scope,
                        existing.copy(
                            syncState = NoteSyncState.Conflict.name,
                            conflictKind = CONFLICT_KIND_CLOUD_DELETED,
                            conflictRevision = revision,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    conflictsTotal++
                }
            }
            NoteSyncState.PendingDelete.name -> noteDao.delete(scope, noteId)
            else -> {
                noteDao.delete(scope, noteId)
                pulledNotesTotal++
            }
        }
    }

    private suspend fun applyTagUpsert(scope: String, dto: com.colink.android.data.remote.dto.TagDto) {
        val existing = noteTagDao.find(scope, dto.tagId)
        if (existing == null) {
            noteTagDao.upsert(
                scope,
                    NoteTagEntity(
                    tagId = dto.tagId,
                    name = dto.name,
                    nameNormalized = normalizeTagName(dto.name.trim()),
                    revision = dto.revision,
                    baseRevision = dto.revision,
                    syncState = NoteSyncState.Synced.name,
                    deleted = false,
                    createdAt = parseTimestampMillis(dto.createdAt, System.currentTimeMillis()),
                    updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
                ),
            )
            pulledTagsTotal++
            return
        }

        if (existing.syncState == NoteSyncState.Synced.name) {
            if (existing.revision >= dto.revision) {
                return
            }
            noteTagDao.upsert(
                scope,
                existing.copy(
                    name = dto.name,
                    nameNormalized = normalizeTagName(dto.name.trim()),
                    revision = dto.revision,
                    baseRevision = dto.revision,
                    updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
                ),
            )
            pulledTagsTotal++
        }
    }

    private suspend fun applyTagDelete(scope: String, tagId: String) {
        val existing = noteTagDao.find(scope, tagId)
        if (existing != null &&
            (existing.syncState == NoteSyncState.PendingDelete.name || existing.syncState == NoteSyncState.Synced.name)
        ) {
            noteTagDao.delete(scope, tagId)
        }
        removeTagFromNotes(scope, tagId, onlySynced = true)
        pulledTagsTotal++
    }

    private suspend fun reconcileSnapshot(scope: String, seenNotes: Set<String>, seenTags: Set<String>) {
        for (row in noteDao.listAllRows(scope)) {
            if (row.deleted) {
                continue
            }
            val isPending = row.syncState in LOCAL_PENDING_STATES
            if (!isPending && !seenNotes.contains(row.noteId)) {
                noteDao.delete(scope, row.noteId)
            }
        }
        for (tag in noteTagDao.listAllRows(scope)) {
            if (tag.deleted) {
                continue
            }
            val isPending = tag.syncState in LOCAL_PENDING_STATES
            if (!isPending && !seenTags.contains(tag.tagId)) {
                noteTagDao.delete(scope, tag.tagId)
            }
        }
    }

    // ------------------------------------------------------------------
    // Conflict resolution
    // ------------------------------------------------------------------

    override suspend fun resolveConflict(
        noteId: String,
        resolution: String,
        mergedTitle: String?,
        mergedMarkdown: String?,
    ): Result<Note> =
        runCatching {
            val scope = currentScope()
            val record = noteDao.find(scope, noteId) ?: error("note not found")
            require(
                record.syncState == NoteSyncState.Conflict.name ||
                    record.syncState == NoteSyncState.ConflictDelete.name,
            ) { "note has no conflict" }

            val cloudDeleted = record.conflictKind == CONFLICT_KIND_CLOUD_DELETED
            val now = System.currentTimeMillis()

            when (resolution) {
                "local" -> {
                    if (cloudDeleted) {
                        noteDao.delete(scope, noteId)
                        val fresh = NoteEntity.newPending(
                            noteId = UUID.randomUUID().toString(),
                            title = record.title,
                            markdown = record.markdown,
                            tagIds = decodeIds(record.tagIds),
                            attachmentIds = decodeIds(record.attachmentIds),
                            now = now,
                        )
                        noteDao.upsert(scope, fresh)
                        return@runCatching fresh.toDomain()
                    } else {
                        noteDao.upsert(
                            scope,
                            record.copy(
                                baseRevision = record.conflictRevision ?: record.revision,
                                syncState = NoteSyncState.Pending.name,
                                conflictKind = null,
                                conflictTitle = null,
                                conflictMarkdown = null,
                                conflictTagIds = null,
                                conflictAttachmentIds = null,
                                conflictRevision = null,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                "cloud" -> {
                    if (cloudDeleted) {
                        noteDao.delete(scope, noteId)
                        return@runCatching record.toDomain()
                    } else {
                        noteDao.upsert(
                            scope,
                            record.copy(
                                title = record.conflictTitle ?: record.title,
                                markdown = record.conflictMarkdown ?: record.markdown,
                                tagIds = record.conflictTagIds ?: record.tagIds,
                                attachmentIds = record.conflictAttachmentIds ?: record.attachmentIds,
                                revision = record.conflictRevision ?: record.revision,
                                baseRevision = record.conflictRevision ?: record.revision,
                                ancestorTitle = record.conflictTitle ?: record.ancestorTitle,
                                ancestorMarkdown = record.conflictMarkdown ?: record.ancestorMarkdown,
                                ancestorTagIds = record.conflictTagIds ?: record.ancestorTagIds,
                                ancestorAttachmentIds = record.conflictAttachmentIds ?: record.ancestorAttachmentIds,
                                ancestorRevision = record.conflictRevision ?: record.ancestorRevision,
                                syncState = NoteSyncState.Synced.name,
                                conflictKind = null,
                                conflictTitle = null,
                                conflictMarkdown = null,
                                conflictTagIds = null,
                                conflictAttachmentIds = null,
                                conflictRevision = null,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                "merged" -> {
                    val mergedTagIds = NoteMerger.mergeSet(
                        decodeIds(record.ancestorTagIds),
                        decodeIds(record.tagIds),
                        record.conflictTagIds?.let(::decodeIds).orEmpty(),
                    )
                    val mergedAttachmentIds = NoteMerger.mergeSet(
                        decodeIds(record.ancestorAttachmentIds),
                        decodeIds(record.attachmentIds),
                        record.conflictAttachmentIds?.let(::decodeIds).orEmpty(),
                    )
                    if (cloudDeleted) {
                        noteDao.delete(scope, noteId)
                        val fresh = NoteEntity.newPending(
                            noteId = UUID.randomUUID().toString(),
                            title = mergedTitle ?: record.title,
                            markdown = mergedMarkdown ?: record.markdown,
                            tagIds = mergedTagIds,
                            attachmentIds = mergedAttachmentIds,
                            now = now,
                        )
                        noteDao.upsert(scope, fresh)
                        return@runCatching fresh.toDomain()
                    } else {
                        noteDao.upsert(
                            scope,
                            record.copy(
                                title = mergedTitle ?: record.title,
                                markdown = mergedMarkdown ?: record.markdown,
                                tagIds = encodeIds(mergedTagIds),
                                attachmentIds = encodeIds(mergedAttachmentIds),
                                baseRevision = record.conflictRevision ?: record.revision,
                                syncState = NoteSyncState.Pending.name,
                                conflictKind = null,
                                conflictTitle = null,
                                conflictMarkdown = null,
                                conflictTagIds = null,
                                conflictAttachmentIds = null,
                                conflictRevision = null,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                "confirm_delete" -> {
                    noteDao.upsert(
                        scope,
                        record.copy(
                            baseRevision = record.conflictRevision ?: record.revision,
                            syncState = NoteSyncState.PendingDelete.name,
                            conflictKind = null,
                            conflictTitle = null,
                            conflictMarkdown = null,
                            conflictTagIds = null,
                            conflictAttachmentIds = null,
                            conflictRevision = null,
                            updatedAt = now,
                        ),
                    )
                }
                "cancel_delete" -> {
                    noteDao.upsert(
                        scope,
                        record.copy(
                            title = record.conflictTitle ?: record.title,
                            markdown = record.conflictMarkdown ?: record.markdown,
                            tagIds = record.conflictTagIds ?: record.tagIds,
                            attachmentIds = record.conflictAttachmentIds ?: record.attachmentIds,
                            revision = record.conflictRevision ?: record.revision,
                            baseRevision = record.conflictRevision ?: record.revision,
                            ancestorTitle = record.conflictTitle ?: record.ancestorTitle,
                            ancestorMarkdown = record.conflictMarkdown ?: record.ancestorMarkdown,
                            ancestorTagIds = record.conflictTagIds ?: record.ancestorTagIds,
                            ancestorAttachmentIds = record.conflictAttachmentIds ?: record.ancestorAttachmentIds,
                            ancestorRevision = record.conflictRevision ?: record.ancestorRevision,
                            syncState = NoteSyncState.Synced.name,
                            conflictKind = null,
                            conflictTitle = null,
                            conflictMarkdown = null,
                            conflictTagIds = null,
                            conflictAttachmentIds = null,
                            conflictRevision = null,
                            updatedAt = now,
                        ),
                    )
                }
                else -> error("unknown resolution: $resolution")
            }

            noteDao.find(scope, noteId)?.toDomain() ?: error("note disappeared")
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun markNoteSynced(scope: String, record: NoteEntity, dto: NoteDto) {
        persistCloudAttachments(scope, dto.attachments)
        noteDao.upsert(
            scope,
            record.copy(
                title = dto.title,
                markdown = dto.markdown,
                tagIds = encodeIds(normalizeIds(dto.tagIds)),
                attachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
                revision = dto.revision,
                baseRevision = dto.revision,
                ancestorTitle = dto.title,
                ancestorMarkdown = dto.markdown,
                ancestorTagIds = encodeIds(normalizeIds(dto.tagIds)),
                ancestorAttachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
                ancestorRevision = dto.revision,
                syncState = NoteSyncState.Synced.name,
                conflictKind = null,
                conflictTitle = null,
                conflictMarkdown = null,
                conflictTagIds = null,
                conflictAttachmentIds = null,
                conflictRevision = null,
                createdAt = parseTimestampMillis(dto.createdAt, record.createdAt),
                updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
            ),
        )
    }

    private suspend fun enterConflict(scope: String, record: NoteEntity, cloud: NoteDto, kind: String) {
        persistCloudAttachments(scope, cloud.attachments)
        noteDao.upsert(
            scope,
            record.copy(
                syncState = NoteSyncState.Conflict.name,
                conflictKind = kind,
                conflictTitle = cloud.title,
                conflictMarkdown = cloud.markdown,
                conflictTagIds = encodeIds(normalizeIds(cloud.tagIds)),
                conflictAttachmentIds = encodeIds(cloud.attachments.map { it.attachmentId }),
                conflictRevision = cloud.revision,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun cloudContentMatches(record: NoteEntity, cloud: NoteDto): Boolean =
        record.title == cloud.title &&
            record.markdown == cloud.markdown &&
            decodeIds(record.tagIds) == normalizeIds(cloud.tagIds) &&
            decodeIds(record.attachmentIds) == cloud.attachments.map { it.attachmentId }.sorted()

    private fun cloudToEntity(dto: NoteDto, createdAt: Long): NoteEntity =
        NoteEntity(
            noteId = dto.noteId,
            title = dto.title,
            markdown = dto.markdown,
            tagIds = encodeIds(normalizeIds(dto.tagIds)),
            attachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
            revision = dto.revision,
            baseRevision = dto.revision,
            ancestorRevision = dto.revision,
            syncState = NoteSyncState.Synced.name,
            conflictKind = null,
            conflictTitle = null,
            conflictMarkdown = null,
            conflictTagIds = null,
            conflictAttachmentIds = null,
            conflictRevision = null,
            ancestorTitle = dto.title,
            ancestorMarkdown = dto.markdown,
            ancestorTagIds = encodeIds(normalizeIds(dto.tagIds)),
            ancestorAttachmentIds = encodeIds(dto.attachments.map { it.attachmentId }),
            deleted = false,
            createdAt = parseTimestampMillis(dto.createdAt, createdAt),
            updatedAt = parseTimestampMillis(dto.updatedAt, System.currentTimeMillis()),
        )

    private suspend fun persistCloudAttachments(scope: String, attachments: List<AttachmentDto>) {
        for (dto in attachments) {
            val existing = noteAttachmentDao.find(scope, dto.attachmentId)
            val matches = existing == null || (
                existing.kind == dto.kind &&
                    existing.size == dto.size &&
                    existing.sha256 == dto.sha256
                )
            val record = if (matches) {
                NoteAttachmentEntity(
                    attachmentId = dto.attachmentId,
                    kind = dto.kind,
                    fileName = dto.fileName,
                    mediaType = dto.mediaType,
                    size = dto.size,
                    sha256 = dto.sha256,
                    syncState = NoteSyncState.Synced.name,
                    deleted = false,
                    createdAt = parseTimestampMillis(dto.createdAt, existing?.createdAt ?: System.currentTimeMillis()),
                )
            } else {
                existing!!.copy(syncState = NoteSyncState.Conflict.name)
            }
            noteAttachmentDao.upsert(scope, record)
        }
    }

    private suspend fun pushSingleAttachment(
        scope: String,
        serverUrl: String,
        token: String,
        record: NoteAttachmentEntity,
    ) {
        val file = attachmentCacheFile(scope, record.attachmentId)
        val dto = notesApi.uploadAttachment(
            url = apiEndpoint(serverUrl, NOTE_ATTACHMENTS_PATH),
            authorization = token,
            attachmentId = textPart("attachmentId", record.attachmentId),
            kind = textPart("kind", record.kind),
            sha256 = textPart("sha256", record.sha256),
            file = MultipartBody.Part.createFormData(
                "file",
                record.fileName,
                file.asRequestBody(record.mediaType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()),
            ),
        ).requireData()
        noteAttachmentDao.upsert(
            scope,
            record.copy(syncState = NoteSyncState.Synced.name, mediaType = dto.mediaType),
        )
        pushedAttachmentsTotal++
    }

    private suspend fun rekeyAttachment(scope: String, record: NoteAttachmentEntity): NoteAttachmentEntity {
        val replacementId = UUID.randomUUID().toString()
        val source = attachmentCacheFile(scope, record.attachmentId)
        val target = attachmentCacheFile(scope, replacementId)
        source.copyTo(target, overwrite = false)
        val replacement = record.copy(
            attachmentId = replacementId,
            syncState = NoteSyncState.Pending.name,
            deleted = false,
        )
        try {
            database.withTransaction {
                noteAttachmentDao.upsert(scope, replacement)
                replaceCurrentAttachmentReferences(scope, record.attachmentId, replacementId)
                noteAttachmentDao.delete(scope, record.attachmentId)
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        source.delete()
        return replacement
    }

    private suspend fun replaceCurrentAttachmentReferences(scope: String, from: String, to: String) {
        val fromUri = "colink-attachment://$from"
        val toUri = "colink-attachment://$to"
        for (note in noteDao.listAllRows(scope)) {
            val ids = decodeIds(note.attachmentIds)
            if (from in ids || fromUri in note.markdown) {
                noteDao.upsert(
                    scope,
                    note.copy(
                        markdown = note.markdown.replace(fromUri, toUri),
                        attachmentIds = encodeIds(normalizeIds(ids.map { if (it == from) to else it })),
                        syncState = if (note.syncState == NoteSyncState.Synced.name) {
                            NoteSyncState.Pending.name
                        } else {
                            note.syncState
                        },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun removeCurrentAttachmentReferences(scope: String, attachmentId: String) {
        val uriPattern = Regex("!?\\[[^]]*]\\(colink-attachment://${Regex.escape(attachmentId)}\\)")
        for (note in noteDao.listAllRows(scope)) {
            val ids = decodeIds(note.attachmentIds)
            if (attachmentId in ids || uriPattern.containsMatchIn(note.markdown)) {
                noteDao.upsert(
                    scope,
                    note.copy(
                        markdown = note.markdown.replace(uriPattern, "").replace(Regex("\\n{3,}"), "\n\n"),
                        attachmentIds = encodeIds(ids.filter { it != attachmentId }),
                        syncState = if (note.syncState == NoteSyncState.Synced.name) {
                            NoteSyncState.Pending.name
                        } else {
                            note.syncState
                        },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun parseTimestampMillis(raw: String, fallback: Long): Long =
        runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(fallback)

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun retargetTagReferences(scope: String, from: String, to: String) {
        for (row in noteDao.listAllRows(scope)) {
            val tagIds = decodeIds(row.tagIds)
            val ancestorTagIds = decodeIds(row.ancestorTagIds)
            val changed = tagIds.contains(from) || ancestorTagIds.contains(from)
            if (changed) {
                noteDao.upsert(
                    scope,
                    row.copy(
                        tagIds = encodeIds(tagIds.map { if (it == from) to else it }),
                        ancestorTagIds = encodeIds(ancestorTagIds.map { if (it == from) to else it }),
                    ),
                )
            }
        }
    }

    private suspend fun removeTagFromNotes(scope: String, tagId: String, onlySynced: Boolean) {
        for (row in noteDao.listAllRows(scope)) {
            if (onlySynced && row.syncState != NoteSyncState.Synced.name) {
                continue
            }
            val tagIds = decodeIds(row.tagIds)
            val ancestorTagIds = decodeIds(row.ancestorTagIds)
            if (tagIds.contains(tagId) || ancestorTagIds.contains(tagId)) {
                noteDao.upsert(
                    scope,
                    row.copy(
                        tagIds = encodeIds(tagIds.filter { it != tagId }),
                        ancestorTagIds = encodeIds(ancestorTagIds.filter { it != tagId }),
                    ),
                )
            }
        }
    }

    private fun normalizeIds(ids: List<String>): List<String> = ids.distinct().sorted()

    private fun normalizeTagName(name: String): String {
        val nfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
        val folded = android.icu.text.CaseMap.fold().apply(nfc)
        return java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFC)
    }

    private fun NoteAttachmentEntity.toDomain(): NoteAttachment =
        NoteAttachment(
            id = attachmentId,
            kind = kind,
            fileName = fileName,
            mediaType = mediaType,
            size = size,
            sha256 = sha256,
            syncState = syncState,
        )

    private fun bearer(token: String): String = "Bearer $token"

    private fun textPart(name: String, value: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, null, value.toRequestBody("text/plain".toMediaTypeOrNull()))

    private companion object {
        const val CONFLICT_KIND_EDIT = "edit"
        const val CONFLICT_KIND_CLOUD_DELETED = "cloudDeleted"
        const val CONFLICT_KIND_DELETE = "delete"
        const val CODE_SYNC_CURSOR_EXPIRED = 6009

        val LOCAL_PENDING_STATES = setOf(
            NoteSyncState.Pending.name,
            NoteSyncState.PendingDelete.name,
            NoteSyncState.Conflict.name,
            NoteSyncState.ConflictDelete.name,
        )

    }
}
