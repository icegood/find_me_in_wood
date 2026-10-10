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
    // Release signing: fmiw.* in ~/.gradle/gradle.properties locally, KEYSTORE_* env in
    // CI (release.yml decodes the ANDROID_KEYSTORE_* secrets). Without either, release
    // outputs stay unsigned rather than silently carrying the debug key.
    val releaseSigning = Properties().apply {
        val userFile = File(System.getProperty("user.home"), ".gradle/gradle.properties")
        userFile.takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.let { props ->
        fun pick(prop: String, env: String) = props.getProperty(prop) ?: System.getenv(env)?.ifBlank { null }
        val storeFile = pick("fmiw.storeFile", "KEYSTORE_FILE")
        val storePassword = pick("fmiw.storePassword", "KEYSTORE_PASSWORD")
        if (storeFile == null || storePassword == null) {
            null
        } else {
            mapOf(
                "storeFile" to storeFile,
                "storePassword" to storePassword,
                "keyAlias" to (pick("fmiw.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "fmiw"),
                // PKCS12 stores (fmiw-release.jks) use the store password for the key.
                "keyPassword" to (pick("fmiw.keyPassword", "KEYSTORE_KEY_PASSWORD") ?: storePassword),
            )
        }
    }
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }
    buildTypes {
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
