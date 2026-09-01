# Keep rules for the **instrumented test APK only**, wired in as `testProguardFiles`.
-dontwarn javax.lang.model.element.Modifier

# the R8 run that matters — the app's — cannot see this file.
-dontshrink
-dontoptimize
-dontobfuscate
-keep class ** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
