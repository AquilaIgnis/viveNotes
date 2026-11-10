// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so their KGP (`kotlin` in the catalog) lands on the root classpath and
    // outranks the older KGP that AGP's built-in Kotlin depends on. Applied only in :app,
    // they would lose to it: child projects load the root's classes first.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.chaquopy.python) apply false
}
