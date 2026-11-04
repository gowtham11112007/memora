package com.pulse.app.data

import android.content.Context
import com.pulse.app.domain.MoneySettings
import kotlinx.coroutines.flow.MutableStateFlow

/** The balance and budget the user set, and the last balance seen in a bank SMS. */
class MoneyPrefs(context: Context, demo: Boolean = false) {
    private val prefs = context.getSharedPreferences(if (demo) "money-demo" else "money", Context.MODE_PRIVATE)
    val state = MutableStateFlow(load())

    private fun load() = MoneySettings(
        openingPaise = prefs.getLong("opening", 0),
        openingAt = prefs.getLong("openingAt", 0),
        bankPaise = prefs.getLong("bank", 0),
        bankAt = prefs.getLong("bankAt", 0),
        budgetPaise = prefs.getLong("budget", 0),
    )

    fun setOpening(paise: Long, at: Long) {
        prefs.edit().putLong("opening", paise).putLong("openingAt", at).apply()
        state.value = load()
    }

    fun setBudget(paise: Long) {
        prefs.edit().putLong("budget", paise).apply()
        state.value = load()
    }

    fun setBank(paise: Long, at: Long) {
        prefs.edit().putLong("bank", paise).putLong("bankAt", at).apply()
        state.value = load()
    }

    fun clear() {
        prefs.edit().clear().apply()
        state.value = load()
    }
}
