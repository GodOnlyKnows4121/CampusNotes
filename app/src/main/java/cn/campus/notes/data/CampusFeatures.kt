package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@Entity(tableName = "semesters")
data class Semester(@PrimaryKey val id: String = newId(), val name: String,
    val startDate: String, val weeks: Int = 20, val periods: String = DEFAULT_PERIODS) {
    fun times(): List<Pair<LocalTime, LocalTime>> = periods.lines().filter { it.isNotBlank() }.map {
        val p = it.trim().split("-"); require(p.size == 2) { "节次格式：08:00-08:45，每行一节" }
        LocalTime.parse(p[0].trim()) to LocalTime.parse(p[1].trim())
    }
    fun week(date: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(startDate), date), 7L).toInt() + 1
    companion object {
        const val DEFAULT_PERIODS = "08:00-08:45\n08:50-09:35\n10:00-10:45\n10:50-11:35\n14:00-14:45\n14:50-15:35\n16:00-16:45\n16:50-17:35\n19:00-19:45\n19:50-20:35"
    }
}
@Entity(tableName = "course_placements")
data class CoursePlacement(@PrimaryKey val courseId: String, val semesterId: String? = null,
    val startPeriod: Int = 1, val endPeriod: Int = 2, val pattern: String = "all", val customWeeks: String = "") {
    fun occurs(course: LocalCourse, week: Int): Boolean = week in course.firstWeek..course.lastWeek && when (pattern) {
        "odd" -> week % 2 == 1; "even" -> week % 2 == 0
        "custom" -> week in customWeeks.split(',').mapNotNull { it.trim().toIntOrNull() }; else -> true
    }
    companion object { fun legacy(c: LocalCourse) = CoursePlacement(c.id, startPeriod = c.slot * 2 + 1, endPeriod = c.slot * 2 + 2) }
}
@Entity(tableName = "campus_todos")
data class CampusTodo(@PrimaryKey val id: String = newId(), val title: String, val body: String = "",
    val dueAt: Long? = null, val courseId: String? = null, val done: Boolean = false, val remind: Boolean = false)
@Entity(tableName = "record_links")
data class RecordLink(@PrimaryKey val recordId: String, val courseId: String? = null, val topicId: String? = null)
@Entity(tableName = "trash_entries")
data class TrashEntry(@PrimaryKey val id: String = newId(), val kind: String, val title: String,
    val payload: String, val deletedAt: Long = System.currentTimeMillis())

@Dao
interface FeatureDao {
    @Query("SELECT * FROM semesters ORDER BY startDate DESC") fun semesters(): Flow<List<Semester>>
    @Query("SELECT * FROM semesters") suspend fun allSemesters(): List<Semester>
    @Upsert suspend fun save(value: Semester)
    @Query("SELECT * FROM course_placements") fun placements(): Flow<List<CoursePlacement>>
    @Query("SELECT * FROM course_placements") suspend fun allPlacements(): List<CoursePlacement>
    @Upsert suspend fun save(value: CoursePlacement)
    @Query("DELETE FROM course_placements WHERE courseId = :id") suspend fun deletePlacement(id: String)
    @Query("SELECT * FROM campus_todos ORDER BY done, dueAt IS NULL, dueAt, title") fun todos(): Flow<List<CampusTodo>>
    @Query("SELECT * FROM campus_todos") suspend fun allTodos(): List<CampusTodo>
    @Upsert suspend fun save(value: CampusTodo)
    @Query("DELETE FROM campus_todos WHERE id = :id") suspend fun deleteTodo(id: String)
    @Query("SELECT * FROM record_links") fun links(): Flow<List<RecordLink>>
    @Query("SELECT * FROM record_links") suspend fun allLinks(): List<RecordLink>
    @Upsert suspend fun save(value: RecordLink)
    @Query("SELECT * FROM trash_entries ORDER BY deletedAt DESC") fun trash(): Flow<List<TrashEntry>>
    @Query("SELECT * FROM trash_entries") suspend fun allTrash(): List<TrashEntry>
    @Upsert suspend fun save(value: TrashEntry)
    @Query("DELETE FROM trash_entries WHERE id = :id") suspend fun deleteTrash(id: String)
}

internal fun LibraryNode.toJson() = JSONObject().put("id", id).put("parentId", parentId)
    .put("kind", kind).put("title", title).put("rule", rule).put("localName", localName)
    .put("mime", mime).put("bytes", bytes).put("example", example)
internal fun JSONObject.nullable(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
internal fun JSONObject.toNode() = LibraryNode(getString("id"), nullable("parentId"), getString("kind"),
    getString("title"), optString("rule"), nullable("localName"), getString("mime"), getLong("bytes"), optBoolean("example"))
internal fun TrashEntry.nodes(): List<LibraryNode> = if (kind != "node") emptyList() else (if(payload.trimStart().startsWith("[")) JSONArray(payload) else JSONObject(payload).getJSONArray("nodes")).let { a ->
    (0 until a.length()).map { a.getJSONObject(it).toNode() }
}
internal fun TrashEntry.notes(): List<Note> = if(kind!="node"||payload.trimStart().startsWith("["))emptyList()else JSONObject(payload).optJSONArray("notes")?.let { a ->
    (0 until a.length()).map {val j=a.getJSONObject(it);Note(j.getString("id"),j.getString("title"),j.getString("body"),j.getLong("updatedAt"))}
}?:emptyList()
