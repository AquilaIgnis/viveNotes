package com.vivenotes.data

import android.content.Context

/**
 * Whether Google Play installed this copy of the app.
 *
 * Play's rules apply to the copy Play delivered, so this decides two things: the in-app rating sheet
 * is asked for only there, and card checkout through Stripe is offered everywhere but there. A copy
 * from F-Droid, the self-hosted repository, or `adb install` answers false.
 */
fun installedByGooglePlay(context: Context): Boolean = runCatching {
    context.packageManager.getInstallSourceInfo(context.packageName)
        .installingPackageName == PLAY_STORE_PACKAGE
}.getOrDefault(false)

private const val PLAY_STORE_PACKAGE = "com.android.vending"
