package com.pulse.app.domain

enum class RecordType { EXPENSE, INCOME }

/** Money is held in paise as a Long, because floating point cannot represent 0.10 exactly. */
data class Extraction(val amountPaise: Long, val merchant: String?, val type: RecordType)

/**
 * Rules only: no network and no AI. Fast, free, testable, and it behaves the same on stage.
 * It returns null whenever it is unsure, since a missed record is better than a wrong total.
 */
object ExpenseExtractor {
    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)

    // No money moved, whatever else the message says.
    private val failed = Regex("""\b(failed|declined|unsuccessful|cancelled|canceled)\b""", ignoreCase)

    // Promotions and future events mention money too, but a real bank alert can carry these words in an advert.
    private val promoOrFuture = Regex(
        """\b(requested|request|reminder|due|overdue|will be|scheduled|upcoming|offer|win|won|get|claim|eligible|pre-?approved|loan|apply|hurry|voucher|coupon|pay now)\b""",
        ignoreCase,
    )
    private val moneyMoved = Regex("""\b(debited|credited|withdrawn)\b""", ignoreCase)
    private val notYet = Regex("""\b(will be|to be|shall be|get|claim|eligible)\b[^.]*\b(debited|credited)\b""", ignoreCase)

    // Banks end alerts with "If not requested by you, call...". That advice isn't part of the transaction.
    private val safetyFooter = Regex(
        """\b(?:if\s+not|if\s+this\s+(?:is|was)\s+not|not\s+you|not\s+u\b|not\s+done\s+by|if\s+(?:you\s+)?(?:did|have)\s*(?:not|n't)|sms\s+block|to\s+block|to\s+report|to\s+dispute|for\s+dispute|report\s+(?:fraud|unauth)|t\s*&\s*c).*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun isNotATransaction(message: String): Boolean {
        if (failed.containsMatchIn(message)) return true
        if (!promoOrFuture.containsMatchIn(message)) return false
        val confirmed = moneyMoved.containsMatchIn(message) && looksLikeBankAlert(message) && !notYet.containsMatchIn(message)
        return !confirmed
    }

    // Removed first, so "Avl Bal Rs 10,000" is never mistaken for the transaction amount.
    private val balance = Regex(
        """\b(?:avl\.?|avail\.?|available|closing|total)?\s*(?:bal\.?|balance)\s*(?:is|:|-)?\s*(?:₹|rs\.?|inr)?\s*[0-9][0-9,]*(?:\.[0-9]+)?""",
        ignoreCase,
    )

    private val amountAfterSymbol = Regex("""(?:₹|\brs\.?|\binr\.?|\brupees\b)[\s:]*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", ignoreCase)
    private val amountBeforeWord = Regex("""\b([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:rs|inr|rupees)\b""", ignoreCase)

    // Some bank SMS have no currency symbol: "A/C X1234 debited by 20.0 on 01Jan26".
    private val amountAfterVerb = Regex(
        """\b(?:debited|credited)\s+(?:by|for|with|of)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)|\b(?:paid|sent)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+to\b""",
        ignoreCase,
    )

    // Used for SMS apps, so a chat like "I paid 500 rupees for lunch" never becomes a record.
    private val bankAlertWords = Regex("""\b(a/?c|acct|account|upi|card|bank|ref|refno|txn|debited|credited|neft|imps|rtgs)\b""", ignoreCase)

    fun looksLikeBankAlert(text: String): Boolean = bankAlertWords.containsMatchIn(text)

    private val balanceAmount = Regex(
        """\b(?:avl\.?|avail\.?|available|closing)?\s*(?:bal\.?|balance)\s*(?:is|:|-)?\s*(?:₹|rs\.?|inr)?\s*([0-9][0-9,]*(?:\.[0-9]+)?)""",
        ignoreCase,
    )

    /** The account balance a bank message reports, in paise. */
    fun findBalance(text: String): Long? {
        val raw = balanceAmount.find(text)?.groupValues?.get(1) ?: return null
        val parts = raw.replace(",", "").split(".")
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = parts.getOrNull(1)?.take(2)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        return rupees * 100 + paise
    }

    private val incomeWords = Regex(
        """\b(paid you|sent you|(?:paid|sent)\s+(?:₹|rs\.?|inr\.?)\s*[\d,.]+\s+to\s+you|payment of [^.]*? received|received|credited|credit(?!\s*card)|refund(?:ed)?|deposited|cashback)\b""",
        ignoreCase,
    )
    private val expenseWords = Regex(
        """\b(paid|sent|debited|debit(?!\s*card)|spent|used|purchase|withdrawn|withdrawal|transferred|charged|payment (?:of|to|successful|done))\b""",
        ignoreCase,
    )

    private const val NAME = """([A-Za-z0-9&@'_-][^.;,!()\n]*?)"""
    private const val NAME_END =
        """(?=\s+(?:via|on|using|from|ref|upi|for|thru|through|refno|with|and|is|was|has|successful|successfully|processed|done|deposited|at|txn|in|into|to|your)\b|\s*[.;,!()\n]|\s*$)"""
    private const val MONEY = """(?:₹|rs\.?|inr\.?)\s*[\d,.]+"""

    // Banks often write "UPI/P2A/123456789/RAHUL KUMAR/HDFC", in either direction.
    private val upiPathName = Regex("""\bUPI/(?:[A-Za-z0-9]+/)?\d{6,}/([^/\n.]+)""", ignoreCase)
    private val toMerchant = Regex("""\b(?:to|at):?\s+(?:vpa\s+|merchant\s+)?$NAME$NAME_END""", ignoreCase)
    private val creditedParty = Regex(""";\s*$NAME\s+credited\b""", ignoreCase)
    private val paidPerson = Regex("""\b(?:paid|sent)\s+$NAME\s+$MONEY""", ignoreCase)
    private val fromSender = Regex("""\bfrom:?\s+(?:vpa\s+)?$NAME$NAME_END""", ignoreCase)
    private val bySender = Regex("""\bby\s+(?:vpa\s+)?$NAME$NAME_END""", ignoreCase)
    private val paidYouSender = Regex("""^\s*(.+?)\s+(?:has\s+)?(?:paid|sent)\s+(?:you\b|$MONEY\s+to\s+you\b)""", ignoreCase)

    // Own accounts, amounts ("credited by Rs500"), clock times ("at 2:39 PM") and app names are never the other person.
    private val notAName = Regex(
        """^(your|you|a/?c|acct|account|bank|card|xx|wallet)\b|^(?:₹|rs\.?|inr\.?)?\s*[\d,.]+$|^\d{1,2}[:.]\d{2}\b|^(?:google pay|gpay|paytm|phonepe|bhim|cred)$""",
        ignoreCase,
    )

    private val personTitle = Regex("""^[A-Za-z][A-Za-z .']{1,39}$""")
    private val notAPersonTitle = Regex(
        """\b(pay|gpay|paytm|phonepe|bhim|cred|bank|upi|payment|payments|money|received|credited|debited|paid|sent|alert|alerts|transaction|wallet|messages|notification|update|success|successful)\b""",
        ignoreCase,
    )

    private val reference = Regex(
        """\b(?:ref(?:erence)?|rrn|utr|txn|transaction|upi)\.?\s*(?:no\.?|number|id|ref)?\s*[:.#-]?\s*[A-Za-z]{0,4}(\d{6,})""",
        ignoreCase,
    )
    private val upiPathReference = Regex("""\bUPI/(?:[A-Za-z0-9]+/)?(\d{6,})""", ignoreCase)

    /** The last 4 digits of the bank's reference, which are the same whichever app reports the payment. */
    fun findReference(text: String): String? =
        (reference.find(text) ?: upiPathReference.find(text))?.groupValues?.get(1)?.takeLast(4)

    /**
     * Reads the body first, then adds the title if the body has no transaction or no name.
     * Payment apps may put the person in the title alone, so with [titleMayBePerson] a title that
     * looks like a name is used. It's off for SMS and chats, where the title is the sender of the message.
     */
    fun extract(title: String?, text: String, titleMayBePerson: Boolean = true): Extraction? {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val body = parse(text)
        val joined = if (cleanTitle != null && body?.merchant == null) parse("$cleanTitle. $text") else null
        val best = when {
            body == null -> joined
            joined != null && joined.amountPaise == body.amountPaise && joined.type == body.type -> joined
            else -> body
        } ?: return null
        val titleIsName = titleMayBePerson && cleanTitle != null &&
            personTitle.matches(cleanTitle) && !notAPersonTitle.containsMatchIn(cleanTitle)
        return if (best.merchant == null && titleIsName) best.copy(merchant = cleanTitle) else best
    }

    private fun parse(fullMessage: String): Extraction? {
        val message = safetyFooter.replace(fullMessage, "")
        if (isNotATransaction(message)) return null
        val cleaned = balance.replace(message, " ")
        val amount = findAmount(cleaned) ?: return null
        val type = findType(cleaned) ?: return null
        val merchant = firstName(upiPathName, cleaned) ?: if (type == RecordType.EXPENSE) {
            firstName(toMerchant, cleaned) ?: firstName(creditedParty, cleaned) ?: firstName(paidPerson, cleaned)
        } else {
            firstName(paidYouSender, cleaned) ?: firstName(fromSender, cleaned) ?: firstName(bySender, cleaned)
        }
        return Extraction(amount, merchant, type)
    }

    private fun findAmount(text: String): Long? {
        val match = amountAfterSymbol.find(text) ?: amountAfterVerb.find(text) ?: amountBeforeWord.find(text)
        val raw = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = raw.replace(",", "").split(".")
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = parts.getOrNull(1)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        return (rupees * 100 + paise).takeIf { it > 0 }
    }

    /** The earliest direction word wins, so "debited ... credited to Swiggy" is an expense. */
    private fun findType(text: String): RecordType? {
        val income = incomeWords.find(text)?.range?.first
        val expense = expenseWords.find(text)?.range?.first
        return when {
            income == null && expense == null -> null
            expense == null -> RecordType.INCOME
            income == null -> RecordType.EXPENSE
            income <= expense -> RecordType.INCOME
            else -> RecordType.EXPENSE
        }
    }

    private fun firstName(pattern: Regex, text: String): String? =
        pattern.findAll(text)
            .map { it.groupValues[1].substringAfterLast(". ").substringBefore('@').trim().trimEnd('.', ':', ',') }
            .firstOrNull { it.length in 2..40 && !notAName.containsMatchIn(it) }
}
