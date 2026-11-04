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

    // Promotions, failures and future events mention money, but no money moved.
    private val notATransaction = Regex(
        """\b(failed|declined|unsuccessful|cancelled|canceled|requested|request|reminder|due|overdue|will be|scheduled|upcoming|offer|win|won|get|claim|eligible|pre-?approved|loan|apply|hurry|voucher|coupon|pay now)\b""",
        ignoreCase,
    )

    // Removed first, so "Avl Bal Rs 10,000" is never mistaken for the transaction amount.
    private val balance = Regex(
        """\b(?:avl\.?|avail\.?|available|closing|total)?\s*(?:bal\.?|balance)\s*(?:is|:|-)?\s*(?:₹|rs\.?|inr)?\s*[0-9][0-9,]*(?:\.[0-9]+)?""",
        ignoreCase,
    )

    private val amountAfterSymbol = Regex("""(?:₹|\brs\.?|\binr\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", ignoreCase)
    private val amountBeforeWord = Regex("""\b([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:rs|inr|rupees)\b""", ignoreCase)

    // Some bank SMS have no currency symbol: "A/C X1234 debited by 20.0 on 01Jan26".
    private val amountAfterVerb = Regex("""\b(?:debited|credited)\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", ignoreCase)

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
        """\b(paid you|sent you|payment of [^.]*? received|received|credited|refund(?:ed)?|deposited|cashback)\b""",
        ignoreCase,
    )
    private val expenseWords = Regex(
        """\b(paid|sent|debited|spent|purchase|withdrawn|withdrawal|transferred|charged|payment (?:of|to|successful|done))\b""",
        ignoreCase,
    )

    private const val NAME = """([A-Za-z0-9&@'_-][^.;,\n]*?)"""
    private const val NAME_END =
        """(?=\s+(?:via|on|using|from|ref|upi|for|thru|through|refno|with|and|is|was|has|successful|successfully|processed|done|deposited|at|txn)\b|\s*[.;,\n]|\s*$)"""
    private val toMerchant = Regex("""\b(?:to|at)\s+(?:vpa\s+|merchant\s+)?$NAME$NAME_END""", ignoreCase)
    private val creditedParty = Regex(""";\s*$NAME\s+credited\b""", ignoreCase)
    private val fromSender = Regex("""\bfrom\s+(?:vpa\s+)?$NAME$NAME_END""", ignoreCase)
    private val bySender = Regex("""\bby\s+(?:vpa\s+)?$NAME$NAME_END""", ignoreCase)
    private val paidYouSender = Regex("""^\s*(.+?)\s+(?:paid|sent)\s+you\b""", ignoreCase)
    private val notAName = Regex("""^(your|you|a/?c|account|bank|card|xx|wallet)\b""", ignoreCase)

    /** Reads the body first. The title is only used if the body alone has no transaction. */
    fun extract(title: String?, text: String): Extraction? =
        parse(text) ?: title?.takeIf { it.isNotBlank() }?.let { parse("$it. $text") }

    private fun parse(message: String): Extraction? {
        if (notATransaction.containsMatchIn(message)) return null
        val cleaned = balance.replace(message, " ")
        val amount = findAmount(cleaned) ?: return null
        val type = findType(cleaned) ?: return null
        val merchant = if (type == RecordType.EXPENSE) {
            firstName(toMerchant, cleaned) ?: firstName(creditedParty, cleaned)
        } else {
            firstName(paidYouSender, cleaned) ?: firstName(fromSender, cleaned) ?: firstName(bySender, cleaned)
        }
        return Extraction(amount, merchant, type)
    }

    private fun findAmount(text: String): Long? {
        val match = amountAfterSymbol.find(text) ?: amountAfterVerb.find(text) ?: amountBeforeWord.find(text)
        val raw = match?.groupValues?.get(1) ?: return null
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
            .map { it.groupValues[1].substringBefore('@').trim() }
            .firstOrNull { it.isNotEmpty() && it.length <= 40 && !notAName.containsMatchIn(it) }
}
