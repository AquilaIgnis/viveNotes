package com.vivenotes

/**
 * Marks a test that passes on the tablets but flakes on the CI emulator.
 *
 * The GitHub job boots a headless `google_apis` AVD on `swiftshader_indirect`, so pixel assertions
 * sample a differently-rasterised frame, injected drags shed events under load, and a real DataStore
 * write can miss a generous timeout. Those are properties of that emulator, not of the code under
 * test, and a suite that goes red at random teaches nobody to read it.
 *
 * `.github/workflows/tests.yml` passes this class to the runner's `notAnnotation` argument, so CI
 * skips these and everything else stays enforced. A plain local or tablet run has no such argument
 * and still executes them — this excuses a test from one machine, it does not retire it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class FlakyOnEmulator
