plugins {
    id("com.android.library")
    kotlin("android")
}
android {
    namespace = "dev.tachimanga.dropbox.hostapi"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.7.22")
    compileOnly("com.squareup.okhttp3:okhttp:4.10.0")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("androidx.preference:preference:1.2.1")
}
