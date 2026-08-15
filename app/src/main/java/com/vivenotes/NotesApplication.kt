package com.vivenotes

import android.app.Application
import com.vivenotes.ai.AiModelStore
import com.vivenotes.ai.OnnxInkRecognitionEngine
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.DatabaseBackupManager
import com.vivenotes.data.DeletionPurgeWorker
import com.vivenotes.data.EditorDefaultsStore
import com.vivenotes.data.ImageTextIndexer
import com.vivenotes.data.InkTextIndexer
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.PenSettingsStore
import com.vivenotes.data.StarterInkPageFixture
import com.vivenotes.data.ViewSettingsStore
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.richtext.FontRegistry
import com.vivenotes.math.SympyMathEngine

/**
 * Manual dependency container.
 *
 * A DI framework would be a second annotation processor for three objects. Revisit when the app
 * is split into modules and the graph stops fitting on one screen.
 */
class NotesApplication : Application() {

    val database: NotesDatabase by lazy { NotesDatabase.create(this) }
    val databaseBackups: DatabaseBackupManager by lazy { DatabaseBackupManager(this, database) }
    val repository: NotesRepository by lazy {
        NotesRepository(
            database,
            starterInkPage = StarterInkPageFixture.load(this),
        )
    }
    val attachments: AttachmentStore by lazy { AttachmentStore(this, database) }
    val notebookTransfers: NotebookTransferManager by lazy {
        NotebookTransferManager(this, database, attachments)
    }
    val editorDefaults: EditorDefaultsStore by lazy { EditorDefaultsStore(this) }
    val viewSettings: ViewSettingsStore by lazy { ViewSettingsStore(this) }
    val penSettings: PenSettingsStore by lazy { PenSettingsStore(this) }
    val aiModels: AiModelStore by lazy { AiModelStore(this) }
    val recognitionEngine: OnnxInkRecognitionEngine by lazy { OnnxInkRecognitionEngine(aiModels) }

    /**
     * Reads pictures for the Content panel — `memory/imageOcrPlan.md`.
     *
     * Lazy like everything else here, which matters more for this one: touching it opens ONNX
     * Runtime, and an install whose owner never searches should never pay for that.
     */
    val imageText: ImageTextIndexer by lazy {
        ImageTextIndexer(repository, attachments, recognitionEngine)
    }
    val inkText: InkTextIndexer by lazy {
        InkTextIndexer(repository, recognitionEngine)
    }
    val mathEngine: SympyMathEngine by lazy { SympyMathEngine(this) }

    override fun onCreate() {
        super.onCreate()
        FontRegistry.init(this)
        DeletionPurgeWorker.schedule(this)
    }
}
