# App-side keep rules used ONLY by `-PtestRelease` (see the `testRelease` KDoc in build.gradle.kts).
# They are not part of the shipping APK.
#
# Two rules, deliberately blunt, because neither is part of the shipping APK.
#
# An instrumented test APK is not self-contained. It shares a classloader with the app and AGP
# excludes from it every dependency the app already carries, so test code links against the *app's*
# copy of Room, Compose, coroutines, kotlin-stdlib and androidx.ink. R8 shrinks those to what the
# app needs, which is not what the tests need, and the difference surfaces at runtime as
# `NoClassDefFoundError` / `NoSuchMethodError` on library classes that exist in neither APK. A first
# run without this rule failed 108+ of the suite that way: 45 on `androidx.room.Room`, 35 on
# `androidx.compose.runtime.Composer`, 23 on `kotlinx.coroutines`, 8 on
# `androidx.ink.brush.StockBrushes`. None of them said anything about the app.
#
# Enumerating library survivors one crash at a time is a losing game, so the first rule keeps those
# libraries whole **except ONNX Runtime**. Its native bridge is exactly the shipping JNI boundary the
# saved-page formula regression must protect, so it is governed only by `src/main/keepRules` here as
# it is in production. The second rule keeps app classes and their original member descriptors
# because the app R8 invocation cannot see references made from the separately compiled test APK.
# Without it, R8 correctly removes test-only constructors, companion fields and helper methods; the
# test APK then reports hundreds of `NoSuchMethodError` and `NoSuchFieldError` failures which say
# nothing about production. `allowoptimization` and `allowobfuscation` still exercise optimized app
# method bodies and the mapping hand-off between the two R8 invocations. Shrinking is covered
# separately by installing and cold-launching the ordinary shipping release, which does not read
# this file.
#
# **What this therefore cannot catch:** removal of app entry points reached only by the test APK, or
# a fault in a library's shrunk form. The ordinary shipping release smoke run covers process startup,
# reflection and library shrinking; this variant covers the full behavioural suite against optimized
# and obfuscated app code.
-keep class !com.vivenotes.**,!ai.onnxruntime.**,** { *; }
-keep,allowoptimization,allowobfuscation class com.vivenotes.** { *; }
