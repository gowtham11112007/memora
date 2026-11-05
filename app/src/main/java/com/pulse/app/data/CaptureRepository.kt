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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** SMS apps also carry personal chats, so they get a stricter check before anything is kept. */
val MESSAGING_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
    "com.truecaller",
    "com.microsoft.android.smsorganizer",
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
 * and only masked text reaches the disk. Everything runs on the phone, unless Smart reading is on,
 * and then only what [readSmartly] lets through goes to the AI services.
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
    private val smartReadingOn: () -> Boolean = { false },
    private val smartReader: suspend (String, String, LocalDateTime) -> AiReading? = { _, _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    // An SMS and an app can report one payment at the same moment. One at a time means the second sees the first.
    private val oneAtATime = Mutex()

    // Apps often repost a notification, and each read would use up free quota.
    private val recentReadings = object : LinkedHashMap<Int, AiReading>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AiReading>) = size > RECENT_READINGS
    }

    suspend fun capture(pkg: String, title: String?, text: String?, postedAt: Long): CaptureDecision {
        // The network call happens outside the lock, so a slow reply never holds up a payment.
        val reading = readSmartly(pkg, title, text)
        return oneAtATime.withLock { handle(pkg, title, text, postedAt, reading) }
    }

    /**
     * The only place message text can leave the phone. It runs the same checks as [handle] first, so
     * apps not allowed, codes, passwords and muted people are never sent. Money stays on the phone
     * too: bank alerts are skipped, and rules alone decide what counts as a payment.
     */
    private suspend fun readSmartly(pkg: String, title: String?, text: String?): AiReading? {
        if (!smartReadingOn() || isPaused()) return null
        if (db.appPermissionDao().isAllowed(pkg) != true) return null
        if (CaptureGate.decide(true, title, text) != CaptureDecision.STORE) return null
        val sender = cleanSender(title)
        val body = text.orEmpty().trim()
        if (body.length < MIN_SMART_READ) return null
        if (isPersonalApp(pkg) && ContactMatcher.match(title, db.contactRuleDao().all()) { it.name }?.starred == false) return null
        if (ExpenseExtractor.looksLikeBankAlert(body) || ExpenseExtractor.extract(sender, body, titleMayBePerson = false) != null) return null

        val key = "$sender\n$body".hashCode()
        synchronized(recentReadings) { recentReadings[key] }?.let { return it }
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now()), zone)
        val reading = try {
            withContext(Dispatchers.IO) { smartReader(PrivacyFilter.mask(sender), PrivacyFilter.mask(body), localNow) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (reading != null) synchronized(recentReadings) { recentReadings[key] = reading }
        return reading
    }

    private suspend fun handle(pkg: String, title: String?, text: String?, postedAt: Long, reading: AiReading?): CaptureDecision {
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
            else -> store(pkg, cleanSender(title), text.orEmpty().trim(), postedAt, t, rule?.starred == true, reading)
        }
        if (allowed && decision != CaptureDecision.DROPPED_EMPTY) logActivity(pkg, title, decision, t)
        return decision
    }

    /** The bell page's history. It names the sender only, never the message, and lasts 7 days. */
    private suspend fun logActivity(pkg: String, title: String?, decision: CaptureDecision, t: Long) {
        val shown = if (decision == CaptureDecision.DROPPED_SENSITIVE) "" else PrivacyFilter.mask(cleanSender(title))
        db.activityDao().insert(ActivityEntry(packageName = pkg, title = shown, outcome = decision.name, at = t))
        db.activityDao().deleteOlderThan(t - ACTIVITY_KEEP_MS)
    }

    private suspend fun store(
        pkg: String,
        title: String,
        text: String,
        postedAt: Long,
        t: Long,
        starred: Boolean,
        reading: AiReading?,
    ): CaptureDecision {
        val personal = isPersonalApp(pkg)
        val bankAlert = !personal || ExpenseExtractor.looksLikeBankAlert(text)
        if (bankAlert) ExpenseExtractor.findBalance(text)?.let { onBalanceSeen(it, t) }
        val money = ExpenseExtractor.extract(title, text, titleMayBePerson = !personal)?.takeIf { bankAlert }
        // The AI adds understanding and the word rules keep working, so a miss by the AI never loses a clear date.
        val summary = reading?.summary
        // The user turned these apps on to be read, and a detected date or task only asks first.
        val deadline = reading?.remindAt?.let { Deadline((summary ?: BrainClassifier.shorten(text)).take(MAX_REMINDER_TITLE), it, AI_CONFIDENCE) }
            ?: extractDeadline(title, text, t)
        val todoText = if (money != null || deadline != null) null else reading?.todo ?: TaskPredictor.suggestion(text)
        // Useful messages from any allowed app go to Second Brain. Everyday chatter is dropped.
        val kind = when {
            money != null -> null
            starred -> NoteKind.PEOPLE.takeIf { text.length >= MIN_STARRED_NOTE }
            else -> reading?.kind ?: BrainClassifier.classify("$title. $text")
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
            if (kind != null) saveNote(title, summary ?: text, kind, pkg, t)
            db.rawEventDao().deleteOlderThan(t - Retention.RAW_EVENT_MS)
            todo = todoText?.let { saveSuggestedTodo(it, pkg, t) }
            deadline?.let { saveReminder(it, pkg) }
        }
        reminder?.let(onReminderDetected)
        todo?.let(onTodoSuggested)
        if (money != null) onRecordSaved()
        return CaptureDecision.STORE
    }

    /** WhatsApp adds a count to the sender's name, such as "Rahul (2 messages)". */
    private fun cleanSender(title: String?) = title.orEmpty().trim().replace(Regex("""\s*\(\d+ messages?\)$"""), "")

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
        const val RECENT_READINGS = 50
        const val MIN_SMART_READ = 8
        const val MAX_REMINDER_TITLE = 70

        // Below Deadline.CONFIDENT, so a date from Gemini always asks first, even with Automatic reminders on.
        const val AI_CONFIDENCE = 0.7
        const val ACTIVITY_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        // "ok" or "haha" from a starred friend isn't worth keeping.
        const val MIN_STARRED_NOTE = 12
    }
}
