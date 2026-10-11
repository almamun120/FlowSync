plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.flowsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.flowsync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Permanent signing key: supplied through environment variables (GitHub Secrets in CI).
    // Without them the build falls back to the debug key so it still installs.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    val hasReleaseKey = keystorePath != null && File(keystorePath).exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val key = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        debug { signingConfig = key }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
                "META-INF/*.kotlin_module"
            )
            pickFirsts += setOf("META-INF/MANIFEST.MF", "**/module-info.class")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Protocol clients (all pure user-space, no root required)
    implementation("com.hierynomus:smbj:0.13.0")          // SMB 2/3
    implementation("commons-net:commons-net:3.11.1")      // FTP / FTPS
    implementation("com.github.mwiede:jsch:0.2.21")       // SFTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")  // WebDAV + Google Drive REST
    implementation("com.google.android.gms:play-services-auth:21.2.0") // Google Drive authorization
}
