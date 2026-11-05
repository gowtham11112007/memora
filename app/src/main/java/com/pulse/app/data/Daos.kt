package com.pulse.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.pulse.app.domain.RecordType
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {
    @Insert
    suspend fun insert(event: RawEvent): Long

    @Query("SELECT * FROM raw_event ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RawEvent>>

    @Query("DELETE FROM raw_event WHERE capturedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface MoneyRecordDao {
    @Insert
    suspend fun insert(record: MoneyRecord): Long

    @Delete
    suspend fun delete(record: MoneyRecord)

    @Query("SELECT * FROM record ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<MoneyRecord>>

    @Query("SELECT * FROM record ORDER BY occurredAt DESC")
    suspend fun all(): List<MoneyRecord>

    @Update
    suspend fun update(record: MoneyRecord)

    @Query("SELECT * FROM record WHERE amountPaise = :amount AND type = :type AND occurredAt BETWEEN :from AND :until ORDER BY occurredAt")
    suspend fun sameAmount(amount: Long, type: RecordType, from: Long, until: Long): List<MoneyRecord>
}

@Dao
interface DropLogDao {
    @Insert
    suspend fun insert(entry: DropLog)

    @Query("SELECT COUNT(*) FROM drop_log WHERE droppedAt >= :since")
    fun observeCountSince(since: Long): Flow<Int>
}

@Dao
interface AppPermissionDao {
    @Upsert
    suspend fun upsert(permission: AppPermission)

    @Query("SELECT * FROM app_permission")
    fun observeAll(): Flow<List<AppPermission>>

    @Query("SELECT allowed FROM app_permission WHERE packageName = :pkg")
    suspend fun isAllowed(pkg: String): Boolean?

    @Query("SELECT packageName FROM app_permission WHERE allowed = 1 LIMIT 1")
    suspend fun firstAllowedPackage(): String?
}

@Dao
interface MerchantRuleDao {
    @Upsert
    suspend fun upsert(rule: MerchantRule)

    @Query("SELECT * FROM merchant_rule")
    fun observeAll(): Flow<List<MerchantRule>>

    @Query("SELECT * FROM merchant_rule")
    suspend fun all(): List<MerchantRule>
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM task ORDER BY dueAt")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM task ORDER BY dueAt")
    suspend fun all(): List<Reminder>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    @Query("SELECT COUNT(*) FROM task WHERE title = :title AND dueAt = :dueAt")
    suspend fun countSame(title: String, dueAt: Long): Int
}

@Dao
interface ContactRuleDao {
    @Upsert
    suspend fun upsert(rule: ContactRule)

    @Delete
    suspend fun delete(rule: ContactRule)

    @Query("SELECT * FROM contact_rule ORDER BY starred DESC, label")
    fun observeAll(): Flow<List<ContactRule>>

    @Query("SELECT * FROM contact_rule")
    suspend fun all(): List<ContactRule>
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(entry: ActivityEntry)

    @Query("SELECT * FROM activity ORDER BY at DESC, id DESC LIMIT 200")
    fun observeRecent(): Flow<List<ActivityEntry>>

    @Query("DELETE FROM activity WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(todo: Todo): Long

    @Update
    suspend fun update(todo: Todo)

    @Delete
    suspend fun delete(todo: Todo)

    @Query("SELECT * FROM todo ORDER BY done, createdAt DESC")
    fun observeAll(): Flow<List<Todo>>

    @Query("SELECT * FROM todo ORDER BY done, createdAt DESC")
    suspend fun all(): List<Todo>

    @Query("SELECT * FROM todo WHERE id = :id")
    suspend fun byId(id: Long): Todo?

    @Query("SELECT COUNT(*) FROM todo WHERE text = :text")
    suspend fun countSame(text: String): Int
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("SELECT * FROM note ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM note ORDER BY pinned DESC, createdAt DESC")
    suspend fun all(): List<Note>

    @Query("SELECT COUNT(*) FROM note WHERE text = :text")
    suspend fun countSame(text: String): Int
}

@Dao
interface AppSeenDao {
    @Query("INSERT OR IGNORE INTO app_seen (packageName, lastSeenAt, seenCount) VALUES (:pkg, :at, 0)")
    suspend fun addIfMissing(pkg: String, at: Long)

    @Query("UPDATE app_seen SET lastSeenAt = :at, seenCount = seenCount + 1 WHERE packageName = :pkg")
    suspend fun bump(pkg: String, at: Long)

    @Query("SELECT * FROM app_seen")
    fun observeAll(): Flow<List<AppSeen>>
}
