package com.colink.android.ui.castboard

import android.content.Context
import android.net.Uri
import com.colink.android.BuildConfig
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val PLUGINS_DIRECTORY = "castboard-plugins"
private const val PREFERENCES_NAME = "castboard_plugins"
private const val MAX_ARCHIVE_FILES = 512
private const val MAX_ARCHIVE_FILE_SIZE = 16L * 1024 * 1024
private const val MAX_ARCHIVE_TOTAL_SIZE = 64L * 1024 * 1024
private const val MAX_MANIFEST_SIZE = 1024L * 1024

enum class CastBoardPluginFailure {
    InvalidArchive,
    InvalidManifest,
    Incompatible,
    Storage,
    NotFound,
}

class CastBoardPluginException(
    val failure: CastBoardPluginFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

@Serializable
data class CastBoardPluginManifest(
    @SerialName("schemaVersion") val schemaVersion: String,
    val id: String,
    val name: Map<String, String>,
    val description: Map<String, String>? = null,
    val version: String,
    @SerialName("minCastBoardVersion") val minCastBoardVersion: String,
    @SerialName("type") val type: String,
    val entry: String,
)

data class CastBoardPluginItem(
    val manifest: CastBoardPluginManifest,
    val rawManifest: JsonObject,
    val enabled: Boolean,
    val installedAt: Long,
    internal val directoryName: String,
)

@Singleton
class CastBoardPluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val pluginsDirectory: File = File(context.filesDir, PLUGINS_DIRECTORY).apply { mkdirs() }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _plugins = MutableStateFlow(loadPlugins())
    val plugins: StateFlow<List<CastBoardPluginItem>> = _plugins.asStateFlow()

    suspend fun importPlugin(uri: Uri): CastBoardPluginItem = withContext(Dispatchers.IO) {
        mutex.withLock {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive)
            input.use(::importFromStream)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plugin = _plugins.value.firstOrNull { it.manifest.id == id }
                ?: throw CastBoardPluginException(CastBoardPluginFailure.NotFound)
            if (!preferences.edit().putBoolean(enabledKey(plugin.directoryName), enabled).commit()) {
                throw CastBoardPluginException(CastBoardPluginFailure.Storage)
            }
            _plugins.value = _plugins.value.map { item ->
                if (item.manifest.id == id) item.copy(enabled = enabled) else item
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plugin = _plugins.value.firstOrNull { it.manifest.id == id }
                ?: throw CastBoardPluginException(CastBoardPluginFailure.NotFound)
            val target = File(pluginsDirectory, plugin.directoryName)
            val stagedDelete = File(pluginsDirectory, ".delete-${UUID.randomUUID()}")
            if (!target.renameTo(stagedDelete)) {
                throw CastBoardPluginException(CastBoardPluginFailure.Storage)
            }
            val preferencesUpdated = preferences.edit()
                .remove(enabledKey(plugin.directoryName))
                .remove(installedAtKey(plugin.directoryName))
                .commit()
            if (!preferencesUpdated) {
                stagedDelete.renameTo(target)
                throw CastBoardPluginException(CastBoardPluginFailure.Storage)
            }
            stagedDelete.deleteRecursively()
            _plugins.value = _plugins.value.filterNot { it.manifest.id == id }
        }
    }

    fun enabledPlugins(): List<CastBoardPluginItem> = plugins.value.filter(CastBoardPluginItem::enabled)

    private fun importFromStream(input: InputStream): CastBoardPluginItem {
        Files.createDirectories(pluginsDirectory.toPath())
        val stage = Files.createTempDirectory(pluginsDirectory.toPath(), ".import-").toFile()
        try {
            extractCastBoardPluginArchive(input, stage)
            val packageRoot = resolveCastBoardPluginPackageRoot(stage)
            val plugin = readPlugin(packageRoot, directoryName = "")
            val directoryName = pluginDirectoryName(plugin.manifest.id)
            val target = File(pluginsDirectory, directoryName)
            val existing = _plugins.value.firstOrNull { it.manifest.id == plugin.manifest.id }
            val installedAt = existing?.installedAt ?: System.currentTimeMillis()
            val enabled = existing?.enabled ?: true
            replaceInstalledPlugin(
                stage = packageRoot,
                target = target,
                directoryName = directoryName,
                enabled = enabled,
                installedAt = installedAt,
            )
            val installed = plugin.copy(
                enabled = enabled,
                installedAt = installedAt,
                directoryName = directoryName,
            )
            _plugins.value = (_plugins.value.filterNot { it.manifest.id == installed.manifest.id } + installed)
                .sortedWith(compareBy(CastBoardPluginItem::installedAt, { it.manifest.id }))
            return installed
        } catch (error: CastBoardPluginException) {
            throw error
        } catch (error: Exception) {
            throw CastBoardPluginException(CastBoardPluginFailure.Storage, error)
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun replaceInstalledPlugin(
        stage: File,
        target: File,
        directoryName: String,
        enabled: Boolean,
        installedAt: Long,
    ) {
        val backup = File(pluginsDirectory, ".backup-${UUID.randomUUID()}")
        val hadExisting = target.exists()
        if (hadExisting && !target.renameTo(backup)) {
            throw CastBoardPluginException(CastBoardPluginFailure.Storage)
        }
        if (!stage.renameTo(target)) {
            if (hadExisting) backup.renameTo(target)
            throw CastBoardPluginException(CastBoardPluginFailure.Storage)
        }

        val preferencesUpdated = preferences.edit()
            .putBoolean(enabledKey(directoryName), enabled)
            .putLong(installedAtKey(directoryName), installedAt)
            .commit()
        if (!preferencesUpdated) {
            target.deleteRecursively()
            if (hadExisting) backup.renameTo(target)
            throw CastBoardPluginException(CastBoardPluginFailure.Storage)
        }
        if (hadExisting) backup.deleteRecursively()
    }

    private fun loadPlugins(): List<CastBoardPluginItem> {
        if (!pluginsDirectory.isDirectory) return emptyList()
        return pluginsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("plugin-") }
            .mapNotNull { directory ->
                runCatching { readPlugin(directory, directory.name) }
                    .onFailure { error ->
                        CoLinkLog.w("CastBoard", "ignoring invalid plugin directory=${directory.name}", error)
                    }
                    .getOrNull()
            }
            .map { plugin ->
                plugin.copy(
                    enabled = preferences.getBoolean(enabledKey(plugin.directoryName), true),
                    installedAt = preferences.getLong(installedAtKey(plugin.directoryName), plugin.installedAt),
                )
            }
            .sortedWith(compareBy(CastBoardPluginItem::installedAt, { it.manifest.id }))
            .toList()
    }

    private fun readPlugin(directory: File, directoryName: String): CastBoardPluginItem {
        val manifestFile = File(directory, "manifest.json")
        if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_SIZE) {
            throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
        }
        val rawManifest = runCatching {
            pluginJson.parseToJsonElement(manifestFile.readText()).let { it as JsonObject }
        }.getOrElse { throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest, it) }
        val manifest = runCatching {
            pluginJson.decodeFromJsonElement(CastBoardPluginManifest.serializer(), rawManifest)
        }.getOrElse { throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest, it) }
        validateCastBoardPluginManifest(manifest, directory)
        if (directoryName.isNotEmpty() && pluginDirectoryName(manifest.id) != directoryName) {
            throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
        }
        return CastBoardPluginItem(
            manifest = manifest,
            rawManifest = rawManifest,
            enabled = true,
            installedAt = directory.lastModified(),
            directoryName = directoryName,
        )
    }

    private fun enabledKey(directoryName: String) = "$directoryName.enabled"
    private fun installedAtKey(directoryName: String) = "$directoryName.installedAt"
}

internal fun resolveCastBoardPluginPackageRoot(stage: File): File {
    if (File(stage, "manifest.json").isFile) return stage

    val candidates = stage.listFiles()
        ?.filterNot { it.name == "__MACOSX" || it.name == ".DS_Store" }
        ?: throw CastBoardPluginException(CastBoardPluginFailure.Storage)
    if (candidates.size != 1) {
        throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    }
    val packageRoot = candidates.single()
    if (!packageRoot.isDirectory || !File(packageRoot, "manifest.json").isFile) {
        throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    }
    return packageRoot
}

private val pluginJson = Json {
    ignoreUnknownKeys = true
}

internal fun extractCastBoardPluginArchive(input: InputStream, destination: File) {
    val root = destination.toPath().toAbsolutePath().normalize()
    val visited = mutableSetOf<Path>()
    var fileCount = 0
    var totalSize = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

    try {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                fileCount += 1
                if (fileCount > MAX_ARCHIVE_FILES) {
                    throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive)
                }
                val relative = safeCastBoardPackagePath(entry.name)
                    ?: throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive)
                val target = root.resolve(relative).normalize()
                if (!target.startsWith(root) || target == root || !visited.add(target)) {
                    throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive)
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output ->
                        var fileSize = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileSize += count
                            totalSize += count
                            if (fileSize > MAX_ARCHIVE_FILE_SIZE || totalSize > MAX_ARCHIVE_TOTAL_SIZE) {
                                throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive)
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (error: CastBoardPluginException) {
        throw error
    } catch (error: Exception) {
        throw CastBoardPluginException(CastBoardPluginFailure.InvalidArchive, error)
    }
}

internal fun validateCastBoardPluginManifest(manifest: CastBoardPluginManifest, directory: File) {
    if (manifest.schemaVersion != "1.0.0" ||
        manifest.id.isBlank() ||
        !validLocalizedStrings(manifest.name) ||
        manifest.description?.let(::validLocalizedStrings) == false ||
        parseVersion(manifest.version) == null ||
        manifest.type !in setOf("navigable", "transient")
    ) {
        throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    }
    val minimumVersion = parseVersion(manifest.minCastBoardVersion)
        ?: throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    val currentVersion = checkNotNull(parseVersion(BuildConfig.CASTBOARD_VERSION))
    if (minimumVersion > currentVersion) {
        throw CastBoardPluginException(CastBoardPluginFailure.Incompatible)
    }

    val entry = safeCastBoardPackagePath(manifest.entry)
        ?: throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    val root = directory.toPath().toAbsolutePath().normalize()
    val entryPath = root.resolve(entry).normalize()
    if (!entryPath.startsWith(root) || !Files.isRegularFile(entryPath)) {
        throw CastBoardPluginException(CastBoardPluginFailure.InvalidManifest)
    }
}

private fun validLocalizedStrings(strings: Map<String, String>): Boolean =
    strings.isNotEmpty() && strings.all { (language, value) -> language.isNotBlank() && value.isNotBlank() }

private data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
}

private fun parseVersion(version: String): ReleaseVersion? {
    val parts = version.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || it.any { character -> character !in '0'..'9' } }) return null
    return ReleaseVersion(
        major = parts[0].toLongOrNull() ?: return null,
        minor = parts[1].toLongOrNull() ?: return null,
        patch = parts[2].toLongOrNull() ?: return null,
    )
}

internal fun safeCastBoardPackagePath(path: String): Path? {
    if (path.isBlank() ||
        path.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(path) ||
        path.any { it == ':' || it == '?' || it == '#' } ||
        path.contains('\\') ||
        path.contains('\u0000')
    ) return null
    val parsed = runCatching { Paths.get(path) }.getOrNull() ?: return null
    if (parsed.isAbsolute || parsed.any { it.toString() == ".." }) return null
    return parsed.normalize()
}

private fun pluginDirectoryName(id: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    return buildString(71) {
        append("plugin-")
        digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }
}
