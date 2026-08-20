package com.vivenotes.data.sync

/**
 * What moving a closed notebook to the cloud, or bringing it back, came to.
 *
 * Separate from [SyncRunResult] because the two answer different questions. A sync run either
 * converged or did not, and the screen reporting it is a status line; this is a press somebody made
 * on one notebook, and every outcome below is something to say back about *that* notebook. The
 * failures that are really sync failures carry one along rather than being flattened into a string.
 *
 * `memory/closedNotebooksPlan.md`.
 */
sealed interface CloudArchiveResult {

    /** The contents are on the server and no longer on this device. */
    data object Moved : CloudArchiveResult

    /** The contents are back, and the notebook is back on the rail. */
    data object BroughtBack : CloudArchiveResult

    /** It was already where it was being asked to go. Not an error, and not worth a message. */
    data object AlreadyDone : CloudArchiveResult

    /** No server is connected, so there is nowhere to put it and nowhere to fetch it from. */
    data object NoAccount : CloudArchiveResult

    /**
     * This device still has [pending] changes the server has not accepted, so nothing was deleted.
     *
     * The empty outbox is the whole safety argument for evicting a local copy — see
     * `HierarchySync.evictToCloud` — and a count is more use on screen than a boolean: "3 changes
     * still to upload" tells someone to wait, where "not ready" tells them nothing.
     */
    data class NotUploaded(val pending: Int) : CloudArchiveResult

    /** The notebook is gone, or was never here. */
    data object UnknownNotebook : CloudArchiveResult

    /** The transport or the local database refused. Carries the verdict the sync layer produced. */
    data class Failed(val result: SyncRunResult) : CloudArchiveResult
}
