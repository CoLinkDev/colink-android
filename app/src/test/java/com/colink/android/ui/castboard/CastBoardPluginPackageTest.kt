package com.colink.android.ui.castboard

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastBoardPluginPackageTest {
    @Test
    fun extractsPackageFilesInsideDestination() {
        val destination = Files.createTempDirectory("castboard-plugin-test").toFile()
        try {
            extractCastBoardPluginArchive(
                ByteArrayInputStream(zipOf("manifest.json" to "{}", "assets/icon.svg" to "<svg/>")),
                destination,
            )

            assertEquals("{}", destination.resolve("manifest.json").readText())
            assertEquals("<svg/>", destination.resolve("assets/icon.svg").readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun resolvesRootAndSingleWrapperPackages() {
        val rootPackage = Files.createTempDirectory("castboard-plugin-test").toFile()
        val wrappedPackage = Files.createTempDirectory("castboard-plugin-test").toFile()
        try {
            rootPackage.resolve("manifest.json").writeText("{}")
            assertEquals(rootPackage, resolveCastBoardPluginPackageRoot(rootPackage))

            wrappedPackage.resolve(".DS_Store").writeText("metadata")
            wrappedPackage.resolve("__MACOSX").mkdir()
            val wrapper = wrappedPackage.resolve("plugin").apply { mkdir() }
            wrapper.resolve("manifest.json").writeText("{}")
            assertEquals(wrapper, resolveCastBoardPluginPackageRoot(wrappedPackage))
        } finally {
            rootPackage.deleteRecursively()
            wrappedPackage.deleteRecursively()
        }
    }

    @Test
    fun rejectsAmbiguousOrDeeplyNestedPackageRoots() {
        val ambiguous = Files.createTempDirectory("castboard-plugin-test").toFile()
        val deeplyNested = Files.createTempDirectory("castboard-plugin-test").toFile()
        try {
            ambiguous.resolve("first").apply { mkdir() }.resolve("manifest.json").writeText("{}")
            ambiguous.resolve("second").apply { mkdir() }.resolve("manifest.json").writeText("{}")
            val ambiguousError = runCatching { resolveCastBoardPluginPackageRoot(ambiguous) }.exceptionOrNull()
            assertTrue(ambiguousError is CastBoardPluginException)
            assertEquals(
                CastBoardPluginFailure.InvalidManifest,
                (ambiguousError as CastBoardPluginException).failure,
            )

            deeplyNested.resolve("outer").resolve("inner").apply { mkdirs() }
                .resolve("manifest.json").writeText("{}")
            val nestedError = runCatching { resolveCastBoardPluginPackageRoot(deeplyNested) }.exceptionOrNull()
            assertTrue(nestedError is CastBoardPluginException)
            assertEquals(
                CastBoardPluginFailure.InvalidManifest,
                (nestedError as CastBoardPluginException).failure,
            )
        } finally {
            ambiguous.deleteRecursively()
            deeplyNested.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipEntries() {
        val destination = Files.createTempDirectory("castboard-plugin-test").toFile()
        try {
            val result = runCatching {
                extractCastBoardPluginArchive(
                    ByteArrayInputStream(zipOf("../outside.js" to "unsafe")),
                    destination,
                )
            }

            assertTrue(result.exceptionOrNull() is CastBoardPluginException)
            assertEquals(
                CastBoardPluginFailure.InvalidArchive,
                (result.exceptionOrNull() as CastBoardPluginException).failure,
            )
            assertFalse(checkNotNull(destination.parentFile).resolve("outside.js").exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun acceptsOnlyPackageRelativeEntryPaths() {
        assertTrue(safeCastBoardPackagePath("assets/index.js") != null)
        assertTrue(safeCastBoardPackagePath("../index.js") == null)
        assertTrue(safeCastBoardPackagePath("assets\\index.js") == null)
        assertTrue(safeCastBoardPackagePath("/index.js") == null)
        assertTrue(safeCastBoardPackagePath("C:/index.js") == null)
    }

    @Test
    fun validatesManifestCompatibilityAndEntryFile() {
        val destination = Files.createTempDirectory("castboard-plugin-test").toFile()
        try {
            destination.resolve("index.js").writeText("export default { mount() {} }")
            val manifest = CastBoardPluginManifest(
                schemaVersion = "1.0.0",
                id = "com.example.test",
                name = mapOf("en" to "Test"),
                version = "1.0.0",
                minCastBoardVersion = "2.2.0",
                type = "navigable",
                entry = "index.js",
            )
            validateCastBoardPluginManifest(manifest, destination)

            val incompatible = runCatching {
                validateCastBoardPluginManifest(
                    manifest.copy(minCastBoardVersion = "999.0.0"),
                    destination,
                )
            }.exceptionOrNull()
            assertTrue(incompatible is CastBoardPluginException)
            assertEquals(CastBoardPluginFailure.Incompatible, (incompatible as CastBoardPluginException).failure)
        } finally {
            destination.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
