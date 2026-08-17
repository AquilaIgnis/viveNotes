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
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // androidx.ink ships libink.so per ABI at roughly 1.2MB each. Shipping all four costs
            // ~4.6MB in a single APK for architectures nothing here runs on; these two cover real
            // devices and the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
}
