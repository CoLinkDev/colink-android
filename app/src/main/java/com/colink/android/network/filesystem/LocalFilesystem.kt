package com.colink.android.network.filesystem

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.colink.android.R
import com.colink.android.network.message.FsEntry
import com.colink.android.network.message.FsListPayload
import com.colink.android.network.message.FsListResultPayload
import com.colink.android.network.message.FsRootEntry
import com.colink.android.network.message.FsRootsResultPayload
import com.colink.android.network.message.FsStatPayload
import com.colink.android.network.message.FsStatResultPayload
import com.colink.android.network.message.FsDownloadPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_LIST_LIMIT = 200L
private const val MAX_LIST_LIMIT = 1_000L

class LocalFilesystemException(
    val reason: String,
    message: String,
) : IllegalStateException(message)

@Singleton
class LocalFilesystem @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun roots(): FsRootsResultPayload {
        val roots = linkedMapOf<String, FsRootEntry>()

        addRoot(roots, context.filesDir, context.getString(R.string.remote_files_root_app_storage))
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .forEachIndexed { index, file ->
                addRoot(
                    roots,
                    file,
                    if (index == 0) {
                        context.getString(R.string.remote_files_root_app_files)
                    } else {
                        context.getString(R.string.remote_files_root_app_files_extra, index + 1)
                    },
                )
            }

        val sharedStorage = Environment.getExternalStorageDirectory()
        if (sharedStorage.isDirectory && sharedStorage.canRead()) {
            addRoot(roots, sharedStorage, context.getString(R.string.remote_files_root_shared_storage))
        }

        return FsRootsResultPayload(roots = roots.values.toList())
    }

    fun list(request: FsListPayload): FsListResultPayload {
        val directory = absoluteFile(request.path)
        val attributes = attributes(directory)
        if (!attributes.isDirectory) {
            throw LocalFilesystemException("not_directory", "Path is not a directory")
        }
        if (!directory.canRead()) {
            throw LocalFilesystemException("permission_denied", "Directory cannot be read")
        }

        val entries = directory.listFiles()
            ?.mapNotNull { file -> runCatching { entry(file) }.getOrNull() }
            ?.sortedWith(
                compareBy<FsEntry> { it.kind != "directory" }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name },
            )
            ?: throw LocalFilesystemException("permission_denied", "Directory cannot be read")
        val total = entries.size.toLong()
        val offset = request.offset.orDefault(0L).coerceIn(0L, total)
        val limit = request.limit.orDefault(DEFAULT_LIST_LIMIT).coerceIn(1L, MAX_LIST_LIMIT)
        val page = entries.drop(offset.toInt()).take(limit.toInt())

        return FsListResultPayload(
            path = request.path,
            entries = page,
            total = total,
            offset = offset,
            hasMore = offset + page.size < total,
        )
    }

    fun stat(request: FsStatPayload): FsStatResultPayload {
        val file = absoluteFile(request.path)
        val attributes = try {
            attributes(file)
        } catch (_: NoSuchFileException) {
            return FsStatResultPayload(path = request.path, exists = false)
        }
        val item = entry(file, attributes)
        return FsStatResultPayload(
            path = request.path,
            exists = true,
            kind = item.kind,
            size = item.size,
            modified = item.modified,
            created = item.created,
            readonly = item.readonly,
            hidden = item.hidden,
        )
    }

    fun download(request: FsDownloadPayload): File {
        val file = absoluteFile(request.path)
        val attributes = attributes(file)
        if (!attributes.isRegularFile) {
            throw LocalFilesystemException("not_file", "Path is not a regular file")
        }
        if (!file.canRead()) {
            throw LocalFilesystemException("permission_denied", "File cannot be read")
        }
        return file
    }

    private fun addRoot(
        roots: MutableMap<String, FsRootEntry>,
        file: File,
        label: String,
    ) {
        if (!file.isDirectory || !file.canRead()) {
            return
        }
        val path = file.absolutePath
        val stat = runCatching { StatFs(path) }.getOrNull()
        roots.putIfAbsent(
            path,
            FsRootEntry(
                path = path,
                label = label,
                totalBytes = stat?.totalBytes,
                freeBytes = stat?.availableBytes,
            ),
        )
    }

    private fun absoluteFile(path: String): File {
        val file = File(path)
        if (!file.isAbsolute) {
            throw LocalFilesystemException("generic", "Path must be absolute")
        }
        return file
    }

    private fun entry(file: File): FsEntry = entry(file, attributes(file))

    private fun entry(file: File, attributes: BasicFileAttributes): FsEntry {
        val kind = when {
            attributes.isSymbolicLink -> "symlink"
            attributes.isDirectory -> "directory"
            attributes.isRegularFile -> "file"
            else -> "other"
        }
        return FsEntry(
            name = file.name,
            kind = kind,
            size = attributes.isRegularFile.then { attributes.size() },
            modified = attributes.lastModifiedTime().toMillis().takeIf { it > 0L },
            created = attributes.creationTime().toMillis().takeIf { it > 0L },
            readonly = !file.canWrite(),
            hidden = runCatching { file.isHidden }.getOrDefault(false),
        )
    }

    private fun attributes(file: File): BasicFileAttributes =
        try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: NoSuchFileException) {
            throw error
        } catch (error: AccessDeniedException) {
            throw LocalFilesystemException("permission_denied", error.message ?: "Path cannot be read")
        } catch (error: SecurityException) {
            throw LocalFilesystemException("permission_denied", error.message ?: "Path cannot be read")
        } catch (error: Exception) {
            throw LocalFilesystemException("io_error", error.message ?: "Filesystem operation failed")
        }
}

private fun Long?.orDefault(fallback: Long): Long = this ?: fallback

private inline fun <T> Boolean.then(value: () -> T): T? = if (this) value() else null
