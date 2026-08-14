package com.vivenotes.data

/** The hierarchy row represented by one entry in the app-wide Deleted Items pane. */
enum class DeletedItemKind {
    Notebook,
    Section,
    Page,
}

/** Stable identity carried by a transient Undo action without retaining a database projection. */
data class DeletedItemKey(
    val id: String,
    val kind: DeletedItemKind,
)

/**
 * One recoverable hierarchy root.
 *
 * A deleted child is deliberately absent while one of its ancestors is deleted: the ancestor is
 * the action that made that whole branch unreachable. Restoring it reveals any older child
 * tombstones as independent entries instead of silently clearing them too.
 */
data class DeletedItem(
    val key: DeletedItemKey,
    val name: String,
    val notebookName: String? = null,
    val sectionName: String? = null,
    val deletedAt: Long,
    val sectionCount: Int = 0,
    val pageCount: Int = 0,
)

