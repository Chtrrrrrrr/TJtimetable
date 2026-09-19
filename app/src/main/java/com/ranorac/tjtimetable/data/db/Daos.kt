package com.ranorac.tjtimetable.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    @Query("SELECT * FROM terms ORDER BY year DESC, term DESC")
    fun observeAll(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrent(): Flow<TermEntity?>

    @Query("SELECT * FROM terms WHERE calendarId = :calendarId")
    suspend fun byId(calendarId: String): TermEntity?

    @Query("SELECT * FROM terms WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): TermEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: TermEntity)

    @Query("UPDATE terms SET isCurrent = (calendarId = :calendarId)")
    suspend fun markCurrent(calendarId: String)

    @Query("DELETE FROM terms WHERE calendarId = :calendarId")
    suspend fun delete(calendarId: String)
}

@Dao
interface CourseDao {

    @Transaction
    @Query("SELECT * FROM courses WHERE termId = :termId AND hidden = 0 ORDER BY name")
    fun observeVisibleForTerm(termId: String): Flow<List<CourseWithSessions>>

    @Transaction
    @Query("SELECT * FROM courses WHERE termId = :termId ORDER BY name")
    fun observeAllForTerm(termId: String): Flow<List<CourseWithSessions>>

    @Transaction
    @Query("SELECT * FROM courses WHERE termId = :termId ORDER BY name")
    suspend fun allForTerm(termId: String): List<CourseWithSessions>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun courseById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Query("UPDATE courses SET colorIndex = :colorIndex WHERE id = :id")
    suspend fun setColor(id: Long, colorIndex: Int?)

    @Query("UPDATE courses SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE courses SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("DELETE FROM courses WHERE termId = :termId")
    suspend fun deleteForTerm(termId: String)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface DayAdjustmentDao {

    @Query("SELECT * FROM day_adjustments WHERE termId = :termId ORDER BY epochDay")
    fun observeForTerm(termId: String): Flow<List<DayAdjustmentEntity>>

    @Query("SELECT * FROM day_adjustments WHERE termId = :termId ORDER BY epochDay")
    suspend fun forTerm(termId: String): List<DayAdjustmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(adjustment: DayAdjustmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(adjustments: List<DayAdjustmentEntity>)

    @Query("DELETE FROM day_adjustments WHERE termId = :termId AND epochDay = :epochDay")
    suspend fun delete(termId: String, epochDay: Long)

    @Query("DELETE FROM day_adjustments WHERE termId = :termId")
    suspend fun deleteForTerm(termId: String)

    /**
     * Clears the API-derived and inferred rows before a refresh, leaving the
     * student's own edits untouched — they are the authority for that date.
     */
    @Query("DELETE FROM day_adjustments WHERE termId = :termId AND sourceName != 'MANUAL'")
    suspend fun deleteDerivedForTerm(termId: String)

    @Query("SELECT * FROM day_adjustments WHERE termId = :termId AND sourceName = 'MANUAL'")
    suspend fun manualForTerm(termId: String): List<DayAdjustmentEntity>
}
