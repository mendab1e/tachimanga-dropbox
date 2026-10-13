plugins {
    id("com.android.application")
    kotlin("android")
}
// This identifies the OAuth client and is deliberately public in the APK.
val bundledDropboxAppKey = (providers.gradleProperty("dropboxAppKey").orNull
    ?: providers.environmentVariable("DROPBOX_APP_KEY").orNull?.takeIf { it.isNotBlank() }
    ?: file("dropbox-app-key.txt").takeIf { it.exists() }?.readText().orEmpty()).trim()
require(bundledDropboxAppKey.isEmpty() || bundledDropboxAppKey.matches(Regex("[A-Za-z0-9]+"))) {
    "dropboxAppKey / DROPBOX_APP_KEY must be a public Dropbox app key containing only letters and digits"
}
android {
    namespace = "eu.kanade.tachiyomi.extension.all.dropbox"
    compileSdk = 34
    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.dropbox"
        minSdk = 21
        targetSdk = 34
        versionCode = 10
        versionName = "1.4.10"
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$bundledDropboxAppKey\"")
        manifestPlaceholders["appName"] = "Tachiyomi: Dropbox"
    }
    signingConfigs {
        getByName("debug") {
            val localKey = rootProject.file(".tooling/debug.keystore")
            if (localKey.exists()) storeFile = localKey
        }
    }
    val distributionKey = System.getenv("EXTENSION_KEYSTORE")
    if (!distributionKey.isNullOrBlank()) {
        signingConfigs.create("distribution") {
            storeFile = file(distributionKey)
            storePassword = System.getenv("EXTENSION_STORE_PASSWORD")
            keyAlias = System.getenv("EXTENSION_KEY_ALIAS")
            keyPassword = System.getenv("EXTENSION_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal install builds. Use a persistent private key before distributing updates.
            signingConfig = signingConfigs.getByName(if (distributionKey.isNullOrBlank()) "debug" else "distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { buildConfig = true }
    dependenciesInfo { includeInApk = false }
}
dependencies {
    compileOnly(project(":host-api"))
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.7.22")
    compileOnly("com.squareup.okhttp3:okhttp:4.10.0")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    compileOnly("androidx.preference:preference:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.22")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
}
