package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Recognition drafts stay separate from originals and from explicitly confirmed text. */
@Entity(tableName = "content_results", indices = [Index("sourceId")])
data class ContentResult(
    @PrimaryKey val id: String = newId(), val sourceId: String, val sourceStamp: String = "",
    val kind: String, val status: String = "waiting", val text: String = "", val detail: String = "",
    val provider: String = "", val outputNoteId: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ContentDao {
    @Query("SELECT * FROM content_results ORDER BY updatedAt DESC, createdAt DESC, id DESC") fun observe(): Flow<List<ContentResult>>
    @Query("SELECT * FROM content_results WHERE id = :id") suspend fun result(id: String): ContentResult?
    @Query("SELECT * FROM content_results WHERE sourceId = :id ORDER BY updatedAt DESC, createdAt DESC, id DESC") suspend fun forSource(id: String): List<ContentResult>
    @Query("SELECT * FROM content_results WHERE status = 'confirmed' ORDER BY updatedAt DESC") suspend fun confirmed(): List<ContentResult>
    @Upsert suspend fun save(value: ContentResult)
    @Query("DELETE FROM content_results WHERE sourceId IN (:ids)") suspend fun deleteSources(ids: List<String>)
    @Query("UPDATE content_results SET status = 'interrupted', detail = '应用进程退出导致处理中断，原件保留；请主动重试' WHERE status IN ('waiting','running')") suspend fun interrupt()
}
