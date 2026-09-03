import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.chaquopy.python)
}

room {
    schemaDirectory("$projectDir/schemas")
}

private val allAbis = listOf("arm64-v8a", "x86_64")

/**

Points the instrumented suite at the  `./gradlew connectedAndroidTest PtestRelease`,
for R8
**/
private val testRelease: Boolean = project.hasProperty("testRelease")

/**
 * ABIs the **release** APK keeps: arm64-v8a only, which is every device this ships to. Debug keeps
 * all of [allAbis] so it still installs on the x86_64 emulator.
 */
private val releaseAbis: List<String> =
    (project.findProperty("releaseAbis") as String?)
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?: if (testRelease) allAbis else listOf("arm64-v8a")

/**
 * Deployment values that are not the same on every machine, read from `local.properties`.
 *
 * `local.properties` is tracked here — it already carries `sdk.dir` — so these are defaults with a
 * committed value, not secrets. Neither of them is one: a Google **Web** OAuth client id is a public
 * identifier that ships inside every APK by design, and a server address is a server address. The
 * only thing that must never appear here is a client *secret*, and ID-token verification does not
 * use one (viveCServer `deploy/env.example`).
 *
 * A `-P` on the command line outranks the file, so a one-off build can point at another server
 * without editing anything: `./gradlew assembleDebug -Pvive.cloudBaseUrl=http://192.168.1.20:5444`.
 */
private val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

private fun localSetting(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: fallback


 // The upload key's four values, read from a **gitignored** `.env` at the repo root.
private val dotenv: Properties = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use(::load)
}

private fun secret(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf(String::isNotBlank)
        ?: dotenv.getProperty(name)?.takeIf(String::isNotBlank)
        ?: System.getenv(name)?.takeIf(String::isNotBlank)


// The keystore to sign `release` with, or null when there is nothing to sign with
private val releaseKeystore: File? =
    secret("VIVE_KEYSTORE")?.let { path ->
        rootProject.file(path).also { file ->
            // Set-but-wrong is a typo, not a machine without a key: say so instead of quietly
            // handing back an unsigned APK an hour before an upload.
            require(file.exists()) {
                "VIVE_KEYSTORE is set to \"$path\" (${file.absolutePath}), which does not exist."
            }
        }
    }

/**
 * Whether the release variant carries the upload key. False under [testRelease], which signs with
 * the debug key on purpose: that variant exists to put instrumented tests through R8, and it is
 * installed on tablets and emulators all day.
 */
private val signedRelease: Boolean = releaseKeystore != null && !testRelease

/** Single source of truth for `versionName` and for the [releaseArtifactName] built from it. */
private val appVersionName = "1.2.1"

/** `vivenotes-1.2.1.apk` / `.aab`, the names a signed release is published under. */
private val releaseArtifactName = "vivenotes-$appVersionName"

/**
 * Renames the AAB, which — unlike the APK — has no `outputFileName` to set. AGP builds the bundle's
 * name as `<archivesName>-<variant>.aab` and offers no hook on that half, so the rename is an
 * artifact *transform*: [SingleArtifact.BUNDLE] goes in, the same bytes come out under the name
 * `withName` gives, and the transformed file becomes the bundle. A `Copy` task would have left a
 * second, staler AAB of the same size beside it.
 */
abstract class NameBundleTask : DefaultTask() {
    @get:InputFile
    abstract val bundle: RegularFileProperty

    @get:OutputFile
    abstract val renamed: RegularFileProperty

    @TaskAction
    fun rename() {
        bundle.get().asFile.copyTo(renamed.get().asFile, overwrite = true)
    }
}

android {
    namespace = "com.vivenotes"
    // 37, because Compose 1.12 refuses to be compiled against anything older — see the material3
    // note in `gradle/libs.versions.toml`.
    // Side benefit: it clears the one thing blocking AppFunctions (feature I7, `docs/plan.md` §12).
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.vivenotes"
        minSdk = 35
        targetSdk = 36
        versionCode = 5
        versionName = appVersionName

        testInstrumentationRunner = "com.vivenotes.ViveNotesTestRunner"

        /*
         * The Google **Web** OAuth client id handed to Credential Manager as `serverClientId`, and
         * the audience `VIVE_GOOGLE_CLIENT_IDS` must accept on the server — the two are one string
         * and a mismatch is rejected at the audience check before any signature work.
         *
         * Empty is a supported state, not a broken one: `CloudAccountScreen` disables Sign in with
         * Google and says why, rather than letting Credential Manager fail with a provider error
         * that reads like the user's Google account is at fault.
         */
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localSetting("vive.googleWebClientId", "")}\"",
        )
        // Stable Play Console product id. Price and billing period live in the activated base plan;
        // the app reads their localized presentation from ProductDetails and never hardcodes $4.99.
        buildConfigField(
            "String",
            "CLOUD_STORAGE_SUBSCRIPTION_ID",
            "\"${localSetting("vive.cloudStorageSubscriptionId", "vivenotes_storage_monthly")}\"",
        )

        ndk {
            abiFilters += allAbis
        }
    }

    // Instrumented tests are built against `debug` unless [testRelease] says otherwise.
    if (testRelease) {
        testBuildType = "release"
    }

    // Created only when [signedRelease]; `findByName("release")` below is null otherwise.
    signingConfigs {
        val keystore = releaseKeystore
        if (signedRelease && keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = secret("VIVE_KEYSTORE_PASSWORD")
                keyAlias = secret("VIVE_KEY_ALIAS")
                // A key generated with `-destkeypass` left off shares the store's password, which
                // is what `keytool -genkeypair` does when you press enter at the second prompt.
                keyPassword = secret("VIVE_KEY_PASSWORD") ?: secret("VIVE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
             // The debug app is `com.vivenotes.debug`; release keeps the bare `com.vivenotes`
            buildConfigField(
                "String",
                "CLOUD_BASE_URL",
                "\"${localSetting("vive.cloudBaseUrl", "http://10.0.2.2:5444")}\"",
            )
            applicationIdSuffix = ".debug"
            // `AboutDialog` reads the version off the installed package, so this is what tells the
            // two apart from inside the app. The launcher tells them apart by `app_name`, which
            // `src/debug/res/values/strings.xml` overrides for this variant alone.
            versionNameSuffix = "-debug"
        }
        release {
            // The managed deployment. https, and not only as good practice: this is the one build
            // that reaches a server across the public internet, and the device token it carries
            // cannot be reissued.
            buildConfigField(
                "String",
                "CLOUD_BASE_URL",
                "\"${localSetting("vive.cloudBaseUrl", "https://cloud.vivenotes.net")}\"",
            )
            // AGP 9.2 still uses the legacy switches; AGP 9.3+ can replace these with
            // `optimization { enable = true }`. Rules in src/main/keepRules are merged by AGP.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Applies to the androidTest APK's own R8 run, which only happens under [testRelease];
            // inert otherwise, since a debug test APK is not minified.
            testProguardFiles("proguard-rules-androidTest.pro")
            // Null when neither branch applies, which leaves the variant unsigned.
            signingConfig =
                if (testRelease) signingConfigs.getByName("debug")
                else signingConfigs.findByName("release")
            if (testRelease) {
                // Test infrastructure the app APK has to retain for the test APK to resolve it.
                proguardFiles("proguard-rules-testRelease.pro")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Off by default since AGP 8, and turned back on for one thing: `BuildConfig.DEBUG` is a
        // compile-time constant, so a block guarded by it is removed from the release APK entirely
        // rather than shipped and skipped. `RecognitionPanel`'s SymPy diagnostics are the first
        // caller. A runtime `FLAG_DEBUGGABLE` check would read the same and strip nothing.
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        //Drops whatever [releaseAbis] leaves out of the release APK.
        variant.packaging.jniLibs.excludes.addAll(
            (allAbis - releaseAbis.toSet()).map { abi -> "lib/$abi/**" }
        )

        // Only a signed build is publishable, so only a signed build gets the published name. An
        // unsigned or debug-signed release keeps AGP's `app-release-unsigned.apk` /
        // `app-release.apk`, which is the one thing about those files worth reading at a glance.
        if (signedRelease) {
            variant.outputs.forEach { output ->
                output.outputFileName.set("$releaseArtifactName.apk")
            }
            variant.artifacts
                .use(tasks.register<NameBundleTask>("nameBundleForRelease"))
                .wiredWithFiles(NameBundleTask::bundle, NameBundleTask::renamed)
                .withName("$releaseArtifactName.aab")
                .toTransform(SingleArtifact.BUNDLE)
        }
    }
}

// Which CPython builds the requirements venv.
private val buildPythonCommand: String =
    File("/usr/bin/python3.13").takeIf { it.canExecute() }?.path ?: "python3.13"

chaquopy {
    defaultConfig {
        // 3.13 has current arm64/x86-64 support and Chaquopy's 16 KB-page compatibility fixes.
        version = "3.13"
        buildPython(buildPythonCommand)
        pip {
            install("sympy==1.14.0")
            // SymPy 1.14's generated LaTeX parser requires this ANTLR generation line.
            install("antlr4-python3-runtime==4.11.1")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner, for the foreground sync cadence (memory/syncPlan.md SD6). Counting
    // started Activities would avoid the dependency and get configuration changes wrong: this owner
    // already debounces the teardown and rebuild of the only Activity, so a rotation is not a
    // "went to background" event and does not cost a flush.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)

    // Sign in with Google. `credentials` is the API, `credentials-play-services-auth` is the
    // provider that actually answers on a device with Play services, and `googleid` supplies the
    // request option and the typed credential. All three are required: without the provider the
    // request returns NoCredentialException on every device.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    // Managed cloud storage is a digital service, so Play-distributed builds use Google Play
    // Billing rather than Google Pay. Purchase tokens are verified and acknowledged by the managed
    // backend before access is granted; this dependency only owns the Play Store UI and discovery.
    implementation(libs.google.play.billing)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.storage)
    implementation(libs.androidx.ink.strokes)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.ratex.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    // Virtual time. The sync clock's whole behaviour is "how often", and a test that waited for a
    // real 60 s interval would either be slow or be a test of something shorter than the thing.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // `createComposeRule` needs its otherwise-empty ComponentActivity declared in the app under
    // test. Keep that test-only manifest out of normal release artifacts: it joins release only
    // for the explicitly requested minified instrumentation variant.
    if (testRelease) {
        releaseImplementation(libs.androidx.compose.ui.test.manifest)
    }
}
