plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.notifforward.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "com.notifforward.app"; minSdk = 26; targetSdk = 35; versionCode = 30102; versionName = "3.1.2" }
    val releaseStore = System.getenv("WIN2MOBILE_ANDROID_KEYSTORE")
    val releaseAlias = System.getenv("WIN2MOBILE_ANDROID_KEY_ALIAS")
    val releaseStorePassword = System.getenv("WIN2MOBILE_ANDROID_STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("WIN2MOBILE_ANDROID_KEY_PASSWORD")
    val releaseSigningAvailable = listOf(releaseStore, releaseAlias, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }
    if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
        check(releaseSigningAvailable) { "Release requires a private signing key; use scripts/build_android.ps1 -Configuration release." }
    }
    if (releaseSigningAvailable) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir("../../../tests/fixtures")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    kapt("androidx.room:room-compiler:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
