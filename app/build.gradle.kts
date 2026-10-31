plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Filled in by the GitHub build from the official Nuvio app; can also be set in the app.
fun buildValue(name: String): String =
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: ""

android {
    namespace = "app.nuviowatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nuviowatch"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        buildConfigField("String", "DEFAULT_SERVER_URLS", "\"${buildValue("NUVIO_SERVER_URLS")}\"")
        buildConfigField("String", "DEFAULT_SERVER_KEY", "\"${buildValue("NUVIO_SERVER_KEY")}\"")
        // The family's shared database. The publishable key is meant to ship in apps;
        // row-level security and the family key protect the data.
        buildConfigField("String", "FAMILY_DB_URL", "\"https://abkwdwlihhyyhejdjihm.supabase.co\"")
        buildConfigField("String", "FAMILY_DB_KEY", "\"sb_publishable_MX1ApdkrbjXLgWLc2hRl4A_ZcCDXjoN\"")
    }

    signingConfigs {
        // Optional fixed signing key so new builds install over old ones (see README).
        val keystore = System.getenv("SIGNING_KEYSTORE_FILE")
        if (!keystore.isNullOrBlank()) {
            create("fixed") {
                storeFile = file(keystore)
                // Pasted secrets often end with a stray newline.
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.trim()
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.trim()
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
}
