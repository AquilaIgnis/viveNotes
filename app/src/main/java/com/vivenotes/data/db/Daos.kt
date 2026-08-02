package com.vivenotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    @Transaction
    @Query("SELECT * FROM notebooks WHERE deletedAt IS NULL ORDER BY sortIndex")
    fun observeTree(): Flow<List<NotebookWithSections>>

    @Query("SELECT * FROM notebooks WHERE deletedAt IS NULL ORDER BY sortIndex")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE notebooks SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: String, expanded: Boolean)

    @Query("UPDATE notebooks SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM notebooks")
    suspend fun nextSortIndex(): Int

    @Query("SELECT COUNT(*) FROM notebooks WHERE deletedAt IS NULL")
    suspend fun count(): Int
}

@Dao
interface SectionDao {

    @Query("SELECT * FROM sections WHERE notebookId = :notebookId AND deletedAt IS NULL ORDER BY sortIndex")
    fun observeIn(notebookId: String): Flow<List<SectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(section: SectionEntity)

    @Query("UPDATE sections SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE sections SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM sections WHERE notebookId = :notebookId")
    suspend fun nextSortIndex(notebookId: String): Int

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun byId(id: String): SectionEntity?
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE sectionId = :sectionId AND deletedAt IS NULL ORDER BY sortIndex")
    fun observeIn(sectionId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<PageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity)

    @Query("UPDATE pages SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE pages SET preview = :preview, updatedAt = :now WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String, now: Long)

    @Query("UPDATE pages SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) + 1 FROM pages WHERE sectionId = :sectionId")
    suspend fun nextSortIndex(sectionId: String): Int

    @Query("SELECT * FROM pages WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR preview LIKE '%' || :query || '%') ORDER BY updatedAt DESC LIMIT 50")
    fun search(query: String): Flow<List<PageEntity>>
}

@Dao
interface PageContentDao {

    @Query("SELECT * FROM page_content WHERE pageId = :pageId")
    suspend fun byId(pageId: String): PageContentEntity?

    @Upsert
    suspend fun upsert(content: PageContentEntity)
}

@Dao
interface InkStrokeDao {

    /** A page's live ink, in draw order. Tombstones are excluded, never removed. */
    @Query("SELECT * FROM ink_strokes WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY seq")
    suspend fun byPage(pageId: String): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: InkStrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(strokes: List<InkStrokeEntity>)

    @Query("UPDATE ink_strokes SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    @Query("UPDATE ink_strokes SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>)

    @Query("UPDATE ink_strokes SET colorArgb = :colorArgb WHERE id = :id")
    suspend fun setColor(id: String, colorArgb: Int)

    @Query("UPDATE ink_strokes SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: String, groupId: String?)

    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM ink_strokes WHERE pageId = :pageId")
    suspend fun nextSeq(pageId: String): Int
}

@Dao
interface InkEraseDao {

    @Transaction
    @Query("SELECT * FROM ink_erases WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun byPage(pageId: String): List<InkEraseWithTargets>

    @Insert
    suspend fun insert(erase: InkEraseEntity)

    @Insert
    suspend fun insertTargets(targets: List<InkEraseTargetEntity>)

    @Query("UPDATE ink_erases SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)
}

@Dao
interface InkMoveDao {

    @Transaction
    @Query("SELECT * FROM ink_moves WHERE pageId = :pageId AND deletedAt IS NULL ORDER BY createdAt, id")
    suspend fun byPage(pageId: String): List<InkMoveWithTargets>

    @Insert
    suspend fun insert(move: InkMoveEntity)

    @Insert
    suspend fun insertTargets(targets: List<InkMoveTargetEntity>)

    @Query("UPDATE ink_moves SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)
}
