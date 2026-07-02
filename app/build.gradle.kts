import java.util.Properties
import org.gradle.api.tasks.Sync

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
val castBoardProjectDir = rootProject.file("../colink-castboard")
val castBoardSourceDir = castBoardProjectDir.resolve("src")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets")

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
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    aaptOptions {
        ignoreAssetsPattern = "!SourceHanSansSC-VF.ttf.woff2:!SourceHanSansSC-VF.ttf"
    }

}

kotlin {
    jvmToolchain(21)
}

val syncCastBoardAssets by tasks.registering(Sync::class) {
    from(castBoardSourceDir) {
        into("castboard")
    }
    into(generatedAssetsDir)
}

android.sourceSets.getByName("main").assets.srcDir(syncCastBoardAssets)

tasks.matching { task ->
    (task.name.startsWith("merge") && task.name.endsWith("Assets")) ||
        task.name.startsWith("lintVital") ||
        task.name.endsWith("LintVitalReportModel")
}.configureEach {
    dependsOn(syncCastBoardAssets)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.material.components)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
