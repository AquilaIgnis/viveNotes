package com.vivenotes

/**
 * Marks a test that passes on the tablets but flakes on the CI emulator.
 *
 * The GitHub job boots a headless `google_apis` AVD on `swiftshader_indirect`, so pixel assertions
 * sample a differently-rasterised frame, injected drags shed events under load, and a DataStore
 * write can miss a generous timeout.
 *
 * `.github/workflows/tests.yml` passes this class as the runner's `notAnnotation`, so CI skips
 * these. A local or tablet run has no such argument and still executes them.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class FlakyOnEmulator
