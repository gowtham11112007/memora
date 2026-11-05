package com.pulse.app.data

import androidx.room.ColumnInfo
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
    /** The last 4 digits of the bank's reference, used to spot the same payment from another app. */
    val reference: String? = null,
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
    /** A Repeat name: NONE, DAILY, WEEKLY or MONTHLY. */
    @ColumnInfo(defaultValue = "NONE") val repeat: String = "NONE",
)

/** A person the user starred or muted, matched against the name a notification shows. */
@Entity(tableName = "contact_rule")
data class ContactRule(
    /** Lowercase, for matching. */
    @PrimaryKey val name: String,
    val label: String,
    /** True means priority. False means muted, so their messages are skipped. */
    val starred: Boolean,
)

/** One line per notification from an allowed app. Never holds the message. Deleted after 7 days. */
@Entity(tableName = "activity", indices = [Index("at")])
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    /** The masked sender or title. Blank for anything sensitive. */
    val title: String,
    /** A CaptureDecision name. */
    val outcome: String,
    val at: Long,
)

/** A to-do. An unconfirmed one was suggested from a message and is waiting for a yes. */
@Entity(tableName = "todo", indices = [Index("createdAt")])
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val done: Boolean = false,
    val confirmed: Boolean = true,
    val createdAt: Long,
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
