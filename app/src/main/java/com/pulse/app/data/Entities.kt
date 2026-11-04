package com.pulse.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pulse.app.domain.RecordType

/** A money or reminder message, already masked. Deleted after 24 h (see Retention). */
@Entity(tableName = "raw_event")
data class RawEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    /** When the source app posted it. */
    val postedAt: Long,
    /** When Memora stored it. Retention is measured from here. */
    val capturedAt: Long,
)

/** One row per app the user has ever toggled. Missing row = not allowed. */
@Entity(tableName = "app_permission")
data class AppPermission(
    @PrimaryKey val packageName: String,
    val label: String,
    val allowed: Boolean,
)

/** The structured fact we keep long-term. It holds no raw message text. */
@Entity(tableName = "record", indices = [Index("occurredAt")])
data class MoneyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The source app, or [MANUAL] if the user typed it in. */
    val packageName: String,
    val amountPaise: Long,
    val merchant: String?,
    val type: RecordType,
    val occurredAt: Long,
) {
    companion object { const val MANUAL = "manual" }
}

/** Counts messages dropped as sensitive. Only the time is stored, never the content or the app. */
@Entity(tableName = "drop_log")
data class DropLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val droppedAt: Long,
)

/** The category a user chose for a merchant. Merchant is stored lowercase. */
@Entity(tableName = "merchant_rule")
data class MerchantRule(
    @PrimaryKey val merchant: String,
    val category: String,
)

/** A reminder. Confidence is 1.0 if the user added it. Unconfirmed ones are not scheduled. */
@Entity(tableName = "task", indices = [Index("dueAt")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueAt: Long,
    val confidence: Double,
    val confirmed: Boolean,
    val done: Boolean = false,
    /** The source app, or [MoneyRecord.MANUAL]. */
    val source: String,
)

/** Something to remember: typed by the user, or a short masked line from a useful notification. */
@Entity(tableName = "note", indices = [Index("createdAt")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** A NoteKind name. */
    val kind: String,
    val createdAt: Long,
    val pinned: Boolean = false,
    /** The source app, or [MoneyRecord.MANUAL]. */
    val source: String,
)

/** When an allowed app last posted anything. Time and count only, so the user can see reading works. */
@Entity(tableName = "app_seen")
data class AppSeen(
    @PrimaryKey val packageName: String,
    val lastSeenAt: Long,
    val seenCount: Long,
)
