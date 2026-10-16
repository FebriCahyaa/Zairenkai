// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
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
// CI secrets and shells often carry a trailing newline or stray whitespace
// (e.g. `echo "$pw" | gh secret set`). An invisible "\n" on the keystore
// password reads to the signer as "password was incorrect", so trim here.
fun secret(env: String, prop: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(prop))?.trim()?.takeIf { it.isNotEmpty() }

// The release workflow stages the complete, checksum-manifested root module
// before building APKs. Pin its archive SHA-256 into BuildConfig so the app can
// authenticate the bundled bytes using the signed APK as the trust anchor.
val embeddedModuleZip = project.file("src/main/assets/embedded/Zairenkai-module.zip")
val embeddedModuleSha256 = if (embeddedModuleZip.isFile) {
    val digest = MessageDigest.getInstance("SHA-256")
    embeddedModuleZip.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
} else ""
val buildId = run {
    val commit = System.getenv("GITHUB_SHA")?.trim()?.take(8)?.takeIf { it.isNotEmpty() }
    val runId = System.getenv("GITHUB_RUN_ID")?.trim()?.takeIf { it.isNotEmpty() }
    val attempt = System.getenv("GITHUB_RUN_ATTEMPT")?.trim()?.takeIf { it.isNotEmpty() }
    when {
        commit != null && runId != null -> "$commit-$runId-${attempt ?: "1"}"
        commit != null -> System.getenv("GITHUB_SHA")!!.trim().take(12)
        else -> "local"
    }
}
val zkfcApiMajor = runCatching {
    Regex("API_MAJOR\\s*=\\s*(\\d+)")
        .find(project.file("../tools/zkfc-license/zkfc_license.py").readText())
        ?.groupValues?.get(1)?.toInt()
}.getOrNull() ?: 1
val kernelUapiApiMajor = runCatching {
    Regex("#define\\s+ZKFC_API_MAJOR\\s+(\\d+)")
        .find(project.file("../kernel/include/uapi/linux/zkfc.h").readText())
        ?.groupValues?.get(1)?.toInt()
}.getOrNull() ?: -1
require(zkfcApiMajor == kernelUapiApiMajor) {
    "ZKFC API major mismatch: token issuer=$zkfcApiMajor, kernel UAPI=$kernelUapiApiMajor. " +
        "Update both contracts deliberately before shipping."
}

/** Return the SHA-256 fingerprint of the X.509 certificate used by this keystore alias. */
fun certificateSha256(file: File, storePassword: String, alias: String): String {
    var loaded: KeyStore? = null
    var lastError: Exception? = null
    for (type in listOf("PKCS12", "JKS")) {
        try {
            val candidate = KeyStore.getInstance(type)
            file.inputStream().use { candidate.load(it, storePassword.toCharArray()) }
            loaded = candidate
            break
        } catch (e: Exception) {
            lastError = e
        }
    }
    val keystore = loaded ?: throw GradleException(
        "Unable to open release keystore '$file' as PKCS12 or JKS. " +
            "Check ZK_KEYSTORE_BASE64/ZK_KEYSTORE_FILE and ZK_KEYSTORE_PASSWORD.",
        lastError,
    )
    val certificate = keystore.getCertificate(alias)
        ?: throw GradleException("Alias '$alias' was not found in release keystore '$file'.")
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02X".format(it) }
}

// Update manifests are proxied by the License Server. Production CI must provide
// ZKFC_LICENSE_SERVER_URL (or -PlicenseServerUrl); empty is allowed only for
// local compilation and makes the in-app updater fail closed with a clear error.
val appVersionCode = providers.gradleProperty("versionCode")
    .orNull
    ?.toIntOrNull()
    ?: 2
require(appVersionCode in 1..2_100_000_000) {
    "versionCode must be between 1 and 2100000000."
}
val canaryBuildId = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.takeIf { it.matches(Regex("[0-9]{1,9}")) } ?: "local"

val licenseServerUrl = providers.gradleProperty("licenseServerUrl")
    .orElse(providers.environmentVariable("ZKFC_LICENSE_SERVER_URL"))
    .orElse("")
    .get()
    .trim()
    .trimEnd('/')
require(
    licenseServerUrl.isBlank() ||
        Regex("^https://[A-Za-z0-9.-]+(?::443)?$").matches(licenseServerUrl)
) {
    "ZKFC_LICENSE_SERVER_URL must be an HTTPS origin without a path."
}

android {
    namespace = "com.zairenkai.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zairenkai.app"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = "1.1.0"
        vectorDrawables { useSupportLibrary = true }
        // Expected signer (SHA-256). Local unsigned builds report "unknown";
        // official Canary and Release builds pin the certificate below.
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
        buildConfigField("String", "EMBEDDED_MODULE_SHA256", "\"$embeddedModuleSha256\"")
        buildConfigField("String", "ZKFC_BUILD_ID", "\"$buildId\"")
        buildConfigField("int", "ZKFC_API_MAJOR", "$zkfcApiMajor")
        buildConfigField("String", "ZKFC_LICENSE_SERVER_URL", "\"$licenseServerUrl\"")
    }

    signingConfigs {
        create("release") {
            val b64 = System.getenv("ZK_KEYSTORE_BASE64")?.trim()?.takeIf { it.isNotEmpty() }
            val ksPath = secret("ZK_KEYSTORE_FILE", "storeFile")
            when {
                b64 != null -> {
                    val out = layout.buildDirectory.file("zk-release.jks").get().asFile
                    out.parentFile.mkdirs()
                    // MIME decoder tolerates line-wrapped base64 (e.g. plain
                    // `base64 keystore.jks` without -w0), which the strict
                    // decoder would reject.
                    out.writeBytes(Base64.getMimeDecoder().decode(b64))
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

    // Pin the in-app signature guard to the certificate actually loaded for release
    // signing. This avoids a stale hard-coded fingerprint when the owner's keystore
    // is provisioned through GitHub Actions secrets.
    val releaseSigningConfig = signingConfigs.getByName("release")
    val releaseCertificateSha256 = run {
        val file = releaseSigningConfig.storeFile
        val password = releaseSigningConfig.storePassword
        val alias = releaseSigningConfig.keyAlias
        if (file != null && file.isFile && !password.isNullOrBlank() && !alias.isNullOrBlank()) {
            certificateSha256(file, password, alias)
        } else {
            ""
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            isDefault = true
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
            signingConfig = if (
                cfg.storeFile?.isFile == true &&
                !cfg.storePassword.isNullOrBlank() &&
                !cfg.keyAlias.isNullOrBlank() &&
                !cfg.keyPassword.isNullOrBlank()
            ) cfg else null
            // Keep runtime certificate verification aligned with the actual release key.
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$releaseCertificateSha256\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Debug is auto-signed by Android's debug keystore; no release key needed.
        }
        // Canary is a separate package identity but remains minified and non-debuggable.
        // When official signing material is available it uses the same trusted signer
        // as Release; it is never published with Android's debug certificate.
        create("canary") {
            initWith(getByName("release"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary.$canaryBuildId"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            val cfg = signingConfigs.getByName("release")
            signingConfig = if (
                cfg.storeFile?.isFile == true &&
                !cfg.storePassword.isNullOrBlank() &&
                !cfg.keyAlias.isNullOrBlank() &&
                !cfg.keyPassword.isNullOrBlank()
            ) cfg else null
            matchingFallbacks += "release"
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$releaseCertificateSha256\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }

    // Robolectric UI tests read the merged resources; Roborazzi writes the
    // screenshots it captures to build/outputs/roborazzi for review.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.output.dir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.path)
            }
        }
    }
    // The in-app language picker needs every language in the install;
    // Play language splits would drop the ones not in the device settings.
    bundle {
        language { enableSplit = false }
    }
    packaging {
        // Standalone GPL boot-patch executables are packaged under jniLibs so
        // Android extracts them to nativeLibraryDir for direct process execution.
        // They are intentionally not loaded into the proprietary JVM process.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// Do not create or publish debug APK variants. Automated validation and distributable
// builds use the optimized Canary and Release variants only.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.enable = false
    }
}

// Open-source licences ("Lisensi terbuka"). The libraries packaged in a
// variant (its runtime classpath, BOMs excluded) are matched against
// tools/licenses/libraries.json (name, description, developers, website,
// source and licences from each library's POM; regenerate it with
// tools/licenses/update_libraries.py). The build writes
// assets/oss_licenses.json with the full text of every licence used
// (tools/licenses/texts) and fails when a packaged library has no entry, so
// the list can never silently fall behind the dependencies.
abstract class OssLicensesTask : DefaultTask() {
    @get:Input abstract val modules: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val metadata: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val texts: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun generate() {
        val meta = groovy.json.JsonSlurper().parse(metadata.get().asFile) as Map<String, Any?>
        val libs = meta["libraries"] as Map<String, Map<String, Any?>>
        val coords = modules.get()
        val missing = coords.map { it.substringBeforeLast(":") }.filter { it !in libs }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "No licence metadata for: ${missing.joinToString()}.\n" +
                    "Run: ./gradlew :app:writeOssModules && python3 tools/licenses/update_libraries.py",
            )
        }
        val entries = coords.map { coord ->
            val ga = coord.substringBeforeLast(":")
            libs.getValue(ga) + mapOf("id" to ga, "version" to coord.substringAfterLast(":"))
        } + (meta["extra"] as List<Map<String, Any?>>)
        val sorted = entries.sortedBy { (it["name"] as String).lowercase() }
        val used = sorted.flatMap { e -> (e["licenses"] as List<Map<String, Any?>>).map { it["spdx"] as String } }.toSortedSet()
        val textDir = texts.get().asFile
        val textMap = used.associateWith { id -> File(textDir, "$id.txt").takeIf { it.exists() }?.readText() }.filterValues { it != null }
        val out = outputDir.get().asFile.apply { mkdirs() }
        File(out, "oss_licenses.json").writeText(groovy.json.JsonOutput.toJson(mapOf("libraries" to sorted, "texts" to textMap)) + "\n")
    }
}

/** "group:name:version" of every library packaged in [runtime], BOMs and platforms excluded. */
fun packagedModules(runtime: Configuration): List<String> =
    runtime.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .filterNot { it.module.endsWith("-bom") || it.module.endsWith("-bill-of-materials") }
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()

val ossRuntimes = mutableListOf<Configuration>()
androidComponents {
    onVariants { variant ->
        val runtime = variant.runtimeConfiguration
        ossRuntimes += runtime
        val task = tasks.register<OssLicensesTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}OssLicenses") {
            modules.set(provider { packagedModules(runtime) })
            metadata.set(rootProject.layout.projectDirectory.file("tools/licenses/libraries.json"))
            texts.set(rootProject.layout.projectDirectory.dir("tools/licenses/texts"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, OssLicensesTask::outputDir)
    }
}

// Every variant's packaged libraries: input for tools/licenses/update_libraries.py.
tasks.register("writeOssModules") {
    val out = layout.buildDirectory.file("oss/modules.txt")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val all = ossRuntimes.flatMap { packagedModules(it) }.toSortedSet()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(all.joinToString("\n") + "\n")
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
    // Icons are Zairenkai's own (ui/icons/ZkIcons.kt); no icon library.
    implementation(libs.androidx.navigation.compose)
    // Setup-wizard animations (res/raw/setup_*.json, generated by tools/lottie).
    implementation(libs.lottie.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // JVM UI tests (Robolectric) for the setup flow, system bars and
    // collapsing titles; Roborazzi captures screenshots for visual review.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Robolectric needs an Android framework jar at test time. Resolve it through
// Gradle (same repositories, proxy and cache as every other dependency) and
// run Robolectric offline against it, instead of letting it download on its own.
// Keep each SDK version in its own configuration: Gradle conflict resolution
// otherwise selects only one version of android-all-instrumented, leaving the
// other SDK requested by @Config(sdk = [...]) absent from the offline directory.
val robolectricRuntime = configurations.create("robolectricRuntime")
val robolectricApi32Runtime = configurations.create("robolectricApi32Runtime")
dependencies {
    // Provide every SDK used by @Config tests; offline Robolectric resolves jars by exact filename.
    add(robolectricRuntime.name, libs.robolectric.android.all)
    add(robolectricApi32Runtime.name, libs.robolectric.android.api32)
    // Provides Ed25519 verification on Android 12+ without depending on platform provider availability.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Copy>("copyRobolectricJars") {
    from(robolectricRuntime)
    from(robolectricApi32Runtime)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
}
