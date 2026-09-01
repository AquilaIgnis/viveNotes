# App-side keep rules used ONLY by `-PtestRelease` (see the `testRelease` KDoc in build.gradle.kts).
# They are not part of the shipping APK.

-keep class !com.vivenotes.**,!ai.onnxruntime.**,** { *; }
-keep,allowoptimization,allowobfuscation class com.vivenotes.** { *; }
