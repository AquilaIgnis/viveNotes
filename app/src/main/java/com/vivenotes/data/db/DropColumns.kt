package com.vivenotes.data.db

import android.database.sqlite.SQLiteDatabase

/**
 * Removes [columns] from [table].
 *
 * `ALTER TABLE … DROP COLUMN` needs SQLite 3.35. Android 12 and 13 ship 3.32, and a manufacturer
 * can ship another, so the version decides, not the API level. Below 3.35 the table is rebuilt the
 * way SQLite documents for a change `ALTER TABLE` cannot make:
 * 1. Create a table from the stored definition, minus those columns.
 * 2. Copy the rows across.
 * 3. Drop the old table and rename the new one into its place.
 * 4. Recreate its indexes and triggers.
 *
 * Foreign keys are off for that, because dropping a parent table with them on deletes through every
 * `ON DELETE CASCADE` that points at it. They can only be switched off outside a transaction, so
 * this refuses to run inside one.
 */
internal fun SQLiteDatabase.dropColumns(
    table: String,
    columns: Set<String>,
    rebuild: Boolean = !canDropColumns(),
) {
    if (!rebuild) {
        columns.forEach { column -> execSQL("ALTER TABLE `$table` DROP COLUMN `$column`") }
        return
    }
    check(!inTransaction()) { "Foreign keys cannot be switched off inside a transaction" }
    val foreignKeys = longOf("PRAGMA foreign_keys") == 1L
    if (foreignKeys) execSQL("PRAGMA foreign_keys = OFF")
    try {
        beginTransaction()
        try {
            rebuildWithout(table, columns)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    } finally {
        if (foreignKeys) execSQL("PRAGMA foreign_keys = ON")
    }
}

/** Whether this SQLite has `ALTER TABLE … DROP COLUMN`, which arrived in 3.35. */
internal fun SQLiteDatabase.canDropColumns(): Boolean {
    val version = textsOf("SELECT sqlite_version()").single()
    val (major, minor) = version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0)
    return major > 3 || (major == 3 && minor >= 35)
}

private fun SQLiteDatabase.rebuildWithout(table: String, columns: Set<String>) {
    val definition = textsOf("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", table).single()
    // A UNIQUE or PRIMARY KEY index has no SQL of its own; the new table's constraints recreate it.
    val dependents = textsOf(
        "SELECT sql FROM sqlite_master WHERE type IN ('index', 'trigger') AND tbl_name = ? AND sql IS NOT NULL",
        table,
    )
    val dropped = columns.map(String::lowercase).toSet()
    val kept = rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }.filterNot { it.lowercase() in dropped }
    val rebuilt = "vive_rebuild_$table"
    execSQL("DROP TABLE IF EXISTS `$rebuilt`")
    execSQL(tableWithoutColumns(definition, rebuilt, columns))
    val list = kept.joinToString(", ") { "`$it`" }
    execSQL("INSERT INTO `$rebuilt` ($list) SELECT $list FROM `$table`")
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `$rebuilt` RENAME TO `$table`")
    dependents.forEach(::execSQL)
    val broken = rawQuery("PRAGMA foreign_key_check", null).use { it.count }
    check(broken == 0) { "Rebuilding $table left $broken row(s) pointing at nothing" }
}

/**
 * [definition], a `CREATE TABLE` as SQLite stores it, renamed to [newName] and without the column
 * definitions named in [columns].
 *
 * Everything else stays exactly as written: types, collations, defaults and table constraints. The
 * column list is split at its top-level commas, which only needs quotes, brackets and comments told
 * apart from structure. Names compare case-insensitively, as SQLite compares them. A column that is
 * not there is an error: guessing would write a table the importer rejects, or one that silently
 * keeps what was meant to go.
 */
internal fun tableWithoutColumns(definition: String, newName: String, columns: Set<String>): String {
    var open = -1
    var close = -1
    var depth = 0
    val commas = mutableListOf<Int>()
    var index = 0
    while (index < definition.length && close < 0) {
        val char = definition[index]
        when {
            char == '\'' || char == '"' || char == '`' -> index = closingQuote(definition, index)
            char == '[' -> index = definition.indexOf(']', index + 1).also { require(it >= 0) { "Unclosed [" } }
            definition.startsWith("--", index) ->
                index = definition.indexOf('\n', index).let { if (it < 0) definition.length else it }
            definition.startsWith("/*", index) ->
                index = definition.indexOf("*/", index + 2).also { require(it >= 0) { "Unclosed comment" } } + 1
            char == '(' -> {
                if (depth == 0) open = index
                depth++
            }
            char == ')' -> {
                depth--
                if (depth == 0) close = index
            }
            char == ',' && depth == 1 -> commas += index
        }
        index++
    }
    require(open >= 0 && close > open) { "Not a CREATE TABLE with a column list: $definition" }
    val items = (listOf(open) + commas + close).zipWithNext { from, to -> definition.substring(from + 1, to).trim() }
    val wanted = columns.map(String::lowercase).toSet()
    val found = mutableSetOf<String>()
    val kept = items.filter { item ->
        val name = columnNameOf(item)?.lowercase()
        if (name != null && name in wanted) {
            found += name
            false
        } else {
            true
        }
    }
    require(found == wanted) { "No column ${(wanted - found).joinToString()} in: $definition" }
    return "CREATE TABLE `$newName` (" + kept.joinToString(", ") + ")" + definition.substring(close + 1)
}

/** The index of the quote that closes the one at [start]; a doubled quote is an escaped one. */
private fun closingQuote(sql: String, start: Int): Int {
    val quote = sql[start]
    var index = start + 1
    while (index < sql.length) {
        if (sql[index] == quote) {
            if (index + 1 < sql.length && sql[index + 1] == quote) index += 2 else return index
        } else {
            index++
        }
    }
    throw IllegalArgumentException("Unclosed $quote in: $sql")
}

/** The column an item of a column list defines, or null when the item is a table constraint. */
private fun columnNameOf(item: String): String? {
    if (item.isEmpty()) return null
    return when (val first = item[0]) {
        '`', '"', '\'' -> item.substring(1, closingQuote(item, 0)).replace("$first$first", "$first")
        '[' -> item.substring(1, item.indexOf(']'))
        else -> item.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
            .takeUnless { it.uppercase() in TABLE_CONSTRAINT_KEYWORDS }
    }
}

private val TABLE_CONSTRAINT_KEYWORDS = setOf("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")

private fun SQLiteDatabase.textsOf(sql: String, vararg args: String): List<String> =
    rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

private fun SQLiteDatabase.longOf(sql: String): Long =
    rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
