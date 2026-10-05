// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Release signing material is NEVER stored in the repo. It comes from either:
 *   - environment variables (CI secrets): ZK_KEYSTORE_BASE64 or ZK_KEYSTORE_FILE,
 *     ZK_KEYSTORE_PASSWORD, ZK_KEY_ALIAS, ZK_KEY_PASSWORD; or
 *   - a local, git-ignored keystore.properties (storeFile/storePassword/keyAlias/keyPassword).
 * When nothing is provided, the release build is simply left unsigned.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun secret(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

// The spoofed variant only changes the package name; override with
// -PspoofedAppId=<id>. Default stays within our own namespace.
val spoofedAppId = (project.findProperty("spoofedAppId") as String?) ?: "com.zairenkai.app.spoofed"

android {
    namespace = "com.zairenkai.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zairenkai.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // Expected signer (SHA-256). Empty => self-check reports "unknown" and
        // never blocks (debug/spoofed). Set for official release below.
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
    }

    signingConfigs {
        create("release") {
            val b64 = System.getenv("ZK_KEYSTORE_BASE64")
            val ksPath = secret("ZK_KEYSTORE_FILE", "storeFile")
            when {
                b64 != null -> {
                    val out = layout.buildDirectory.file("zk-release.jks").get().asFile
                    out.parentFile.mkdirs()
                    out.writeBytes(Base64.getDecoder().decode(b64.trim()))
                    storeFile = out
                }
                ksPath != null -> storeFile = file(ksPath)
            }
            storePassword = secret("ZK_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("ZK_KEY_ALIAS", "keyAlias")
            // PKCS12 keystores protect the key with the store password, so the
            // key password defaults to the store password when not given.
            keyPassword = secret("ZK_KEY_PASSWORD", "keyPassword")
                ?: secret("ZK_KEYSTORE_PASSWORD", "storePassword")
            // Strongest APK signature schemes available.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            isDefault = true
        }
        create("spoofed") {
            dimension = "distribution"
            applicationId = spoofedAppId
            versionNameSuffix = "-spoofed"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign only when signing material was actually supplied.
            val cfg = signingConfigs.getByName("release")
            signingConfig = if (cfg.storeFile != null && cfg.storePassword != null) cfg else null
            // SHA-256 of the official Zairenkai release certificate.
            buildConfigField(
                "String", "EXPECTED_CERT_SHA256",
                "\"02B4596737261F5C154CCC323B27467A483034A5880A9DB0822EEED66FAC1944\"",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Debug is auto-signed by Android's debug keystore; no release key needed.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// AGP 9 built-in Kotlin: configure the compiler via the kotlin DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
