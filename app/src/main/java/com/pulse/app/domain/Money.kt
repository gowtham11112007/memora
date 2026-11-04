package com.pulse.app.domain

/** 123456700 paise -> "₹12,34,567". Uses Indian digit grouping and hides ".00". */
fun formatRupees(paise: Long): String {
    val digits = (paise / 100).toString()
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
    }
    val fraction = paise % 100
    return "₹$grouped" + if (fraction == 0L) "" else "." + fraction.toString().padStart(2, '0')
}
