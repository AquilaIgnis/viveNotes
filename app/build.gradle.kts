import java.io.File

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

/**
 * Every ABI the project builds native code for.
 *
 * Declared here and merged into `defaultConfig`, and it has to live there. **Chaquopy reads
 * `android.defaultConfig.ndk.abiFilters` plus product flavours and nothing else**
 * (`PythonPlugin.getAbis`), and fails the build outright if the result is empty, so a build type
 * naming its own would be invisible to it. Nor would that narrow anything: AGP's
 * `MergedNdkConfig.append` calls `abiFilters.addAll(...)` for every contributor, so defaultConfig,
 * flavours and build type are *unioned* — a build type can only ever add an ABI, never drop one.
 */
private val allAbis = listOf("arm64-v8a", "x86_64")

/**
 * Points the instrumented suite at the **release** build: `./gradlew connectedAndroidTest
 * -PtestRelease`, or the no-uninstall route in `CLAUDE.md`. When building the two APKs by hand,
 * request both `assembleReleaseAndroidTest` **and** `assembleRelease`: the first regenerates the
 * app mapping used to rewrite test references, but it does not package the matching app APK.
 *
 * It is the only way to see what R8 breaks. Unit tests run on the JVM against unminified classes and
 * a debug APK is not minified at all, so nothing else in the project exercises the shipping bytecode.
 * AGP re-runs R8 over the test APK with the app's own mapping file, which is what keeps test code
 * resolving app classes under their obfuscated names.
 *
 * Two things it has to switch on as well: the release build gets [releaseAbis] = [allAbis] so it
 * installs on an x86_64 emulator, and it gets signed (see the `release` build type), because a test
 * APK may only instrument a package carrying the same certificate.
 */
private val testRelease: Boolean = project.hasProperty("testRelease")

/**
 * ABIs the **release** APK keeps: arm64-v8a only, which is every device this ships to. Debug keeps
 * all of [allAbis] so it still installs on the x86_64 emulator.
 *
 * Measured: the unsigned APK falls from 139,151,305 to 88,965,966 bytes, a 36.1% cut — nearly twice
 * what enabling R8 was worth. x86_64's `libonnxruntime.so` was 33,975,544 of that on its own —
 * native libraries are page-aligned rather than deflated under `extractNativeLibs=false`, so it cost
 * every byte.
 *
 * Because `abiFilters` cannot subtract (see [allAbis]), the narrowing happens at packaging instead,
 * which is the one place AGP exposes per variant. Three consequences worth knowing:
 *
 *  - **A release APK will not run on an x86_64 emulator**, and R8 runs only in release, so a
 *    minification-only bug cannot be reproduced there by default. Build one that can with
 *    `./gradlew assembleRelease -PreleaseAbis=arm64-v8a,x86_64`.
 *  - **Chaquopy still emits its x86_64 assets** — `stdlib-x86_64.imy`, `requirements-x86_64.imy` and
 *    `bootstrap-native/x86_64/`, 3,145,771 bytes — because its ABI set is per-project, not
 *    per-variant, and assets are outside `packaging` anyway. They are inert: the matching `.so`
 *    files are gone and the bootstrap resolves its ABI at runtime. Removing them would take either
 *    an ABI product flavour, which renames every Gradle task documented in `CLAUDE.md` and in CI,
 *    or deleting files out of another plugin's task output. Neither is worth 3.5% of the APK.
 *  - `packageRelease` logs *"There are no .so files available to package in the APK for x86_64"* on
 *    every release build: `abiFilters` still names the ABI, because Chaquopy needs it to, and the
 *    packager is reporting that nothing survived for it. Benign.
 */
private val releaseAbis: List<String> =
    (project.findProperty("releaseAbis") as String?)
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?: if (testRelease) allAbis else listOf("arm64-v8a")

android {
    namespace = "com.vivenotes"
    // 37, because Compose 1.12 refuses to be compiled against anything older — see the material3
    // note in `gradle/libs.versions.toml`.
    //
    // **Compile-only, and that distinction is the whole point.** `compileSdk` picks the API stubs to
    // build against; it does not ship in the APK, does not gate installs, and does not change
    // behaviour on any device. Installability is `minSdk` (35, Android 15) and behaviour opt-ins are
    // `targetSdk` (36) — both **deliberately unchanged**, and the min is the user's own floor, not
    // something to raise for a library's convenience. Android 17's install base is irrelevant here.
    //
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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // androidx.ink ships libink.so per ABI at roughly 1.2MB each, ONNX Runtime's is 27-33MB,
            // and Chaquopy adds a libpython with its own OpenSSL and SQLite. All four Android ABIs
            // would be well over 100MB for architectures nothing here runs on; these two cover real
            // devices and the emulator, and [releaseAbis] narrows what actually ships further still.
            abiFilters += allAbis
        }
    }

    // Instrumented tests are built against `debug` unless [testRelease] says otherwise.
    if (testRelease) {
        testBuildType = "release"
    }

    buildTypes {
        release {
            // AGP 9.2 still uses the legacy switches; AGP 9.3+ can replace these with
            // `optimization { enable = true }`. Rules in src/main/keepRules are merged by AGP.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Applies to the androidTest APK's own R8 run, which only happens under [testRelease];
            // inert otherwise, since a debug test APK is not minified.
            testProguardFiles("proguard-rules-androidTest.pro")
            if (testRelease) {
                signingConfig = signingConfigs.getByName("debug")
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

/**
 * Drops whatever [releaseAbis] leaves out of the release APK.
 *
 * `packaging.jniLibs.excludes` is the per-variant equivalent of `abiFilters`; the `android.packaging`
 * DSL block is project-wide and would take x86_64 out of the debug APK as well. Patterns match the
 * merger's own path space, which is `lib/<abi>/<file>.so` — see
 * `build/intermediates/merged_native_libs/<variant>/.../out/lib/`, which holds all four Android ABIs
 * because `abiFilters` is not applied until packaging. `MergeNativeLibsTask` applies these excludes
 * before that, so the two never contradict each other.
 */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            (allAbis - releaseAbis.toSet()).map { abi -> "lib/$abi/**" }
        )
    }
}
/**
 * Which CPython builds the requirements venv.
 *
 * **The absolute path is this developer's machine, and must not be anyone else's.** It is here to
 * step around a `uv` shim: `uv`'s `python3.13` lives behind a symlink whose location gives Chaquopy
 * a broken prefix when it runs `python -m venv`, and naming the real interpreter is the fix.
 *
 * Hardcoding it broke CI on every single run — `ubuntu-latest` ships 3.12 as the system Python and
 * keeps 3.13 in the tool cache, so `installDebugPythonRequirements` died with *"'/usr/bin/python3.13'
 * does not exist"* before a single test ran, and the emulator job never started behind its `needs`.
 * So the path is used when it is genuinely there and Chaquopy is otherwise left to resolve
 * `python3.13` off PATH, which is where `actions/setup-python` puts it.
 *
 * Deliberately a file check rather than a `System.getenv("CI")` test: what matters is whether this
 * interpreter exists, not whose machine we are on, and a laptop without it should fall back too.
 */
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
