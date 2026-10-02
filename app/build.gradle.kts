import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Properties
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

abstract class DownloadCastBoardTask : DefaultTask() {
    @get:Input
    abstract val castBoardVersion: Property<String>

    @get:OutputDirectory
    abstract val cacheDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val version = castBoardVersion.get()
        val cacheRoot = cacheDirectory.get().asFile.toPath()
        val cachedIndex = cacheRoot.resolve("dist/index.html")
        if (Files.isRegularFile(cachedIndex)) return

        deleteRecursively(cacheRoot)
        Files.createDirectories(cacheRoot.parent)
        val temporaryRoot = Files.createTempDirectory(cacheRoot.parent, ".tmp-$version-")
        val archive = temporaryRoot.resolve("castboard-dist.zip")
        val extracted = temporaryRoot.resolve("extracted")
        val releaseUrl = "https://github.com/CoLinkDev/colink-castboard/releases/download/v$version/castboard-dist.zip"

        try {
            download(releaseUrl, archive)
            extract(archive, extracted)
            if (!Files.isRegularFile(extracted.resolve("dist/index.html"))) {
                throw GradleException("CastBoard $version release does not contain dist/index.html")
            }
            try {
                Files.move(extracted, cacheRoot, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(extracted, cacheRoot)
            }
        } finally {
            deleteRecursively(temporaryRoot)
        }
    }

    private fun download(url: String, destination: java.nio.file.Path) {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "CoLink-Android-Build")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw GradleException("CastBoard download failed with HTTP status $status")
            }
            connection.inputStream.use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: java.nio.file.Path, destination: java.nio.file.Path) {
        Files.createDirectories(destination)
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = destination.resolve(entry.name).normalize()
                if (!target.startsWith(destination)) {
                    throw GradleException("CastBoard release contains an unsafe ZIP entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun deleteRecursively(path: java.nio.file.Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

abstract class ValidateCastBoardTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distDirectory: DirectoryProperty

    @TaskAction
    fun validate() {
        val index = distDirectory.file("index.html").get().asFile
        if (!index.isFile) {
            throw GradleException("Local CastBoard directory does not contain index.html: ${index.parent}")
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val serverBaseUrl = localProperties
    .getProperty("SERVER_BASE_URL", "https://sync.colink.evative7.host")
    .trim()
    .trimEnd('/')

val castBoardDevUrl = localProperties
    .getProperty("CASTBOARD_DEV_URL", "")
    .trim()
val castBoardReleaseVersion = providers.gradleProperty("castboard.version").get().trim()
if (!Regex("^\\d+\\.\\d+\\.\\d+$").matches(castBoardReleaseVersion)) {
    throw GradleException("castboard.version must be a semantic version: $castBoardReleaseVersion")
}
val castBoardLocalPath = providers.gradleProperty("castboard.localPath").orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: localProperties.getProperty("CASTBOARD_LOCAL_PATH", "").trim().takeIf(String::isNotEmpty)
val castBoardCacheDir = rootProject.file(".gradle/castboard-cache/$castBoardReleaseVersion")
val castBoardDistDir = if (castBoardLocalPath == null) {
    castBoardCacheDir.resolve("dist")
} else {
    val localRoot = rootProject.file(castBoardLocalPath)
    when {
        localRoot.resolve("index.html").isFile -> localRoot
        localRoot.resolve("dist/index.html").isFile -> localRoot.resolve("dist")
        else -> throw GradleException(
            "CASTBOARD_LOCAL_PATH must point to a CastBoard dist directory or a project with dist/index.html: $localRoot",
        )
    }
}
val generatedCastBoardAssetsDir = layout.buildDirectory.dir("generated/castboard-assets")
val prepareCastBoard = if (castBoardLocalPath == null) {
    tasks.register<DownloadCastBoardTask>("prepareCastBoard") {
        castBoardVersion.set(castBoardReleaseVersion)
        cacheDirectory.set(castBoardCacheDir)
    }
} else {
    tasks.register<ValidateCastBoardTask>("prepareCastBoard") {
        distDirectory.set(castBoardDistDir)
    }
}

android {
    namespace = "com.colink.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.colink.android"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
        buildConfigField("String", "CASTBOARD_DEV_URL", "\"$castBoardDevUrl\"")
        buildConfigField("String", "CASTBOARD_VERSION", "\"$castBoardReleaseVersion\"")
        manifestPlaceholders["appName"] = "CoLink"
    }

    signingConfigs {
        create("release") {
            val ksFile = file("release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appName"] = "CoLink Debug"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["appName"] = "CoLink"
            signingConfig = if (file("release.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

}

kotlin {
    jvmToolchain(21)
}

val syncReleaseCastBoardAssets by tasks.registering(Sync::class) {
    dependsOn(prepareCastBoard)
    from(castBoardDistDir) {
        into("castboard")
    }
    into(generatedCastBoardAssetsDir.map { it.dir("release") })
}

val syncDebugCastBoardAssets by tasks.registering(Sync::class) {
    into(generatedCastBoardAssetsDir.map { it.dir("debug") })
    if (castBoardDevUrl.isEmpty()) {
        dependsOn(prepareCastBoard)
        from(castBoardDistDir) {
            into("castboard")
        }
    }
}

android.sourceSets.getByName("release").assets.srcDir(syncReleaseCastBoardAssets)
android.sourceSets.getByName("debug").assets.srcDir(syncDebugCastBoardAssets)

tasks.matching { task ->
    task.name == "mergeReleaseAssets" ||
        (task.name.contains("Release") &&
            (task.name.startsWith("lintVital") || task.name.endsWith("LintVitalReportModel")))
}.configureEach {
    dependsOn(syncReleaseCastBoardAssets)
}

tasks.matching { task -> task.name == "mergeDebugAssets" }.configureEach {
    dependsOn(syncDebugCastBoardAssets)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.material.components)
    implementation("io.noties.markwon:core:4.6.2")
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.google.code.scanner)
    implementation(libs.zxing.core)

    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
