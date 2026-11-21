package com.vivenotes

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * What this device's Android can do, for the features that do less before Android 15.
 *
 * The app runs from Android 12 (API 31) but was written against 15, so a few features depend on
 * what arrived after 12. This is the list, and where each is acted on.
 *
 * **Decided here:**
 * - [meshInk]: androidx.ink draws meshes from Android 14. Before it, every stroke is drawn from its
 *   outlines, and [com.vivenotes.ink.StrokeOutlines] gives erased pieces some.
 * - [accessibilityServiceEvents]: Android 13 reports each accessibility service as it is switched.
 *   Before it, the editor hears only accessibility as a whole going on or off, and looks again on
 *   resume.
 *
 * **Decided beside the calls,** because lint only accepts them guarded where they are made:
 * - Where a PDF page's text is: Android 15's `PdfRenderer`, or `PdfRendererPreV` on Android 11 to 14
 *   with S extension 13. Without either, PDF pages come in as pictures. See
 *   [com.vivenotes.fileimport.pdf.PdfBackend].
 * - `ALTER TABLE … DROP COLUMN` depends on the SQLite version, not the API level. See
 *   [com.vivenotes.data.db.dropColumns].
 *
 * **Older, and accepted as it is:**
 * - Stylus buttons arrive as `KEYCODE_STYLUS_BUTTON_*` only from Android 14. Before it, a digitizer
 *   pen's barrel button is motion-event button state, which nothing reads. Bluetooth pens keep their
 *   own keycodes on every version.
 * - A palm the system cancels carries `FLAG_CANCELED` on its up only from Android 13. Before it, a
 *   rejected palm in a multi-touch gesture lifts like a finger.
 * - Android 13 shows its own confirmation when something is copied; Android 12 shows none.
 *
 * Lint follows each property here through `@ChecksSdkIntAtLeast`, so a call guarded by one is not
 * reported as newer than minSdk.
 */
internal object PlatformSupport {

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val meshInk: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val accessibilityServiceEvents: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
