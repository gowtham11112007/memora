package com.pulse.app.data

import androidx.room.withTransaction
import com.pulse.app.domain.BrainClassifier
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.domain.CaptureGate
import com.pulse.app.domain.ContactMatcher
import com.pulse.app.domain.Deadline
import com.pulse.app.domain.DeadlineExtractor
import com.pulse.app.domain.ExpenseExtractor
import com.pulse.app.domain.Extraction
import com.pulse.app.domain.NoteKind
import com.pulse.app.domain.PaymentMatch
import com.pulse.app.domain.PrivacyFilter
import com.pulse.app.domain.Retention
import com.pulse.app.domain.TaskPredictor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** SMS apps also carry personal chats, so they get a stricter check before anything is kept. */
val MESSAGING_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
)

/** Chat apps. A friend saying "I paid 500" isn't your expense, so money needs a real bank alert. */
val CHAT_PACKAGES = setOf(
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.telegram.messenger",
    "com.instagram.android",
)

/** Apps where a notification's title is a person, so star and mute rules apply. */
fun isPersonalApp(pkg: String) = pkg in MESSAGING_PACKAGES || pkg in CHAT_PACKAGES

/**
 * The single door every notification goes through (real or simulated).
 * Order: allow-list, then privacy, then people rules, then organise. Only useful messages are kept,
 * only masked text reaches the disk, and everything here runs on the phone.
 */
class CaptureRepository(
    private val db: PulseDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val onReminderDetected: (Reminder) -> Unit = {},
    private val onBalanceSeen: (Long, Long) -> Unit = { _, _ -> },
    private val isPaused: () -> Boolean = { false },
    private val onRecordSaved: suspend () -> Unit = {},
    private val autoConfirmSure: () -> Boolean = { false },
    private val onTodoSuggested: (Todo) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    // An SMS and an app can report one payment at the same moment. One at a time means the second sees the first.
    private val oneAtATime = Mutex()

    suspend fun capture(pkg: String, title: String?, text: String?, postedAt: Long): CaptureDecision =
        oneAtATime.withLock { handle(pkg, title, text, postedAt) }

    private suspend fun handle(pkg: String, title: String?, text: String?, postedAt: Long): CaptureDecision {
        if (isPaused()) return CaptureDecision.PAUSED
        val allowed = db.appPermissionDao().isAllowed(pkg) == true
        val gate = CaptureGate.decide(allowed, title, text)
        val t = now()
        if (allowed) {
            db.appSeenDao().addIfMissing(pkg, t)
            db.appSeenDao().bump(pkg, t)
        }
        val rule = if (gate == CaptureDecision.STORE && isPersonalApp(pkg)) {
            ContactMatcher.match(title, db.contactRuleDao().all()) { it.name }
        } else {
            null
        }
        val decision = when {
            gate == CaptureDecision.DROPPED_SENSITIVE -> {
                db.dropLogDao().insert(DropLog(droppedAt = t))
                gate
            }
            gate != CaptureDecision.STORE -> gate
            rule?.starred == false -> CaptureDecision.MUTED
            else -> store(pkg, title.orEmpty().trim(), text.orEmpty().trim(), postedAt, t, rule?.starred == true)
        }
        if (allowed && decision != CaptureDecision.DROPPED_EMPTY) logActivity(pkg, title, decision, t)
        return decision
    }

    /** The bell page's history. It names the sender only, never the message, and lasts 7 days. */
    private suspend fun logActivity(pkg: String, title: String?, decision: CaptureDecision, t: Long) {
        val shown = if (decision == CaptureDecision.DROPPED_SENSITIVE) "" else PrivacyFilter.mask(title.orEmpty().trim())
        db.activityDao().insert(ActivityEntry(packageName = pkg, title = shown, outcome = decision.name, at = t))
        db.activityDao().deleteOlderThan(t - ACTIVITY_KEEP_MS)
    }

    private suspend fun store(pkg: String, title: String, text: String, postedAt: Long, t: Long, starred: Boolean): CaptureDecision {
        val sms = pkg in MESSAGING_PACKAGES
        val personal = isPersonalApp(pkg)
        val bankAlert = !personal || ExpenseExtractor.looksLikeBankAlert(text)
        if (bankAlert) ExpenseExtractor.findBalance(text)?.let { onBalanceSeen(it, t) }
        val money = ExpenseExtractor.extract(title, text, titleMayBePerson = !personal)?.takeIf { bankAlert }
        // Chat apps were turned on for this, and a detected date only asks first. SMS stays strict.
        val deadline = extractDeadline(title, text, t)?.takeIf { !sms || starred || DeadlineExtractor.looksLikeBill(text) }
        val todoText = if (money == null && deadline == null && (!sms || starred)) TaskPredictor.suggestion(text) else null
        // A personal chat becomes a note only from a starred person, so SMS needs a business-style sender.
        val kind = when {
            money != null -> null
            starred -> NoteKind.PEOPLE.takeIf { text.length >= MIN_STARRED_NOTE }
            else -> BrainClassifier.classify("$title. $text")?.takeIf { !personal || BrainClassifier.looksLikeBusiness(title) }
        }
        if (money == null && deadline == null && kind == null && todoText == null) return CaptureDecision.NOTHING_USEFUL

        val merchant = money?.merchant?.let(PrivacyFilter::mask)
        val paymentRef = money?.let { ExpenseExtractor.findReference(text) }
        if (money != null && mergeIfSamePayment(pkg, money, merchant, paymentRef, postedAt)) return CaptureDecision.DUPLICATE

        var todo: Todo? = null
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
                        reference = paymentRef,
                    ),
                )
            }
            if (kind != null) saveNote(title, text, kind, pkg, t)
            db.rawEventDao().deleteOlderThan(t - Retention.RAW_EVENT_MS)
            todo = todoText?.let { saveSuggestedTodo(it, pkg, t) }
            deadline?.let { saveReminder(it, pkg) }
        }
        reminder?.let(onReminderDetected)
        todo?.let(onTodoSuggested)
        if (money != null) onRecordSaved()
        return CaptureDecision.STORE
    }

    private suspend fun saveSuggestedTodo(text: String, pkg: String, t: Long): Todo? {
        val line = PrivacyFilter.mask(text)
        if (db.todoDao().countSame(line) > 0) return null
        val todo = Todo(text = line, confirmed = false, createdAt = t, source = pkg)
        return todo.copy(id = db.todoDao().insert(todo))
    }

    private suspend fun saveNote(title: String, text: String, kind: NoteKind, pkg: String, t: Long) {
        val short = BrainClassifier.shorten(text)
        val line = PrivacyFilter.mask(if (kind == NoteKind.PEOPLE) "From $title: $short" else short)
        if (db.noteDao().countSame(line) == 0) {
            db.noteDao().insert(Note(text = line, kind = kind.name, createdAt = t, source = pkg))
        }
    }

    private fun extractDeadline(title: String, text: String, t: Long): Deadline? {
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        return DeadlineExtractor.extract(title, text, localNow)
    }

    /**
     * Detected reminders start unconfirmed: Memora asks first, and schedules only on a yes.
     * With Automatic reminders on, a clear date from a non-chat app is set straight away, with Undo.
     */
    private suspend fun saveReminder(deadline: Deadline, pkg: String): Reminder? {
        val title = PrivacyFilter.mask(deadline.title)
        val dueAt = deadline.dueAt.atZone(zone).toInstant().toEpochMilli()
        if (db.reminderDao().countSame(title, dueAt) > 0) return null
        val reminder = Reminder(
            title = title,
            dueAt = dueAt,
            confidence = deadline.confidence,
            confirmed = !isPersonalApp(pkg) && !deadline.needsConfirmation && autoConfirmSure(),
            source = pkg,
        )
        return reminder.copy(id = db.reminderDao().insert(reminder))
    }

    /**
     * If this report is a payment we already have, keeps one record and gives it the best name and
     * reference from both, since the SMS may have a reference while the app has the person's name.
     */
    private suspend fun mergeIfSamePayment(pkg: String, money: Extraction, merchant: String?, reference: String?, postedAt: Long): Boolean {
        val dao = db.moneyRecordDao()
        val report = PaymentMatch.Report(pkg, merchant, reference, postedAt)
        val existing = dao.sameAmount(money.amountPaise, money.type, postedAt - PaymentMatch.SEARCH_MS, postedAt + PaymentMatch.SEARCH_MS)
            .firstOrNull { PaymentMatch.same(report, PaymentMatch.Report(it.packageName, it.merchant, it.reference, it.occurredAt)) }
            ?: return false
        val merged = existing.copy(
            merchant = PaymentMatch.betterName(existing.merchant, merchant),
            reference = existing.reference ?: reference,
        )
        if (merged != existing) dao.update(merged)
        return true
    }

    private companion object {
        const val ACTIVITY_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        // "ok" or "haha" from a starred friend isn't worth keeping.
        const val MIN_STARRED_NOTE = 12
    }
}
