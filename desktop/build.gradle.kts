import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

// Filled in by the GitHub build: Nuvio's public server key (from the official Nuvio app) and the version.
fun buildValue(name: String): String = (project.findProperty(name) as String?) ?: System.getenv(name) ?: ""
val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "0"

val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val key = buildValue("NUVIO_SERVER_KEY")
    val urls = buildValue("NUVIO_SERVER_URLS")
    inputs.property("key", key)
    inputs.property("urls", urls)
    inputs.property("run", runNumber)
    outputs.dir(out)
    doLast {
        val file = out.get().file("app/nuviowatch/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package app.nuviowatch.desktop
            |
            |object BuildInfo {
            |    const val VERSION = "1.0.$runNumber"
            |    const val RUN = ${runNumber.toIntOrNull() ?: 0}
            |    const val SERVER_KEY = "$key"
            |    const val SERVER_URLS = "$urls"
            |    const val FAMILY_DB_URL = "https://abkwdwlihhyyhejdjihm.supabase.co"
            |    const val FAMILY_DB_KEY = "sb_publishable_MX1ApdkrbjXLgWLc2hRl4A_ZcCDXjoN"
            |}
            """.trimMargin(),
        )
    }
}
sourceSets.main { kotlin.srcDir(generateBuildInfo) }

compose.desktop {
    application {
        mainClass = "app.nuviowatch.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Nuvio Watch"
            // macOS needs a version like 1.0.x with x up to 255... so use the run number's last parts.
            packageVersion = "1.${(runNumber.toIntOrNull() ?: 0) / 100}.${(runNumber.toIntOrNull() ?: 0) % 100}"
            modules("java.net.http", "jdk.unsupported")
            macOS {
                bundleID = "app.nuviowatch.desktop"
                // Made from icon.svg by the GitHub build.
                project.file("icon.icns").takeIf { it.exists() }?.let { iconFile.set(it) }
                appCategory = "public.app-category.entertainment"
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSMultipleInstancesProhibited</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}
