import java.io.File

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
// ---------------------------------------------------------------------------
// Coverage (JaCoCo): unit tests only, mocks allowed solely at API/HW borders.
// Per-module gate + aggregate report; CI fails below 95% lines.
// ---------------------------------------------------------------------------
apply(plugin = "jacoco")

val COVERAGE_MIN = "0.95".toBigDecimal()

val coverageExcludes = listOf(
    "**/R.class", "**/R$*.class", "**/R$*.txt", "**/R.jar",
    "**/BuildConfig.*", "**/Manifest*.*",
)

// Border-glue excluded from the coverage GATE (verified on devices/instrumented runs,
// constitution P6: radios sit behind interfaces). Everything else must be >=95% lines.
// Each entry names an implementation whose body is a direct platform/hardware call.
val gateScopeExcludes = coverageExcludes + listOf(
    // Android framework glue (activities/services/providers):
    "**/ShareInboxActivity*",
    "**/MainActivity*", "**/SessionService*",
    "**/AndroidGnssSource*",        // LocationManager listener glue
    "**/Prefs*",                    // SharedPreferences glue
    "**/MapScreenKt*",              // Composable + osmdroid view wiring
    // Hardware byte-pipes / stack callbacks (BT/USB/LoRa node links):
    "**/BluetoothGattWiring*",                    // GATT/LE stack calls (device-verified)
    "**/WifiDirectTransport\$requestPeerList*",  // WifiP2p event callbacks
    "**/WifiDirectTransport\$requestConnectionInfo*",
    "**/UsbSerialLink*",
)

subprojects {
    afterEvaluate {
        val isAndroid = plugins.hasPlugin("com.android.application") ||
            plugins.hasPlugin("com.android.library")

        // Android modules rely on AGP offline instrumentation (enableUnitTestCoverage);
        // adding Gradle's jacoco plugin would attach a second (runtime) agent -> broken data.
        if (!isAndroid) apply(plugin = "jacoco")

        if (plugins.hasPlugin("com.android.library")) {
            extensions.configure(com.android.build.api.dsl.LibraryExtension::class.java) {
                buildTypes.matching { it.name == "debug" }.configureEach {
                    enableUnitTestCoverage = true
                }
            }
        } else if (plugins.hasPlugin("com.android.application")) {
            extensions.configure(com.android.build.api.dsl.ApplicationExtension::class.java) {
                buildTypes.matching { it.name == "debug" }.configureEach {
                    enableUnitTestCoverage = true
                }
            }
        }

        val testTaskNames = if (isAndroid) setOf("testDebugUnitTest") else setOf("test")
        val execFiles = if (isAndroid) {
            files(layout.buildDirectory.file(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
        } else {
            files(layout.buildDirectory.file("jacoco/test.exec"))
        }
        val classDirs = if (isAndroid) {
            files(
                layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
                layout.buildDirectory.dir("intermediates/javac/debug/classes"),
            )
        } else {
            files(
                layout.buildDirectory.dir("classes/kotlin/main"),
                layout.buildDirectory.dir("classes/java/main"),
            )
        }

        tasks.register<JacocoReport>("jacocoModuleReport") {
            group = "verification"
            dependsOn(tasks.matching { it.name in testTaskNames })
            executionData.setFrom(execFiles)
            sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
            classDirectories.setFrom(classDirs.asFileTree.matching { exclude(coverageExcludes) })
            reports {
                xml.required.set(true)
                html.required.set(true)
                html.outputLocation.set(layout.buildDirectory.dir("reports/coverage"))
            }
        }

        tasks.register<JacocoCoverageVerification>("jacocoModuleVerify") {
            group = "verification"
            description = "Fails if line coverage < 95%"
            dependsOn(tasks.matching { it.name in testTaskNames })
            executionData.setFrom(execFiles)
            classDirectories.setFrom(classDirs.asFileTree.matching { exclude(coverageExcludes) })
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = COVERAGE_MIN
                    }
                }
            }
        }
    }
}

// Whitelist of classes the >=95% line gate applies to: all domain logic plus
// transport decision logic testable as plain JVM. Excluded = platform/HW border
// implementations verified on devices (constitution P6).
val gateIncludes = listOf(
    "app/findmeinwood/core/**",
    "app/findmeinwood/transport/api/**",
    "app/findmeinwood/transport/lora/StreamFramer*",
    "app/findmeinwood/transport/lora/FrameChunker*",
    "app/findmeinwood/transport/lora/LoraTransport*",
    "app/findmeinwood/transport/lora/LoraNodeLink*",
    "app/findmeinwood/transport/bluetooth/FrameFramer*",
    "app/findmeinwood/transport/bluetooth/BluetoothTransport.class",
    
    "app/findmeinwood/transport/bluetooth/BluetoothTransportKt*",
    "app/findmeinwood/transport/share/ShareInbox.class",
    "app/findmeinwood/transport/share/ShareTransport\$Companion*",
    "app/findmeinwood/transport/wifidirect/WifiDirectTransport*",
    "app/findmeinwood/app/SessionBus*",
    "app/findmeinwood/app/ProfileStore*",
    "app/findmeinwood/app/IdentityHolder*",
)

private fun execPaths(): List<String> =
    subprojects.flatMap { p ->
        listOf(
            File(p.buildDir, "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec").absolutePath,
            File(p.buildDir, "jacoco/test.exec").absolutePath,
        )
    }

private fun existingExecs(): List<File> = execPaths().map(::File).filter { it.exists() }

private fun moduleClassDirs(): List<Any> =
    subprojects.flatMap { p ->
        listOf(
            File(p.buildDir, "tmp/kotlin-classes/debug"),
            File(p.buildDir, "intermediates/javac/debug/classes"),
            File(p.buildDir, "classes/kotlin/main"),
            File(p.buildDir, "classes/java/main"),
        )
    }

tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Aggregated unit-test coverage across all modules"
    dependsOn(subprojects.map { it.tasks.named("jacocoModuleReport") })
    sourceDirectories.setFrom(subprojects.map { q -> q.files("src/main/kotlin", "src/main/java") })
    classDirectories.setFrom(provider { moduleClassDirs() })
    executionData.setFrom(provider { existingExecs() })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated-html"))
    }
}

tasks.register<JacocoCoverageVerification>("coverageGate") {
    group = "verification"
    description = "Repo-wide gate: >=95% lines over all non-border-glue code"
    dependsOn(subprojects.map { it.tasks.named("jacocoModuleReport") })
    classDirectories.setFrom(
        provider {
            files(moduleClassDirs()).asFileTree.matching {
                include(gateIncludes)
                exclude(gateScopeExcludes)
            }
        },
    )
    executionData.setFrom(provider { existingExecs() })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = COVERAGE_MIN
            }
        }
    }
}

tasks.register("coverageVerify") {
    group = "verification"
    description = "All coverage gates: pure-module verifies + repo-wide gated scope"
    dependsOn(
        ":core:model:jacocoModuleVerify",
        ":core:crypto:jacocoModuleVerify",
        ":core:session:jacocoModuleVerify",
        ":transport:api:jacocoModuleVerify",
        "coverageGate",
    )
}
