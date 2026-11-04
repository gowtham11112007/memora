package com.pulse.app.domain

/** How long raw notification text is kept before it is deleted. */
object Retention {
    const val RAW_EVENT_MS: Long = 24L * 60 * 60 * 1000
}

enum class CaptureDecision {
    STORE,
    /** The user paused reading, so nothing is looked at. */
    PAUSED,
    IGNORED_NOT_ALLOWED,
    DROPPED_EMPTY,
    DROPPED_SENSITIVE,

    /** Allowed and safe, but no money or date in it, so nothing was kept. */
    NOTHING_USEFUL,

    /** Same app, amount and merchant within 2 minutes: already recorded. */
    DUPLICATE,
}

/** Pure logic (no Android, no database) so it is easy to unit test. */
object CaptureGate {
    fun decide(isAllowed: Boolean, title: String?, text: String?): CaptureDecision {
        // 1. Not allowed: we never even look at the content.
        if (!isAllowed) return CaptureDecision.IGNORED_NOT_ALLOWED
        val body = text.orEmpty().trim()
        if (body.isEmpty()) return CaptureDecision.DROPPED_EMPTY
        // 2. Sensitive: dropped before anything is stored.
        if (PrivacyFilter.isSensitive("${title.orEmpty()} $body")) return CaptureDecision.DROPPED_SENSITIVE
        return CaptureDecision.STORE
    }
}
