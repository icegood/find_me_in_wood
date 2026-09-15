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
        versionCode = 1
        versionName = "0.1"

        val googleClientId = (project.findProperty("google.webClientId") as String?)
            ?: properties["google.webClientId"] as String? ?: ""
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClientId\"")
    }
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val userFile = File(System.getProperty("user.home"), ".gradle/gradle.properties")
                userFile.takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            val configured = props.getProperty("fmiw.storeFile") != null
            if (configured) {
                storeFile = file(props.getProperty("fmiw.storeFile"))
                storePassword = props.getProperty("fmiw.storePassword")
                keyAlias = props.getProperty("fmiw.keyAlias")
                keyPassword = props.getProperty("fmiw.keyPassword")
            } else {
                // CI fallback: sign release builds with the debug key when no
                // fmiw.* credentials are available on the machine.
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
