package com.vivenotes.ui.account

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Opens a Stripe-hosted page — Checkout or the customer portal — in a Custom Tab over the app.
 *
 * Only https is opened. The URL comes from the managed server, and a page for typing a card into
 * has no business arriving as an `intent:` or `file:` link whatever the server says. The address bar
 * stays visible so the person can see they are on Stripe, and sharing is off: a Checkout link pays
 * for this account, and there is nobody it should be sent to.
 *
 * False when nothing on the device can show it.
 */
internal fun openStripePage(activity: Activity, url: String, toolbarColor: Int): Boolean {
    val page = url.toUri()
    if (page.scheme != "https" || page.host.isNullOrEmpty()) return false
    val customTab = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(
            CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build(),
        )
        .setShowTitle(true)
        .setUrlBarHidingEnabled(false)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build()
    return try {
        customTab.launchUrl(activity, page)
        true
    } catch (noBrowser: ActivityNotFoundException) {
        false
    }
}
