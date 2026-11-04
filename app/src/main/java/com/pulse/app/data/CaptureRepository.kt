package com.pulse.app.data

import androidx.room.withTransaction
import com.pulse.app.domain.BrainClassifier
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.domain.CaptureGate
import com.pulse.app.domain.Deadline
import com.pulse.app.domain.DeadlineExtractor
import com.pulse.app.domain.ExpenseExtractor
import com.pulse.app.domain.Extraction
import com.pulse.app.domain.NoteKind
import com.pulse.app.domain.PrivacyFilter
import com.pulse.app.domain.Retention
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** SMS apps also carry personal chats, so they get a stricter check before anything is kept. */
val MESSAGING_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
)

/**
 * The single door every notification goes through (real or simulated).
 * Order: allow-list, then privacy, then organise. Only useful messages are kept, only masked
 * text reaches the disk, and everything here runs on the phone.
 */
class CaptureRepository(
    private val db: PulseDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onReminderDetected: (Reminder) -> Unit = {},
    private val onBalanceSeen: (Long, Long) -> Unit = { _, _ -> },
    private val isPaused: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(pkg: String, title: String?, text: String?, postedAt: Long): CaptureDecision {
        if (isPaused()) return CaptureDecision.PAUSED
        val allowed = db.appPermissionDao().isAllowed(pkg) == true
        val decision = CaptureGate.decide(allowed, title, text)
        val t = now()
        if (allowed) {
            db.appSeenDao().addIfMissing(pkg, t)
            db.appSeenDao().bump(pkg, t)
        }
        return when (decision) {
            CaptureDecision.DROPPED_SENSITIVE -> {
                db.dropLogDao().insert(DropLog(droppedAt = t))
                decision
            }
            CaptureDecision.STORE -> store(pkg, title.orEmpty().trim(), text.orEmpty().trim(), postedAt, t)
            else -> decision
        }
    }

    private suspend fun store(pkg: String, title: String, text: String, postedAt: Long, t: Long): CaptureDecision {
        val strict = pkg in MESSAGING_PACKAGES
        if (!strict || ExpenseExtractor.looksLikeBankAlert(text)) {
            ExpenseExtractor.findBalance(text)?.let { onBalanceSeen(it, t) }
        }
        val money = ExpenseExtractor.extract(title, text)?.takeIf { !strict || ExpenseExtractor.looksLikeBankAlert(text) }
        val deadline = extractDeadline(title, text, t)?.takeIf { !strict || DeadlineExtractor.looksLikeBill(text) }
        // A personal chat must never become a note, so SMS needs a business-style sender.
        val kind = if (money != null) null else BrainClassifier.classify("$title. $text")
            ?.takeIf { !strict || BrainClassifier.looksLikeBusiness(title) }
        if (money == null && deadline == null && kind == null) return CaptureDecision.NOTHING_USEFUL

        val merchant = money?.merchant?.let(PrivacyFilter::mask)
        if (money != null && isDuplicate(pkg, money, merchant, postedAt)) return CaptureDecision.DUPLICATE

        val reminder = db.withTransaction {
            db.rawEventDao().insert(
                RawEvent(
                    packageName = pkg,
                    title = PrivacyFilter.mask(title),
                    text = PrivacyFilter.mask(text),
                    postedAt = postedAt,
                    capturedAt = t,
                ),
            )
            if (money != null) {
                db.moneyRecordDao().insert(
                    MoneyRecord(
                        packageName = pkg,
                        amountPaise = money.amountPaise,
                        merchant = merchant,
                        type = money.type,
                        occurredAt = postedAt,
                    ),
                )
            }
            if (kind != null) saveNote(text, kind, pkg, t)
            db.rawEventDao().deleteOlderThan(t - Retention.RAW_EVENT_MS)
            deadline?.let { saveReminder(it, pkg) }
        }
        reminder?.let(onReminderDetected)
        return CaptureDecision.STORE
    }

    private suspend fun saveNote(text: String, kind: NoteKind, pkg: String, t: Long) {
        val line = PrivacyFilter.mask(BrainClassifier.shorten(text))
        if (db.noteDao().countSame(line) == 0) {
            db.noteDao().insert(Note(text = line, kind = kind.name, createdAt = t, source = pkg))
        }
    }

    private fun extractDeadline(title: String, text: String, t: Long): Deadline? {
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        return DeadlineExtractor.extract(title, text, localNow)
    }

    /** Detected reminders start unconfirmed: Memora asks first, and schedules only on a yes. */
    private suspend fun saveReminder(deadline: Deadline, pkg: String): Reminder? {
        val title = PrivacyFilter.mask(deadline.title)
        val dueAt = deadline.dueAt.atZone(zone).toInstant().toEpochMilli()
        if (db.reminderDao().countSame(title, dueAt) > 0) return null
        val reminder = Reminder(
            title = title,
            dueAt = dueAt,
            confidence = deadline.confidence,
            confirmed = false,
            source = pkg,
        )
        return reminder.copy(id = db.reminderDao().insert(reminder))
    }

    private suspend fun isDuplicate(pkg: String, money: Extraction, merchant: String?, postedAt: Long): Boolean =
        db.moneyRecordDao().countSimilar(
            pkg,
            money.amountPaise,
            merchant,
            postedAt - DUPLICATE_WINDOW_MS,
            postedAt + DUPLICATE_WINDOW_MS,
        ) > 0

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2L * 60 * 1000
    }
}
