package com.vivenotes.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Makes a small local safety net around the live Room database.
 *
 * A plain copy of `notes.db` is not sufficient while Room is in WAL mode. `VACUUM INTO` asks SQLite
 * itself for a transactionally consistent, compact copy. The result is validated before its
 * `.pending` suffix is removed, so a process or power failure cannot masquerade as a usable backup.
 * These files live under [Context.getNoBackupFilesDir]: Android Auto Backup already carries the live
 * database, and uploading seven local copies would burn through its quota without adding versions.
 */
class DatabaseBackupManager(
    context: Context,
    private val database: NotesDatabase,
    private val directory: File = File(context.noBackupFilesDir, DIRECTORY),
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = BACKUP_INTERVAL_MS,
    private val maxBackups: Int = MAX_BACKUPS,
) {
    private val mutex = Mutex()

    /** Returns the new file, or null when a recent backup already covers this interval. */
    suspend fun createIfDue(force: Boolean = false): File? = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(maxBackups > 0) { "maxBackups must be positive" }
            directory.mkdirs()
            check(directory.isDirectory) { "cannot create backup directory $directory" }
            directory.listFiles { file -> file.name.endsWith(PENDING_SUFFIX) }
                .orEmpty()
                .forEach(File::delete)

            val now = clock()
            val existing = backupFilesNewestFirst()
            if (!force && existing.firstOrNull()?.let { now - it.lastModified() < intervalMs } == true) {
                return@withContext null
            }

            val stem = "notes-$now-${newId()}"
            val pending = File(directory, "$stem$PENDING_SUFFIX")
            val finished = File(directory, "$stem.db")
            try {
                database.openHelper.writableDatabase.compileStatement("VACUUM INTO ?").use { statement ->
                    statement.bindString(1, pending.absolutePath)
                    statement.execute()
                }
                check(isValid(pending)) { "SQLite produced an invalid backup at $pending" }
                moveAtomically(pending, finished)
                finished.setLastModified(now)
                backupFilesNewestFirst().drop(maxBackups).forEach(File::delete)
                finished
            } catch (failure: Throwable) {
                pending.delete()
                throw failure
            }
        }
    }

    /** Valid snapshots, newest first. Intended for a recovery screen or diagnostic export. */
    suspend fun validBackups(): List<File> = mutex.withLock {
        withContext(Dispatchers.IO) { backupFilesNewestFirst().filter(::isValid) }
    }

    private fun backupFilesNewestFirst(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith("notes-") && file.extension == "db" }
            .orEmpty()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    private fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return false
        return db.use {
            runCatching {
                it.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }.getOrDefault(false)
        }
    }

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DIRECTORY = "database_backups"
        const val BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val MAX_BACKUPS = 7
        private const val PENDING_SUFFIX = ".db.pending"
    }
}
