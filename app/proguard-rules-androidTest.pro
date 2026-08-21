# Keep rules for the **instrumented test APK only**, wired in as `testProguardFiles`.
#
# R8 processes the androidTest APK whenever the app under test is minified, which here means
# `./gradlew connectedAndroidTest -PtestRelease` (see the `testRelease` KDoc in build.gradle.kts).
# Nothing in this file relaxes the app's own shrinking: it is a separate R8 invocation, already
# running with `-applymapping` against the app's mapping so test code resolves app classes under
# their obfuscated names. Rules that belong to the shipping APK go in `src/main/keepRules` instead.

# errorprone's @IncompatibleModifiers is declared as returning javax.lang.model.element.Modifier[],
# a JDK compile-time API with no Android counterpart. It reaches the test classpath transitively
# under androidx.test (Truth, then Guava) and is never touched at runtime, but R8 treats a dangling
# reference from an annotation as an error rather than a warning and fails the build outright. This
# is the line AGP's own outputs/mapping/releaseAndroidTest/missing_rules.txt asks for.
-dontwarn javax.lang.model.element.Modifier

# R8 renames the test classes as readily as any others, and `am instrument -e class <name>` resolves
# them by the name in the source, so a targeted run dies with `ClassNotFoundException` on a class
# that is sitting right there under another name. More importantly, optimizing the test runtime
# breaks Compose's root registry: activities launch and `setContent` runs, but assertions report
# "No compose hierarchies found" because the optimized test-side registry no longer matches the
# hooks in the app process.
#
# Keep every class packaged in the test APK, including AndroidX Test and Compose test infrastructure.
# This costs nothing worth having: shrinking *test* code is not what `-PtestRelease` measures, and
# the R8 run that matters — the app's — cannot see this file.
-dontshrink
-dontoptimize
-dontobfuscate
-keep class ** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
