import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.icegood.findmeinwood.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icegood.findmeinwoods"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        val googleClientId = (project.findProperty("google.webClientId") as String?)
            ?: properties["google.webClientId"] as String? ?: ""
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClientId\"")
        // Phones only: the map engine's native code for x86 emulators doubled the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // Release signing: fmiw.* in ~/.gradle/gradle.properties locally, KEYSTORE_* env in
    // CI (release.yml decodes the ANDROID_KEYSTORE_* secrets). Without either, release
    // outputs stay unsigned rather than silently carrying the debug key.
    // Release signing, same scheme as home_media_library: credentials live in a gitignored
    // keystore.properties at the repo root (storeFile/storePassword/keyAlias/keyPassword).
    // CI renders it from the ANDROID_KEYSTORE_* secrets. Without it the release APK is
    // unsigned — never silently debug-signed.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = Properties().apply {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                // PKCS12 stores (fmiw-release.jks) use the store password for the key.
                keyPassword = keystoreProperties.getProperty("keyPassword")?.ifBlank { null }
                    ?: keystoreProperties.getProperty("storePassword")
            }
        } else {
            logger.warn(
                "keystore.properties not found: release APK will be unsigned " +
                    "(unusable for distribution)",
            )
        }
    }
    buildTypes {
        debug {
            // Debug builds carry the field-test link log. Signing them with the same key
            // lets them install over a release build (and vice versa) without an
            // uninstall wiping app data. Without keystore.properties: AGP's debug key.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)

    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation(project(":core:session"))
    implementation(project(":core:auth"))
    implementation(project(":core:chat"))
    implementation(project(":core:storage"))
    implementation(project(":core:p2p"))
    implementation(project(":transport:api"))
    implementation(project(":transport:share"))
    implementation(project(":transport:bluetooth"))
    implementation(project(":transport:lora"))
    implementation(project(":transport:wifidirect"))
    implementation(project(":feature:map"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.androidx.credentials.googleid)
}
