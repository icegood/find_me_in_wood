plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.register("verifyModuleRules") {
    group = "verification"
    description = "Constitution P3/P6: core/* and transport:api must stay pure JVM (no Android plugins)"
    doLast {
        val forbidden = listOf("com.android.application", "com.android.library", "org.jetbrains.kotlin.android")
        val pure = listOf(":core:model", ":core:crypto", ":core:session", ":transport:api")
        pure.forEach { path ->
            val p = project(path)
            forbidden.forEach { pluginId ->
                if (p.plugins.hasPlugin(pluginId)) {
                    throw GradleException("Module rule violation: $path must not apply '$pluginId' (constitution P3/P6)")
                }
            }
        }
    }
}
