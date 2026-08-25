plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.findmeinwood.transport.lora"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":transport:api"))
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
    testImplementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.core)
}
