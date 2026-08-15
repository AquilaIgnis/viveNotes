package com.vivenotes.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.R
import com.vivenotes.ui.icons.MaterialSymbols

/** Tags for [AboutDialog]. It is reachable from one place only — the Settings tab. */
internal object AboutTags {
    const val DIALOG = "about-dialog"
    const val VERSION = "about-version"
    const val SOURCE = "about-source"
    const val DONATE = "about-donate"
    const val CLOSE = "about-close"
}

/** Where the project lives, and where it is supported. Both are shown as well as opened. */
private const val SOURCE_URL = "https://github.com/AquilaIgnis/viveNotes"
private const val DONATE_URL = "https://buymeacoffee.com/acidburn"

/**
 * What this app is, which build of it this is, and where to find or support it.
 *
 * **A dialog rather than a [com.vivenotes.ui.panel.ToolPane].** Everything in the right-docked panes
 * is something you keep open while working on the page beside it — history, paper size, search. This
 * is read once and dismissed, and it is about the *app* rather than about the page, so it takes the
 * screen for a moment instead of taking a third of it for the rest of the session.
 *
 * **The launcher icon is the header**, at the size a store listing would show it, because that is the
 * one image a user already associates with this app. It replaces `AlertDialog`'s `icon` and `title`
 * slots rather than sitting above them — a Material icon *and* the app's own would be two logos in a
 * row, and the wordmark under the icon reads as one thing.
 *
 * **The two actions are the same pill at different weights.** Source is tonal in the scheme's greys
 * and the donation is filled in the app's orange — `tertiary`, the same colour the math actions wear,
 * so the loudest thing in this window is still a colour the app already uses. Each address is printed
 * under its button:
 * a tap that opens a browser is the happy path, but a device without one should still leave the user
 * something they can read and type out somewhere else.
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    // The adaptive icon's foreground layer, which is the whole artwork on
                    // transparency — `R.mipmap.ic_launcher` is an `<adaptive-icon>` XML that
                    // `painterResource` cannot inflate.
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
                Text(
                    text = "ViveNotes",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = rememberAppVersion(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(AboutTags.VERSION),
                )
                Text(
                    text = "An open-source OneNote alternative, " +
                            "licensed under Source First License 1.1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )

                FilledTonalButton(
                    onClick = { context.openLink(SOURCE_URL) },
                    // The scheme's own greys rather than the tonal default: `secondaryContainer` is
                    // one of the few slots `ViveNotesTheme` leaves to Material, and its violet reads
                    // as a colour from another app beside this palette's azure and neutrals.
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .testTag(AboutTags.SOURCE),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_github),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "View on GitHub",
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Address(SOURCE_URL)

                Button(
                    onClick = { context.openLink(DONATE_URL) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(AboutTags.DONATE),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.LocalCafe,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Donate to project",
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Address(DONATE_URL)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(AboutTags.CLOSE),
            ) { Text("Close") }
        },
        modifier = Modifier.testTag(AboutTags.DIALOG),
    )
}

/** The address under a button, quiet enough to be a caption and complete enough to be typed out. */
@Composable
private fun Address(url: String) {
    Text(
        text = url.removePrefix("https://"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * `Version 1.0`, read off the installed package rather than out of `BuildConfig`.
 *
 * `buildFeatures.buildConfig` is off in this module, and turning it on to print one string would
 * generate a class for the whole build for the sake of a dialog. The package manager already knows,
 * and what it reports is the build that is actually installed.
 *
 * The name only: `versionCode` was shown beside it in brackets for a while and says nothing to a
 * reader while it is still 1. If releases ever start bumping it, that is when it earns its place
 * back — a bug report against "1.0" is ambiguous once four builds have been called that.
 *
 * Read through the *application* context: under instrumentation the activity belongs to the test
 * APK, and the version this has to show is the app's.
 */
@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "Version ${info.versionName}"
        }.getOrDefault("Version unknown")
    }
}

/**
 * Hands an address to whatever handles it, and shrugs when nothing does.
 *
 * `runCatching` rather than checking first: a device with no browser installed — an AOSP emulator
 * image, say — should cost the tap and nothing else, and the address stays on screen underneath for
 * someone to read off.
 */
private fun Context.openLink(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
