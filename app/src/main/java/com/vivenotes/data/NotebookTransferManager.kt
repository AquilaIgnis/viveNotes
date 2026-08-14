package com.vivenotes.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.ink.InkCodec
import com.vivenotes.model.DocumentCodecs
import com.vivenotes.model.Outline
import com.vivenotes.model.migrated
import com.vivenotes.model.newId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A successfully imported notebook copy and where the UI should navigate afterward. */
data class NotebookImportResult(
    val notebookId: String,
    val notebookName: String,
    val firstSectionId: String?,
    val firstPageId: String?,
    val created: Boolean,
    /** At least one live notebook, section, or page in the archive replaced a local tombstone. */
    val restored: Boolean,
)

data class NotebookExportResult(val notebookName: String, val byteCount: Long)

class NotebookTransferException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Reads and writes the portable `.vive` notebook container.
 *
 * Imports are deliberately two-phase. The untrusted archive is copied and completely validated in
 * a fresh private cache directory first. Only a [ValidatedBundle] can reach [install], whose Room
 * transaction is the first write to the live database. ZIP paths are never trusted as filesystem
 * paths: the tiny allowlist and canonical-child check are both applied before extraction.
 */
class NotebookTransferManager(
    private val context: Context,
    private val db: NotesDatabase,
    private val attachmentStore: AttachmentStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transferRoot: File = File(context.cacheDir, DIRECTORY),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun exportNotebook(notebookId: String, destination: Uri): NotebookExportResult =
        mutex.withLock {
            withContext(io) {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    exportNotebookLocked(notebookId, output)
                } ?: throw NotebookTransferException("The selected destination could not be opened.")
            }
        }

    suspend fun importNotebook(source: Uri): NotebookImportResult = mutex.withLock {
        withContext(io) {
            context.contentResolver.openInputStream(source)?.use { input ->
                importNotebookLocked(input)
            } ?: throw NotebookTransferException("The selected notebook file could not be opened.")
        }
    }

    /** Test seam which exercises the exact package writer without a document provider. */
    internal suspend fun exportNotebook(notebookId: String, output: OutputStream): NotebookExportResult =
        mutex.withLock { withContext(io) { exportNotebookLocked(notebookId, output) } }

    /** Test seam which still stages and validates every byte before importing. */
    internal suspend fun importNotebook(input: InputStream): NotebookImportResult =
        mutex.withLock { withContext(io) { importNotebookLocked(input) } }

    private fun exportNotebookLocked(notebookId: String, output: OutputStream): NotebookExportResult {
        val root = freshDirectory("export")
        try {
            val bundleDb = File(root, DATABASE_ENTRY)
            createConsistentSnapshot(bundleDb)
            val prepared = prepareNotebookDatabase(bundleDb, notebookId, root)

            val databaseFile = BundleFile(
                path = DATABASE_ENTRY,
                byteCount = bundleDb.length(),
                sha256 = bundleDb.sha256(),
            )
            val manifest = NotebookBundleManifest(
                bundleId = newId(),
                createdAt = clock(),
                sourceNotebookId = prepared.notebook.id,
                notebookName = prepared.notebook.name,
                database = databaseFile,
                attachments = prepared.attachments.map { staged ->
                    BundleAttachment(
                        id = staged.metadata.id,
                        path = "$ATTACHMENTS_PREFIX${staged.metadata.id}",
                        mimeType = staged.metadata.mimeType,
                        pixelWidth = staged.metadata.pixelWidth,
                        pixelHeight = staged.metadata.pixelHeight,
                        byteCount = staged.file.length(),
                        sha256 = staged.metadata.id,
                    )
                },
                counts = prepared.counts,
            )
            val manifestBytes = JSON.encodeToString(manifest).encodeToByteArray()
            require(manifestBytes.size <= MAX_MANIFEST_BYTES)

            val checksums = buildList {
                add(ManifestChecksum(MANIFEST_ENTRY, manifestBytes.sha256()))
                add(ManifestChecksum(DATABASE_ENTRY, databaseFile.sha256))
                prepared.attachments.forEach {
                    add(ManifestChecksum("$ATTACHMENTS_PREFIX${it.metadata.id}", it.metadata.id))
                }
            }.sortedBy { it.path }
            val checksumBytes = checksums.joinToString(separator = "\n", postfix = "\n") {
                "${it.sha256}  ${it.path}"
            }.encodeToByteArray()

            val archive = File(root, "notebook.vive")
            ZipOutputStream(FileOutputStream(archive).buffered()).use { zip ->
                zip.putBytes(MANIFEST_ENTRY, manifestBytes)
                zip.putFile(DATABASE_ENTRY, bundleDb, stored = false)
                prepared.attachments.forEach { staged ->
                    zip.putFile("$ATTACHMENTS_PREFIX${staged.metadata.id}", staged.file, stored = true)
                }
                zip.putBytes(CHECKSUMS_ENTRY, checksumBytes)
            }
            FileInputStream(archive).use { it.copyTo(output) }
            output.flush()
            return NotebookExportResult(prepared.notebook.name, archive.length())
        } catch (failure: NotebookTransferException) {
            throw failure
        } catch (failure: Throwable) {
            throw NotebookTransferException("The notebook could not be exported.", failure)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun importNotebookLocked(input: InputStream): NotebookImportResult {
        val root = freshDirectory("import")
        try {
            val archive = File(root, "incoming.vive")
            FileOutputStream(archive).use { output ->
                input.copyBounded(output, MAX_ARCHIVE_BYTES, "The notebook file is too large.")
            }
            val validated = validateArchive(archive, root)
            return install(validated)
        } catch (failure: NotebookTransferException) {
            throw failure
        } catch (failure: Throwable) {
            throw NotebookTransferException("This .vive file is damaged or unsafe and was not imported.", failure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createConsistentSnapshot(target: File) {
        db.openHelper.writableDatabase.compileStatement("VACUUM INTO ?").use { statement ->
            statement.bindString(1, target.absolutePath)
            statement.execute()
        }
        check(target.isFile && target.length() > 0L)
    }

    private fun prepareNotebookDatabase(
        bundleFile: File,
        notebookId: String,
        root: File,
    ): PreparedExport {
        val database = SQLiteDatabase.openDatabase(
            bundleFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        return database.use { source ->
            source.execSQL("PRAGMA foreign_keys = ON")
            val notebook = source.queryRows(
                "SELECT * FROM notebooks WHERE id = ? AND deletedAt IS NULL",
                arrayOf(notebookId),
                ::readNotebook,
            ).singleOrNull() ?: throw NotebookTransferException("The selected notebook no longer exists.")

            source.beginTransaction()
            try {
                source.execSQL("DELETE FROM notebooks WHERE id <> ?", arrayOf(notebookId))
                // Installation identity is not notebook content and must never travel to another
                // device. The notebook UUID itself remains in both `notebooks.id` and vive_bundle.
                source.execSQL("DROP TABLE IF EXISTS local_metadata")
                // Recognized picture text is a derived cache, not content — the importing device
                // rebuilds it from the pictures the bundle already carries, with whatever engine
                // that build ships. Dropped rather than carried for the reason `page_revisions` is
                // carried: a revision is something a person made, and this is something a model
                // guessed. It also keeps `EXPECTED_COLUMNS` and the bundle format unchanged, so
                // every `.vive` written before schema 15 still imports.
                source.execSQL("DROP TABLE IF EXISTS attachment_text")
                source.setTransactionSuccessful()
            } finally {
                source.endTransaction()
            }

            val currentDocs = source.queryRows("SELECT * FROM page_content", mapper = ::readContent)
            val revisions = source.queryRows("SELECT * FROM page_revisions", mapper = ::readRevision)
            val attachmentRefs = linkedMapOf<String, Int>()
            currentDocs.forEach { row ->
                decodeDocument(row.format, row.docJson.encodeToByteArray()).imageIds().forEach { id ->
                    attachmentRefs[id] = attachmentRefs.getOrDefault(id, 0) + 1
                }
            }
            revisions.forEach { row ->
                DocumentRevisionPayload.unpack(row).imageIds().forEach { id ->
                    attachmentRefs[id] = attachmentRefs.getOrDefault(id, 0) + 1
                }
            }

            val metadata = if (attachmentRefs.isEmpty()) emptyList() else source.queryRows(
                "SELECT * FROM attachments",
                mapper = ::readAttachment,
            ).filter { it.id in attachmentRefs }
            if (metadata.map { it.id }.toSet() != attachmentRefs.keys) {
                throw NotebookTransferException("The notebook references an attachment whose metadata is missing.")
            }

            val stagedAttachments = metadata.sortedBy { it.id }.map { row ->
                requireValidAttachmentMetadata(row)
                val original = attachmentStore.fileFor(row.id)
                if (!original.isFile || original.length() != row.byteCount || original.sha256() != row.id) {
                    throw NotebookTransferException("Attachment ${row.id.take(12)} is missing or damaged.")
                }
                val staged = File(root, "export-attachments/${row.id}").apply {
                    parentFile?.mkdirs()
                }
                original.copyTo(staged)
                StagedAttachment(row.copy(refCount = attachmentRefs.getValue(row.id)), staged)
            }

            source.beginTransaction()
            try {
                source.execSQL("DELETE FROM attachments")
                stagedAttachments.forEach { source.insertAttachment(it.metadata) }
                source.execSQL(
                    "CREATE TABLE vive_bundle (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                )
                source.execSQL(
                    "INSERT INTO vive_bundle VALUES ('format', ?), ('formatVersion', ?), " +
                        "('appSchemaVersion', ?), ('notebookId', ?)",
                    arrayOf(FORMAT, FORMAT_VERSION.toString(), APP_SCHEMA_VERSION.toString(), notebookId),
                )
                source.execSQL("PRAGMA application_id = $APPLICATION_ID")
                source.execSQL("PRAGMA user_version = $FORMAT_VERSION")
                source.setTransactionSuccessful()
            } finally {
                source.endTransaction()
            }
            source.execSQL("VACUUM")
            requireDatabaseOkay(source)
            PreparedExport(
                notebook = notebook,
                attachments = stagedAttachments,
                counts = BundleCounts(
                    sections = source.scalarLong("SELECT COUNT(*) FROM sections").toInt(),
                    pages = source.scalarLong("SELECT COUNT(*) FROM pages").toInt(),
                    strokes = source.scalarLong("SELECT COUNT(*) FROM ink_strokes").toInt(),
                    revisions = source.scalarLong("SELECT COUNT(*) FROM page_revisions").toInt(),
                    attachments = stagedAttachments.size,
                ),
            )
        }
    }

    private fun validateArchive(archive: File, root: File): ValidatedBundle {
        if (archive.length() !in 1..MAX_ARCHIVE_BYTES) fail("The notebook file is empty or too large.")
        val extractedRoot = File(root, "extracted").apply { mkdirs() }
        val names = linkedSetOf<String>()
        var expandedBytes = 0L
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!names.add(entry.name)) fail("The archive contains a duplicate entry.")
                if (names.size > MAX_ENTRIES) fail("The archive contains too many entries.")
                validateEntryName(entry.name)
                if (entry.isDirectory) fail("Directory entries are not allowed in a .vive file.")
                val limit = entryLimit(entry.name)
                if (entry.size > limit) fail("Archive entry ${entry.name} is too large.")
                val target = safeTarget(extractedRoot, entry.name)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    expandedBytes += zip.copyBounded(
                        output,
                        limit,
                        "Archive entry ${entry.name} expands beyond its limit.",
                    )
                }
                if (expandedBytes > MAX_EXPANDED_BYTES) fail("The archive expands beyond its limit.")
                zip.closeEntry()
            }
        }
        if (MANIFEST_ENTRY !in names || DATABASE_ENTRY !in names || CHECKSUMS_ENTRY !in names) {
            fail("The archive is missing required files.")
        }

        val manifestFile = File(extractedRoot, MANIFEST_ENTRY)
        val manifest = runCatching {
            JSON.decodeFromString<NotebookBundleManifest>(manifestFile.readText())
        }.getOrElse { fail("manifest.json is not valid ViveNotes metadata.", it) }
        validateManifest(manifest)

        val expectedNames = buildSet {
            add(MANIFEST_ENTRY)
            add(DATABASE_ENTRY)
            add(CHECKSUMS_ENTRY)
            manifest.attachments.forEach { add(it.path) }
        }
        if (names != expectedNames) fail("The archive contains missing or unexpected files.")

        val checksums = parseChecksums(File(extractedRoot, CHECKSUMS_ENTRY))
        val checksummedNames = expectedNames - CHECKSUMS_ENTRY
        if (checksums.keys != checksummedNames) fail("The checksum list does not exactly match the archive.")
        checksums.forEach { (path, expected) ->
            if (File(extractedRoot, path).sha256() != expected) fail("Checksum verification failed for $path.")
        }
        if (manifest.database.sha256 != checksums[DATABASE_ENTRY] ||
            manifest.database.byteCount != File(extractedRoot, DATABASE_ENTRY).length()
        ) fail("The database metadata does not match its file.")
        manifest.attachments.forEach { attachment ->
            val file = File(extractedRoot, attachment.path)
            if (attachment.sha256 != checksums[attachment.path] || attachment.byteCount != file.length()) {
                fail("Attachment metadata does not match ${attachment.path}.")
            }
        }

        val data = validateBundleDatabase(File(extractedRoot, DATABASE_ENTRY), manifest)
        val stagedAttachments = manifest.attachments.associate { descriptor ->
            val metadata = data.attachments.singleOrNull { it.id == descriptor.id }
                ?: fail("Attachment metadata is missing for ${descriptor.id}.")
            if (
                descriptor.path != "$ATTACHMENTS_PREFIX${descriptor.id}" ||
                descriptor.sha256 != descriptor.id ||
                descriptor.mimeType != metadata.mimeType ||
                descriptor.pixelWidth != metadata.pixelWidth ||
                descriptor.pixelHeight != metadata.pixelHeight ||
                descriptor.byteCount != metadata.byteCount
            ) fail("Attachment metadata is inconsistent for ${descriptor.id}.")
            descriptor.id to File(extractedRoot, descriptor.path)
        }
        data.attachments.forEach { metadata ->
            requireValidAttachmentMetadata(metadata)
            val file = stagedAttachments[metadata.id] ?: fail("Attachment ${metadata.id} is absent.")
            if (file.sha256() != metadata.id) fail("Attachment ${metadata.id.take(12)} is damaged.")
            val bounds = boundsOf(file) ?: fail("Attachment ${metadata.id.take(12)} is not a readable image.")
            if (bounds.width != metadata.pixelWidth || bounds.height != metadata.pixelHeight) {
                fail("Attachment ${metadata.id.take(12)} has incorrect image dimensions.")
            }
        }
        return ValidatedBundle(manifest, data, stagedAttachments)
    }

    private fun validateBundleDatabase(file: File, manifest: NotebookBundleManifest): BundleData {
        if (!file.isFile || file.length() !in 1..MAX_DATABASE_BYTES || !file.hasSQLiteHeader()) {
            fail("notebook.sqlite is not a readable SQLite database.")
        }
        val source = runCatching {
            SQLiteDatabase.openDatabase(
                file,
                SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    .build(),
            )
        }.getOrElse { fail("notebook.sqlite is not a readable SQLite database.", it) }
        return source.use { database ->
            database.rawQuery("PRAGMA query_only = ON", null).close()
            requireDatabaseOkay(database)
            if (database.scalarLong("PRAGMA application_id") != APPLICATION_ID.toLong() ||
                database.scalarLong("PRAGMA user_version") != FORMAT_VERSION.toLong()
            ) fail("The notebook database has an unsupported format.")
            validateSchema(database)
            val bundleMetadata = database.queryRows(
                "SELECT key, value FROM vive_bundle",
            ) { cursor -> cursor.getString(0) to cursor.getString(1) }.toMap()
            if (
                bundleMetadata["format"] != FORMAT ||
                bundleMetadata["formatVersion"] != FORMAT_VERSION.toString() ||
                bundleMetadata["appSchemaVersion"] != APP_SCHEMA_VERSION.toString() ||
                bundleMetadata["notebookId"] != manifest.sourceNotebookId
            ) fail("The manifest and notebook database describe different bundles.")

            validateTableCounts(database, manifest.counts)

            val notebooks = database.queryRows("SELECT * FROM notebooks", mapper = ::readNotebook)
            val sections = database.queryRows("SELECT * FROM sections", mapper = ::readSection)
            val pages = database.queryRows("SELECT * FROM pages", mapper = ::readPage)
            val contents = database.queryRows("SELECT * FROM page_content", mapper = ::readContent)
            val revisions = database.queryRows("SELECT * FROM page_revisions", mapper = ::readRevision)
            val strokes = database.queryRows("SELECT * FROM ink_strokes", mapper = ::readStroke)
            val erases = database.queryRows("SELECT * FROM ink_erases", mapper = ::readErase)
            val eraseTargets = database.queryRows(
                "SELECT eraseId, strokeId FROM ink_erase_targets",
            ) { InkEraseTargetEntity(it.getString(0), it.getString(1)) }
            val moves = database.queryRows("SELECT * FROM ink_moves", mapper = ::readMove)
            val moveTargets = database.queryRows(
                "SELECT moveId, strokeId FROM ink_move_targets",
            ) { InkMoveTargetEntity(it.getString(0), it.getString(1)) }
            val attachments = database.queryRows("SELECT * FROM attachments", mapper = ::readAttachment)

            validateCounts(manifest.counts, sections, pages, strokes, revisions, attachments)
            val notebook = notebooks.singleOrNull()
                ?: fail("A .vive file must contain exactly one notebook.")
            if (notebook.id != manifest.sourceNotebookId || notebook.name != manifest.notebookName ||
                notebook.deletedAt != null
            ) fail("The manifest notebook does not match notebook.sqlite.")
            if (sections.any { it.notebookId != notebook.id }) {
                fail("The notebook contains invalid sections.")
            }
            if (sections.any { !validId(it.id) || it.name.length > MAX_NAME_CHARS }) {
                fail("The notebook contains invalid section metadata.")
            }
            val sectionIds = sections.map { it.id }.toSet()
            if (pages.any { it.sectionId !in sectionIds }) {
                fail("The notebook contains invalid pages.")
            }
            if (pages.any {
                    !validId(it.id) || it.title.length > MAX_NAME_CHARS ||
                        it.preview.length > MAX_PREVIEW_CHARS
                }
            ) fail("The notebook contains invalid page metadata.")
            val pageIds = pages.map { it.id }.toSet()
            if (contents.size != pages.size || contents.map { it.pageId }.toSet() != pageIds) {
                fail("Every imported page must have exactly one document.")
            }
            if (strokes.any { it.pageId !in pageIds } || erases.any { it.pageId !in pageIds } ||
                moves.any { it.pageId !in pageIds } || revisions.any { it.pageId !in pageIds }
            ) fail("The notebook contains records outside its pages.")

            val decodedCurrent = contents.associate { row ->
                if (row.docJson.encodeToByteArray().size > DocumentRevisionPayload.MAX_DOCUMENT_BYTES) {
                    fail("A page document exceeds the safe import limit.")
                }
                row.pageId to decodeDocument(row.format, row.docJson.encodeToByteArray())
            }
            strokes.forEach(::validateStroke)
            erases.forEach(::validateErase)
            moves.forEach(::validateMove)

            val strokesById = strokes.associateBy { it.id }
            val erasesById = erases.associateBy { it.id }
            val movesById = moves.associateBy { it.id }
            if (eraseTargets.any { target ->
                    val erase = erasesById[target.eraseId]
                    val stroke = strokesById[target.strokeId]
                    erase == null || stroke == null || erase.pageId != stroke.pageId
                }
            ) fail("An erase target crosses a page boundary.")
            if (moveTargets.any { target ->
                    val move = movesById[target.moveId]
                    val stroke = strokesById[target.strokeId]
                    move == null || stroke == null || move.pageId != stroke.pageId
                }
            ) fail("A move target crosses a page boundary.")

            val revisionSnapshots = revisions.associate { row ->
                if (!validId(row.id) || !SHA256.matches(row.sha256) ||
                    !SHA256.matches(row.inkSha256)
                ) fail("A saved page version has invalid metadata.")
                val doc = runCatching { DocumentRevisionPayload.unpack(row) }
                    .getOrElse { fail("A saved page version is damaged.", it) }
                val snapshot = runCatching { InkRevisionPayload.unpack(row) }
                    .getOrElse { fail("A saved ink version is damaged.", it) }
                if (snapshot.strokes.any { strokesById[it.id]?.pageId != row.pageId } ||
                    snapshot.eraseIds.any { erasesById[it]?.pageId != row.pageId } ||
                    snapshot.moveIds.any { movesById[it]?.pageId != row.pageId }
                ) fail("A saved version references data outside its page.")
                row.id to ValidatedRevision(row, doc.imageIds(), snapshot)
            }

            val referencedAttachments = buildSet {
                decodedCurrent.values.forEach { addAll(it.imageIds()) }
                revisionSnapshots.values.forEach { addAll(it.imageIds) }
            }
            if (attachments.map { it.id }.toSet() != referencedAttachments ||
                manifest.attachments.map { it.id }.toSet() != referencedAttachments
            ) fail("The attachment list does not exactly match the notebook documents.")

            BundleData(
                notebook,
                sections.sortedBy { it.sortIndex },
                pages.sortedWith(compareBy<PageEntity> { it.sectionId }.thenBy { it.sortIndex }),
                contents,
                revisions,
                revisionSnapshots,
                strokes,
                erases,
                eraseTargets,
                moves,
                moveTargets,
                attachments,
            )
        }
    }

    private suspend fun install(bundle: ValidatedBundle): NotebookImportResult {
        val data = bundle.data
        val notebookId = data.notebook.id
        val existingRows = auditLiveCollisions(data)
        val existingNotebook = existingRows.notebook
        val localSections = db.sectionDao().allInNotebook(notebookId)
        val localPages = db.pageDao().allInNotebook(notebookId)
        val localRevisions = loadChunked(
            data.pages.map { it.id },
            db.pageRevisionDao()::byPageIds,
        )
        // Import is an explicit restore boundary, not an implicit background sync. Every row the
        // chosen archive carries is authoritative for its stable id, even when the device has a
        // newer edit or tombstone. Local-only rows absent from the archive are tombstoned so the
        // visible notebook is the saved state, not a union with whatever happened after export.
        val restoringNotebook = existingNotebook?.deletedAt != null
        val restoringSectionIds = data.sections.mapNotNullTo(mutableSetOf()) { incoming ->
            incoming.id.takeIf {
                incoming.deletedAt == null && existingRows.sections[incoming.id]?.deletedAt != null
            }
        }
        val restoringPageIds = data.pages.mapNotNullTo(mutableSetOf()) { incoming ->
            incoming.id.takeIf {
                incoming.deletedAt == null && existingRows.pages[incoming.id]?.deletedAt != null
            }
        }
        val restored = restoringNotebook || restoringSectionIds.isNotEmpty() || restoringPageIds.isNotEmpty()
        val importedAt = clock()

        /** A restored archive state is a new mutation and must supersede the state it replaces. */
        fun importedTimestamp(incoming: Long, existing: Long): Long = maxOf(
            importedAt,
            incoming,
            if (existing == Long.MAX_VALUE) Long.MAX_VALUE else existing + 1L,
        )

        val installedFiles = mutableListOf<File>()
        try {
            bundle.attachmentFiles.forEach { (id, staged) ->
                val target = attachmentStore.fileFor(id)
                if (target.exists()) {
                    if (!target.isFile || target.length() != staged.length() || target.sha256() != id) {
                        fail("An existing attachment with the same identity is damaged.")
                    }
                } else {
                    val pending = File(target.parentFile, "$id.${UUID.randomUUID()}.import")
                    staged.copyTo(pending)
                    if (pending.sha256() != id) {
                        pending.delete()
                        fail("An attachment changed while it was being installed.")
                    }
                    moveAtomically(pending, target)
                    installedFiles += target
                }
            }

            db.withTransaction {
                val attachmentDao = db.attachmentDao()
                data.attachments.forEach { metadata ->
                    if (metadata.id !in existingRows.attachments) {
                        attachmentDao.insert(metadata.copy(refCount = 0))
                    }
                }

                val notebookDao = db.notebookDao()
                if (existingNotebook == null) {
                    // Recheck inside the write transaction so an edit which clears the marker can
                    // never race an import and lose a real notebook.
                    val replaceableStarter = replaceableStarterNotebook()
                    replaceableStarter?.let { notebookDao.hardDeletePlaceholder(it.id) }
                    notebookDao.upsert(
                        data.notebook.copy(
                            sortIndex = replaceableStarter?.sortIndex ?: notebookDao.nextSortIndex(),
                            expanded = true,
                        ),
                    )
                } else if (!existingNotebook.sameImportedStateAs(data.notebook)) {
                    // Expansion and top-level ordering are this device's navigation state.
                    notebookDao.upsert(
                        data.notebook.copy(
                            sortIndex = existingNotebook.sortIndex,
                            expanded = if (restoringNotebook) true else existingNotebook.expanded,
                            updatedAt = importedTimestamp(
                                data.notebook.updatedAt,
                                existingNotebook.updatedAt,
                            ),
                        ),
                    )
                }
                db.localMetadataDao().delete(NotesRepository.REPLACEABLE_STARTER_KEY)

                val sectionDao = db.sectionDao()
                val importedSectionIds = data.sections.mapTo(hashSetOf()) { it.id }
                data.sections.forEach { row ->
                    val existing = existingRows.sections[row.id]
                    if (existing == null) {
                        sectionDao.upsert(row)
                    } else if (!existing.sameImportedStateAs(row)) {
                        sectionDao.upsert(
                            row.copy(updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt)),
                        )
                    }
                }
                localSections.filter { it.id !in importedSectionIds && it.deletedAt == null }
                    .forEach { sectionDao.softDelete(it.id, importedAt) }

                val pageDao = db.pageDao()
                val importedPageIds = data.pages.mapTo(hashSetOf()) { it.id }
                data.pages.forEach { row ->
                    val existing = existingRows.pages[row.id]
                    if (existing == null) {
                        pageDao.upsert(row)
                    } else if (!existing.sameImportedStateAs(row)) {
                        pageDao.upsert(
                            row.copy(updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt)),
                        )
                    }
                }
                localPages.filter { it.id !in importedPageIds && it.deletedAt == null }
                    .forEach { pageDao.softDelete(it.id, importedAt) }

                val attachmentDeltas = linkedMapOf<String, Int>()
                val contentDao = db.pageContentDao()
                data.contents.forEach { row ->
                    val existing = existingRows.contents[row.pageId]
                    if (existing == null || !existing.sameImportedStateAs(row)) {
                        existing?.safeImageIds().orEmpty().forEach { id ->
                            attachmentDeltas[id] = attachmentDeltas.getOrDefault(id, 0) - 1
                        }
                        row.safeImageIds().forEach { id ->
                            attachmentDeltas[id] = attachmentDeltas.getOrDefault(id, 0) + 1
                        }
                        contentDao.upsert(
                            if (existing == null) row else row.copy(
                                updatedAt = importedTimestamp(row.updatedAt, existing.updatedAt),
                            ),
                        )
                    }
                }

                // Reset every archived page's active ink first. Upserting every carried operation
                // below then reconstructs exactly the archive state; local-only operations stay as
                // tombstoned history rather than leaking onto the restored canvas.
                data.pages.forEach { page ->
                    db.inkStrokeDao().softDeletePage(page.id, importedAt)
                    db.inkEraseDao().softDeletePage(page.id, importedAt)
                    db.inkMoveDao().softDeletePage(page.id, importedAt)
                }
                if (data.strokes.isNotEmpty()) db.inkStrokeDao().upsert(data.strokes)
                data.erases.forEach { db.inkEraseDao().upsert(it) }
                data.erases.map { it.id }.chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                    db.inkEraseDao().deleteTargetsForErases(ids)
                }
                if (data.eraseTargets.isNotEmpty()) {
                    db.inkEraseDao().insertTargetsIfAbsent(data.eraseTargets)
                }
                data.moves.forEach { db.inkMoveDao().upsert(it) }
                data.moves.map { it.id }.chunked(SQLITE_BIND_CHUNK).forEach { ids ->
                    db.inkMoveDao().deleteTargetsForMoves(ids)
                }
                if (data.moveTargets.isNotEmpty()) {
                    db.inkMoveDao().insertTargetsIfAbsent(data.moveTargets)
                }

                val revisionDao = db.pageRevisionDao()
                val importedRevisionIds = data.revisions.mapTo(hashSetOf()) { it.id }
                localRevisions.map { it.id }.filterNot(importedRevisionIds::contains)
                    .chunked(SQLITE_BIND_CHUNK)
                    .forEach { revisionDao.deleteByIds(it) }
                val newRevisions = data.revisions.filter { it.id !in existingRows.revisions }
                newRevisions.forEach { revisionDao.insertIfAbsent(it) }
                newRevisions.map { it.pageId }.distinct().forEach { pageId ->
                    revisionDao.trimToNewest(pageId, NotesRepository.MAX_REVISIONS_PER_PAGE)
                }

                attachmentDeltas.forEach { (id, delta) ->
                    repeat(delta.coerceAtLeast(0)) { attachmentDao.retain(id) }
                    repeat((-delta).coerceAtLeast(0)) { attachmentDao.release(id) }
                }
            }
        } catch (failure: Throwable) {
            installedFiles.forEach(File::delete)
            throw failure
        }

        val firstSection = db.sectionDao().inNotebook(notebookId).firstOrNull()?.id
        val firstPage = firstSection?.let { sectionId ->
            db.pageDao().inNotebook(notebookId).firstOrNull { it.sectionId == sectionId }?.id
        }
        val finalNotebook = db.notebookDao().byId(notebookId) ?: error("synced notebook disappeared")
        return NotebookImportResult(
            notebookId,
            finalNotebook.name,
            firstSection,
            firstPage,
            created = existingNotebook == null,
            restored = restored,
        )
    }

    /**
     * A clean install creates one starter so the UI is usable before a restore. Its UUID is marked
     * locally after seeding and the marker is cleared on the first content mutation. Only that
     * exact, sole, still-live generated notebook can be replaced by a restored stable UUID.
     */
    private suspend fun replaceableStarterNotebook(): NotebookEntity? {
        val metadata = db.localMetadataDao()
        val starterId = metadata.value(NotesRepository.REPLACEABLE_STARTER_KEY) ?: return null
        if (db.notebookDao().count() != 1) return null
        return db.notebookDao().byId(starterId)?.takeIf { it.deletedAt == null }
    }

    /** Stable ids enable sync, so an id may only meet the same immutable object in the live DB. */
    private suspend fun auditLiveCollisions(data: BundleData): ExistingBundleRows {
        val notebookId = data.notebook.id
        val sections = loadChunked(data.sections.map { it.id }, db.sectionDao()::byIds)
            .associateBy { it.id }
        val pages = loadChunked(data.pages.map { it.id }, db.pageDao()::byIds)
            .associateBy { it.id }
        val contents = loadChunked(data.contents.map { it.pageId }, db.pageContentDao()::byIds)
            .associateBy { it.pageId }
        val strokes = loadChunked(data.strokes.map { it.id }, db.inkStrokeDao()::byIds)
            .associateBy { it.id }
        val erases = loadChunked(data.erases.map { it.id }, db.inkEraseDao()::byIds)
            .associateBy { it.id }
        val moves = loadChunked(data.moves.map { it.id }, db.inkMoveDao()::byIds)
            .associateBy { it.id }
        val revisions = loadChunked(data.revisions.map { it.id }, db.pageRevisionDao()::byGlobalIds)
            .associateBy { it.id }
        val attachments = loadChunked(data.attachments.map { it.id }, db.attachmentDao()::byIds)
            .associateBy { it.id }
        val eraseTargets = loadChunked(
            data.erases.map { it.id },
            db.inkEraseDao()::targetsForErases,
        ).toSet()
        val moveTargets = loadChunked(
            data.moves.map { it.id },
            db.inkMoveDao()::targetsForMoves,
        ).toSet()
        data.sections.forEach { incoming ->
            sections[incoming.id]?.let { existing ->
                if (existing.notebookId != notebookId) fail("A section id belongs to another notebook.")
            }
        }
        data.pages.forEach { incoming ->
            pages[incoming.id]?.let { existing ->
                if (existing.sectionId != incoming.sectionId) fail("A page id belongs to another section.")
            }
        }
        data.strokes.forEach { incoming ->
            strokes[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail("An ink id has conflicting geometry.")
            }
        }
        data.erases.forEach { incoming ->
            erases[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail("An erase id has conflicting geometry.")
            }
        }
        data.moves.forEach { incoming ->
            moves[incoming.id]?.let { existing ->
                if (!existing.sameImmutableInkAs(incoming)) fail("A move id has conflicting geometry.")
            }
        }
        data.revisions.forEach { incoming ->
            revisions[incoming.id]?.let { existing ->
                if (existing != incoming) fail("A version id has conflicting content.")
            }
        }
        data.attachments.forEach { incoming ->
            attachments[incoming.id]?.let { existing ->
                if (existing.mimeType != incoming.mimeType ||
                    existing.pixelWidth != incoming.pixelWidth ||
                    existing.pixelHeight != incoming.pixelHeight ||
                    existing.byteCount != incoming.byteCount
                ) fail("An attachment id has conflicting metadata.")
            }
        }
        return ExistingBundleRows(
            notebook = db.notebookDao().byId(notebookId),
            sections = sections,
            pages = pages,
            contents = contents,
            strokes = strokes,
            erases = erases,
            eraseTargets = eraseTargets,
            moves = moves,
            moveTargets = moveTargets,
            revisions = revisions,
            attachments = attachments,
        )
    }

    private suspend fun <T> loadChunked(
        ids: List<String>,
        load: suspend (List<String>) -> List<T>,
    ): List<T> = buildList {
        ids.chunked(SQLITE_BIND_CHUNK).forEach { addAll(load(it)) }
    }

    private fun validateSchema(database: SQLiteDatabase) {
        val objects = database.queryRows(
            "SELECT type, name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
        ) { Triple(it.getString(0), it.getString(1), it.getString(2)) }
        if (objects.any { it.first == "view" || it.first == "trigger" }) {
            fail("Views and triggers are not allowed in a notebook bundle.")
        }
        val tables = objects.filter { it.first == "table" }.associate { it.second to it.third }
        if (tables.keys != EXPECTED_COLUMNS.keys || tables.values.any { sql ->
                !sql.trimStart().startsWith("CREATE TABLE", ignoreCase = true)
            }
        ) fail("The notebook database schema is not recognized.")
        EXPECTED_COLUMNS.forEach { (table, expected) ->
            val actual = database.queryRows("PRAGMA table_info(`$table`)") { it.getString(1) }
            // SQLite preserves ALTER TABLE insertion order, while a fresh Room v13 database puts
            // colorFollowsTheme alongside the entity field. Both are the same schema to callers.
            if (actual.size != expected.size || actual.toSet() != expected.toSet()) {
                fail("The $table table has an unexpected schema.")
            }
        }
    }

    /** Reject resource-exhaustion databases before CursorWindow materializes their rows or blobs. */
    private fun validateTableCounts(database: SQLiteDatabase, expected: BundleCounts) {
        fun count(table: String) = database.scalarLong("SELECT COUNT(*) FROM `$table`")
        val notebooks = count("notebooks")
        val sections = count("sections")
        val pages = count("pages")
        val contents = count("page_content")
        val revisions = count("page_revisions")
        val strokes = count("ink_strokes")
        val erases = count("ink_erases")
        val eraseTargets = count("ink_erase_targets")
        val moves = count("ink_moves")
        val moveTargets = count("ink_move_targets")
        val attachments = count("attachments")
        if (notebooks != 1L || sections != expected.sections.toLong() ||
            pages != expected.pages.toLong() || contents != pages ||
            revisions != expected.revisions.toLong() || strokes != expected.strokes.toLong() ||
            attachments != expected.attachments.toLong()
        ) fail("The manifest record counts do not match notebook.sqlite.")
        if (sections > MAX_SECTIONS || pages > MAX_PAGES || revisions > MAX_REVISIONS ||
            strokes > MAX_STROKES || erases > MAX_INK_OPERATIONS || moves > MAX_INK_OPERATIONS ||
            eraseTargets > MAX_INK_TARGETS || moveTargets > MAX_INK_TARGETS ||
            attachments > MAX_ATTACHMENTS
        ) fail("The notebook database exceeds safe import limits.")
        val perPage = database.scalarLong(
            "SELECT COALESCE(MAX(revisionCount), 0) FROM (" +
                "SELECT COUNT(*) AS revisionCount FROM page_revisions GROUP BY pageId)",
        )
        if (perPage > NotesRepository.MAX_REVISIONS_PER_PAGE) {
            fail("A page contains too many saved versions.")
        }
    }

    private fun validateManifest(manifest: NotebookBundleManifest) {
        if (manifest.format != FORMAT || manifest.formatVersion != FORMAT_VERSION ||
            manifest.appSchemaVersion != APP_SCHEMA_VERSION
        ) fail("This .vive format is not supported by this version of ViveNotes.")
        if (!UUIDISH.matches(manifest.bundleId) || !UUIDISH.matches(manifest.sourceNotebookId)) {
            fail("The manifest contains invalid identifiers.")
        }
        if (manifest.notebookName.isBlank() || manifest.notebookName.length > MAX_NAME_CHARS) {
            fail("The manifest contains an invalid notebook name.")
        }
        if (manifest.database.path != DATABASE_ENTRY ||
            manifest.database.byteCount !in 1..MAX_DATABASE_BYTES ||
            !SHA256.matches(manifest.database.sha256)
        ) fail("The manifest contains invalid database metadata.")
        if (manifest.attachments.size > MAX_ATTACHMENTS ||
            manifest.attachments.map { it.id }.toSet().size != manifest.attachments.size
        ) fail("The manifest contains too many or duplicate attachments.")
        manifest.attachments.forEach { attachment ->
            if (!SHA256.matches(attachment.id) || attachment.sha256 != attachment.id ||
                attachment.path != "$ATTACHMENTS_PREFIX${attachment.id}" ||
                attachment.byteCount !in 1..MAX_ATTACHMENT_BYTES ||
                attachment.pixelWidth !in 1..AttachmentStore.MAX_DIMENSION ||
                attachment.pixelHeight !in 1..AttachmentStore.MAX_DIMENSION ||
                attachment.mimeType !in ALLOWED_IMAGE_TYPES
            ) fail("The manifest contains invalid attachment metadata.")
        }
        val counts = manifest.counts
        if (counts.sections !in 0..MAX_SECTIONS || counts.pages !in 0..MAX_PAGES ||
            counts.strokes !in 0..MAX_STROKES || counts.revisions !in 0..MAX_REVISIONS ||
            counts.attachments != manifest.attachments.size
        ) fail("The manifest declares unsafe record counts.")
    }

    private fun validateCounts(
        expected: BundleCounts,
        sections: List<SectionEntity>,
        pages: List<PageEntity>,
        strokes: List<InkStrokeEntity>,
        revisions: List<PageRevisionEntity>,
        attachments: List<AttachmentEntity>,
    ) {
        if (
            sections.size != expected.sections || pages.size != expected.pages ||
            strokes.size != expected.strokes || revisions.size != expected.revisions ||
            attachments.size != expected.attachments
        ) fail("The manifest record counts do not match notebook.sqlite.")
        if (sections.size > MAX_SECTIONS || pages.size > MAX_PAGES || strokes.size > MAX_STROKES ||
            revisions.size > MAX_REVISIONS || attachments.size > MAX_ATTACHMENTS
        ) fail("The notebook database exceeds safe import limits.")
    }

    private fun validateStroke(row: InkStrokeEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            row.brushFamily.length > MAX_FORMAT_CHARS || (row.groupId?.length ?: 0) > MAX_ID_CHARS ||
            !row.sizeDp.isFinite() || row.sizeDp <= 0f || row.sizeDp > 1_000f ||
            !row.epsilon.isFinite() || row.epsilon <= 0f || row.epsilon > 100f ||
            row.stabilization !in 0..5 || row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            row.enc != InkCodec.ENCODING || !InkCodec.hasValidInputData(row.points) ||
            listOf(row.minX, row.minY, row.maxX, row.maxY).any { !it.isFinite() } ||
            row.minX > row.maxX || row.minY > row.maxY
        ) fail("The notebook contains invalid ink data.")
    }

    private fun validateErase(row: InkEraseEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            !row.sizeDp.isFinite() || row.sizeDp <= 0f || row.sizeDp > 1_000f ||
            row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            row.enc != InkCodec.ENCODING || !InkCodec.hasValidInputData(row.points)
        ) fail("The notebook contains invalid erase data.")
    }

    private fun validateMove(row: InkMoveEntity) {
        if (!validId(row.id) || !validId(row.pageId) ||
            row.points.isEmpty() || row.points.size > MAX_POINT_BYTES ||
            listOf(row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX, row.anchorY)
                .any { !it.isFinite() } ||
            InkCodec.decodeMove(row) == null
        ) fail("The notebook contains invalid move data.")
    }

    private fun requireValidAttachmentMetadata(row: AttachmentEntity) {
        if (!SHA256.matches(row.id) || row.mimeType !in ALLOWED_IMAGE_TYPES ||
            row.pixelWidth !in 1..AttachmentStore.MAX_DIMENSION ||
            row.pixelHeight !in 1..AttachmentStore.MAX_DIMENSION ||
            row.byteCount !in 1..MAX_ATTACHMENT_BYTES
        ) fail("The notebook contains invalid attachment metadata.")
    }

    private fun requireDatabaseOkay(database: SQLiteDatabase) {
        val quick = database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
        }
        if (!quick) fail("SQLite integrity verification failed.")
        val foreignKeyProblem = database.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
        if (foreignKeyProblem) fail("The notebook database contains broken relationships.")
    }

    private fun parseChecksums(file: File): Map<String, String> {
        if (!file.isFile || file.length() > MAX_CHECKSUM_BYTES) fail("The checksum list is invalid.")
        val result = linkedMapOf<String, String>()
        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            val match = CHECKSUM_LINE.matchEntire(line) ?: fail("The checksum list is malformed.")
            val (hash, path) = match.destructured
            validateEntryName(path)
            if (path == CHECKSUMS_ENTRY || result.put(path, hash) != null) {
                fail("The checksum list contains a duplicate or self-reference.")
            }
        }
        return result
    }

    private fun validateEntryName(name: String) {
        if (name.isBlank() || name.length > MAX_ENTRY_NAME_CHARS || '\u0000' in name ||
            '\\' in name || name.startsWith('/') || name.split('/').any { it == ".." || it.isBlank() } ||
            (name != MANIFEST_ENTRY && name != DATABASE_ENTRY && name != CHECKSUMS_ENTRY &&
                !ATTACHMENT_PATH.matches(name))
        ) fail("The archive contains an unsafe path.")
    }

    private fun entryLimit(name: String): Long = when (name) {
        MANIFEST_ENTRY -> MAX_MANIFEST_BYTES.toLong()
        DATABASE_ENTRY -> MAX_DATABASE_BYTES
        CHECKSUMS_ENTRY -> MAX_CHECKSUM_BYTES
        else -> MAX_ATTACHMENT_BYTES
    }

    private fun safeTarget(root: File, name: String): File {
        val target = File(root, name)
        val prefix = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(prefix)) fail("The archive contains an unsafe path.")
        return target
    }

    private fun freshDirectory(kind: String): File {
        transferRoot.mkdirs()
        if (!transferRoot.isDirectory) fail("The private transfer directory is unavailable.")
        return File(transferRoot, "$kind-${UUID.randomUUID()}").also { directory ->
            if (!directory.mkdir()) fail("A private staging directory could not be created.")
        }
    }

    private fun decodeDocument(format: String, bytes: ByteArray) =
        format.takeIf { it.length <= MAX_FORMAT_CHARS }
            ?.let(DocumentCodecs::byId)
            ?.let { codec ->
            runCatching { codec.decode(bytes).migrated() }.getOrNull()
        } ?: fail("The notebook uses an unsupported document format.")

    private fun com.vivenotes.model.PageDoc.imageIds(): List<String> =
        outlines.filterIsInstance<Outline.Image>().map { it.attachmentId }

    private fun PageContentEntity.safeImageIds(): List<String> = runCatching {
        decodeDocument(format, docJson.encodeToByteArray()).imageIds()
    }.getOrDefault(emptyList())

    private fun InkStrokeEntity.sameImmutableInkAs(other: InkStrokeEntity): Boolean =
        id == other.id && pageId == other.pageId && seq == other.seq &&
            brushFamily == other.brushFamily && brushVersion == other.brushVersion &&
            sizeDp == other.sizeDp && epsilon == other.epsilon &&
            stabilization == other.stabilization && minX == other.minX && minY == other.minY &&
            maxX == other.maxX && maxY == other.maxY && points.contentEquals(other.points) &&
            enc == other.enc && createdAt == other.createdAt

    private fun InkStrokeEntity.sameInkStateAs(other: InkStrokeEntity): Boolean =
        sameImmutableInkAs(other) && colorArgb == other.colorArgb &&
            colorFollowsTheme == other.colorFollowsTheme && groupId == other.groupId &&
            deletedAt == other.deletedAt

    private fun InkEraseEntity.sameImmutableInkAs(other: InkEraseEntity): Boolean =
        id == other.id && pageId == other.pageId && mode == other.mode && sizeDp == other.sizeDp &&
            points.contentEquals(other.points) && enc == other.enc && createdAt == other.createdAt

    private fun InkEraseEntity.sameInkStateAs(other: InkEraseEntity): Boolean =
        sameImmutableInkAs(other) && deletedAt == other.deletedAt

    private fun InkMoveEntity.sameImmutableInkAs(other: InkMoveEntity): Boolean =
        id == other.id && pageId == other.pageId && dxDp == other.dxDp && dyDp == other.dyDp &&
            scaleX == other.scaleX && scaleY == other.scaleY && anchorX == other.anchorX &&
            anchorY == other.anchorY && points.contentEquals(other.points) && enc == other.enc &&
            createdAt == other.createdAt

    private fun InkMoveEntity.sameInkStateAs(other: InkMoveEntity): Boolean =
        sameImmutableInkAs(other) && deletedAt == other.deletedAt

    private fun validId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_ID_CHARS && '\u0000' !in value

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw NotebookTransferException(message, cause)

    companion object {
        const val MIME_TYPE = "application/vnd.vivenotes.notebook+zip"
        const val EXTENSION = ".vive"
        const val FORMAT = "com.vivenotes.notebook"
        const val FORMAT_VERSION = 1
        const val APP_SCHEMA_VERSION = 13

        /**
         * Providers do not consistently retain a custom MIME type. Android's Downloads provider,
         * for example, exposes a newly created `.vive` document as `application/octet-stream`.
         * ZIP is included for providers which inspect the container. Document-provider display
         * names are not authoritative, so the complete staged bundle is validated after selection.
         */
        fun importMimeTypes(): Array<String> = arrayOf(
            MIME_TYPE,
            "application/octet-stream",
            "application/zip",
        )

        private const val DIRECTORY = "notebook_transfers"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATABASE_ENTRY = "notebook.sqlite"
        private const val CHECKSUMS_ENTRY = "checksums.sha256"
        private const val ATTACHMENTS_PREFIX = "attachments/"
        private const val APPLICATION_ID = 0x56495645
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_CHECKSUM_BYTES = 512 * 1024L
        private const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024L
        private const val MAX_EXPANDED_BYTES = 768 * 1024 * 1024L
        private const val MAX_DATABASE_BYTES = 384 * 1024 * 1024L
        private const val MAX_ATTACHMENT_BYTES = 32 * 1024 * 1024L
        private const val MAX_POINT_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTACHMENTS = 2_048
        private const val MAX_ENTRIES = MAX_ATTACHMENTS + 3
        private const val MAX_SECTIONS = 2_048
        private const val MAX_PAGES = 20_000
        private const val MAX_STROKES = 500_000
        private const val MAX_INK_OPERATIONS = 500_000L
        private const val MAX_INK_TARGETS = 2_000_000L
        private const val MAX_REVISIONS = MAX_PAGES * NotesRepository.MAX_REVISIONS_PER_PAGE
        private const val MAX_NAME_CHARS = 512
        private const val MAX_PREVIEW_CHARS = 4_096
        private const val MAX_FORMAT_CHARS = 128
        private const val MAX_ID_CHARS = 512
        private const val SQLITE_BIND_CHUNK = 500
        private const val MAX_ENTRY_NAME_CHARS = 160
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val UUIDISH = Regex("[0-9a-fA-F-]{20,64}")
        private val ATTACHMENT_PATH = Regex("attachments/[0-9a-f]{64}")
        private val CHECKSUM_LINE = Regex("([0-9a-f]{64})  ([^\\r\\n]+)")
        private val ALLOWED_IMAGE_TYPES = setOf("image/webp", "image/jpeg")

        private val EXPECTED_COLUMNS = linkedMapOf(
            "android_metadata" to listOf("locale"),
            "attachments" to listOf(
                "id", "mimeType", "pixelWidth", "pixelHeight", "byteCount", "refCount", "createdAt",
            ),
            "ink_erase_targets" to listOf("eraseId", "strokeId"),
            "ink_erases" to listOf(
                "id", "pageId", "mode", "sizeDp", "points", "enc", "createdAt", "deletedAt",
            ),
            "ink_move_targets" to listOf("moveId", "strokeId"),
            "ink_moves" to listOf(
                "id", "pageId", "dxDp", "dyDp", "scaleX", "scaleY", "anchorX", "anchorY",
                "points", "enc", "createdAt", "deletedAt",
            ),
            "ink_strokes" to listOf(
                "id", "pageId", "seq", "brushFamily", "brushVersion", "sizeDp", "colorArgb",
                "epsilon", "stabilization", "minX", "minY", "maxX", "maxY", "points", "enc",
                "createdAt", "groupId", "deletedAt", "colorFollowsTheme",
            ),
            "notebooks" to listOf(
                "id", "name", "colorArgb", "sortIndex", "expanded", "createdAt", "updatedAt", "deletedAt",
            ),
            "page_content" to listOf("pageId", "docJson", "updatedAt", "format"),
            "page_revisions" to listOf(
                "id", "pageId", "createdAt", "format", "encoding", "byteCount", "sha256", "payload",
                "inkFormat", "inkEncoding", "inkByteCount", "inkSha256", "inkPayload",
            ),
            "pages" to listOf(
                "id", "sectionId", "title", "sortIndex", "preview", "createdAt", "updatedAt", "deletedAt",
            ),
            "room_master_table" to listOf("id", "identity_hash"),
            "sections" to listOf(
                "id", "notebookId", "name", "colorArgb", "sortIndex", "createdAt", "updatedAt", "deletedAt",
            ),
            "vive_bundle" to listOf("key", "value"),
        )
    }
}

@Serializable
internal data class NotebookBundleManifest(
    val format: String = NotebookTransferManager.FORMAT,
    val formatVersion: Int = NotebookTransferManager.FORMAT_VERSION,
    val appSchemaVersion: Int = NotebookTransferManager.APP_SCHEMA_VERSION,
    val bundleId: String,
    val createdAt: Long,
    val sourceNotebookId: String,
    val notebookName: String,
    val database: BundleFile,
    val attachments: List<BundleAttachment>,
    val counts: BundleCounts,
)

@Serializable
internal data class BundleFile(val path: String, val byteCount: Long, val sha256: String)

@Serializable
internal data class BundleAttachment(
    val id: String,
    val path: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val byteCount: Long,
    val sha256: String,
)

@Serializable
internal data class BundleCounts(
    val sections: Int,
    val pages: Int,
    val strokes: Int,
    val revisions: Int,
    val attachments: Int,
)

private data class ManifestChecksum(val path: String, val sha256: String)
private data class StagedAttachment(val metadata: AttachmentEntity, val file: File)
private data class PreparedExport(
    val notebook: NotebookEntity,
    val attachments: List<StagedAttachment>,
    val counts: BundleCounts,
)

private data class ValidatedRevision(
    val row: PageRevisionEntity,
    val imageIds: List<String>,
    val ink: InkSnapshot,
)

private data class BundleData(
    val notebook: NotebookEntity,
    val sections: List<SectionEntity>,
    val pages: List<PageEntity>,
    val contents: List<PageContentEntity>,
    val revisions: List<PageRevisionEntity>,
    val revisionSnapshots: Map<String, ValidatedRevision>,
    val strokes: List<InkStrokeEntity>,
    val erases: List<InkEraseEntity>,
    val eraseTargets: List<InkEraseTargetEntity>,
    val moves: List<InkMoveEntity>,
    val moveTargets: List<InkMoveTargetEntity>,
    val attachments: List<AttachmentEntity>,
)

private data class ValidatedBundle(
    val manifest: NotebookBundleManifest,
    val data: BundleData,
    val attachmentFiles: Map<String, File>,
)

private data class ExistingBundleRows(
    val notebook: NotebookEntity?,
    val sections: Map<String, SectionEntity>,
    val pages: Map<String, PageEntity>,
    val contents: Map<String, PageContentEntity>,
    val strokes: Map<String, InkStrokeEntity>,
    val erases: Map<String, InkEraseEntity>,
    val eraseTargets: Set<InkEraseTargetEntity>,
    val moves: Map<String, InkMoveEntity>,
    val moveTargets: Set<InkMoveTargetEntity>,
    val revisions: Map<String, PageRevisionEntity>,
    val attachments: Map<String, AttachmentEntity>,
)

/** Import owns archive content, while top-level order/expansion remain device navigation state. */
private fun NotebookEntity.sameImportedStateAs(other: NotebookEntity): Boolean =
    copy(sortIndex = other.sortIndex, expanded = other.expanded, updatedAt = other.updatedAt) == other

private fun SectionEntity.sameImportedStateAs(other: SectionEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private fun PageEntity.sameImportedStateAs(other: PageEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private fun PageContentEntity.sameImportedStateAs(other: PageContentEntity): Boolean =
    copy(updatedAt = other.updatedAt) == other

private val JSON = Json { encodeDefaults = true }

private fun InputStream.copyBounded(output: OutputStream, limit: Long, message: String): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw NotebookTransferException(message)
        output.write(buffer, 0, read)
    }
    return total
}

private fun ZipOutputStream.putBytes(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.putFile(path: String, file: File, stored: Boolean) {
    val entry = ZipEntry(path)
    if (stored) {
        entry.method = ZipEntry.STORED
        entry.size = file.length()
        entry.compressedSize = file.length()
        entry.crc = CRC32().also { crc ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                }
            }
        }.value
    }
    putNextEntry(entry)
    FileInputStream(file).use { it.copyTo(this) }
    closeEntry()
}

private fun File.sha256(): String = FileInputStream(this).use(InputStream::sha256)
private fun File.hasSQLiteHeader(): Boolean = FileInputStream(this).use { input ->
    val header = ByteArray(SQLITE_HEADER.size)
    input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
}
private fun ByteArray.sha256(): String = ByteArrayInputStream(this).sha256()
private fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private inline fun <T> SQLiteDatabase.queryRows(
    sql: String,
    args: Array<String> = emptyArray(),
    mapper: (Cursor) -> T,
): List<T> = rawQuery(sql, args).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(mapper(cursor))
    }
}

private fun SQLiteDatabase.scalarLong(sql: String): Long =
    rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.float(name: String): Float = getFloat(getColumnIndexOrThrow(name))
private fun Cursor.blob(name: String): ByteArray = getBlob(getColumnIndexOrThrow(name))
private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { index ->
    if (isNull(index)) null else getLong(index)
}
private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { index ->
    if (isNull(index)) null else getString(index)
}
private fun Cursor.nullableBoolean(name: String): Boolean? = getColumnIndexOrThrow(name).let { index ->
    if (isNull(index)) null else getInt(index) != 0
}

private fun readNotebook(it: Cursor) = NotebookEntity(
    it.string("id"), it.string("name"), it.int("colorArgb"), it.int("sortIndex"),
    it.int("expanded") != 0, it.long("createdAt"), it.long("updatedAt"), it.nullableLong("deletedAt"),
)
private fun readSection(it: Cursor) = SectionEntity(
    it.string("id"), it.string("notebookId"), it.string("name"), it.int("colorArgb"),
    it.int("sortIndex"), it.long("createdAt"), it.long("updatedAt"), it.nullableLong("deletedAt"),
)
private fun readPage(it: Cursor) = PageEntity(
    it.string("id"), it.string("sectionId"), it.string("title"), it.int("sortIndex"),
    it.string("preview"), it.long("createdAt"), it.long("updatedAt"), it.nullableLong("deletedAt"),
)
private fun readContent(it: Cursor) = PageContentEntity(
    it.string("pageId"), it.string("docJson"), it.long("updatedAt"), it.string("format"),
)
private fun readRevision(it: Cursor) = PageRevisionEntity(
    it.string("id"), it.string("pageId"), it.long("createdAt"), it.string("format"),
    it.string("encoding"), it.int("byteCount"), it.string("sha256"), it.blob("payload"),
    it.string("inkFormat"), it.string("inkEncoding"), it.int("inkByteCount"),
    it.string("inkSha256"), it.blob("inkPayload"),
)
private fun readStroke(it: Cursor) = InkStrokeEntity(
    id = it.string("id"), pageId = it.string("pageId"), seq = it.int("seq"),
    brushFamily = it.string("brushFamily"), brushVersion = it.int("brushVersion"),
    sizeDp = it.float("sizeDp"), colorArgb = it.int("colorArgb"),
    colorFollowsTheme = it.nullableBoolean("colorFollowsTheme"), epsilon = it.float("epsilon"),
    stabilization = it.int("stabilization"), minX = it.float("minX"), minY = it.float("minY"),
    maxX = it.float("maxX"), maxY = it.float("maxY"), points = it.blob("points"),
    enc = it.string("enc"), createdAt = it.long("createdAt"), groupId = it.nullableString("groupId"),
    deletedAt = it.nullableLong("deletedAt"),
)
private fun readErase(it: Cursor) = InkEraseEntity(
    it.string("id"), it.string("pageId"), EraserMode.valueOf(it.string("mode")),
    it.float("sizeDp"), it.blob("points"), it.string("enc"), it.long("createdAt"),
    it.nullableLong("deletedAt"),
)
private fun readMove(it: Cursor) = InkMoveEntity(
    it.string("id"), it.string("pageId"), it.float("dxDp"), it.float("dyDp"),
    it.float("scaleX"), it.float("scaleY"), it.float("anchorX"), it.float("anchorY"),
    it.blob("points"), it.string("enc"), it.long("createdAt"), it.nullableLong("deletedAt"),
)
private fun readAttachment(it: Cursor) = AttachmentEntity(
    it.string("id"), it.string("mimeType"), it.int("pixelWidth"), it.int("pixelHeight"),
    it.long("byteCount"), it.int("refCount"), it.long("createdAt"),
)

private fun SQLiteDatabase.insertAttachment(row: AttachmentEntity) {
    insertOrThrow("attachments", null, ContentValues().apply {
        put("id", row.id)
        put("mimeType", row.mimeType)
        put("pixelWidth", row.pixelWidth)
        put("pixelHeight", row.pixelHeight)
        put("byteCount", row.byteCount)
        put("refCount", row.refCount)
        put("createdAt", row.createdAt)
    })
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
