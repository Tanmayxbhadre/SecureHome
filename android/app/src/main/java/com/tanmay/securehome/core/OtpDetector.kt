package com.tanmay.securehome.core

/**
 * Port of relay/scripts/lib/otp.mjs. Must pass fixtures/otp-sms.json (see OtpDetectorTest).
 * Digits are matched as [0-9] on purpose: Android's ICU regex treats \d as any Unicode digit, JavaScript doesn't.
 */
object OtpDetector {
    data class Detected(val code: String, val kind: String, val sender: String)

    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val KEYWORD = Regex(
        """\b(?:otp|one[\s-]?time[\s-]?(?:password|passcode|pin)|verification\s+(?:code|pin)|security\s+code|auth(?:entication)?\s+code|login\s+code|passcode|pin|code)\b""",
        IC,
    )
    private val NOT_KEYWORD = Regex("""\bpin\s*code\b""", IC) // postal "PIN code 560001"
    private val BEFORE_EXCLUDE = Regex("""(?:rs\.?|inr|₹|a/c|acct|ending(?:\s+with)?|no\.?|ref\.?|pin\s*code)\s*[:\-]?\s*$""", IC)
    private val RIDE = Regex(
        """\b(?:uber|ola|rapido|namma\s*yatri|blusmart|indrive)\b|\b(?:ride|trip|start)\s+(?:pin|otp|code)\b|\bride\s+otp\b""",
        IC,
    )
    private val RIDE_SENDER = Regex("^(?:UBER|OLA|RAPIDO|NYATRI|BLUSMT|INDRIV)")
    private val DELIVERY = Regex("""\bdeliver(?:y|ed|ing)?\b|\bcourier\b|\bparcel\b|\bpackage\b|\bshipment\b""", IC)
    private val SENDER = Regex("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$""", IC)
    private val NUMBER = Regex("[0-9]{4,8}")
    private val ALNUM = Regex("[A-Za-z0-9]")
    private val DATE_AFTER = Regex("""^[\-/:][0-9]""")
    private val DECIMAL_AFTER = Regex("""^[.,][0-9]""")
    private val SUFFIX_AFTER = Regex("""^\s*(?:%|/-)""")

    /** "AX-HDFCBK-S" → "HDFCBK"; anything else is returned trimmed. */
    fun normalizeSender(from: String): String {
        val t = from.trim()
        return SENDER.find(t)?.groupValues?.get(1)?.uppercase() ?: t
    }

    private class Candidate(val code: String, val at: Int)

    private fun candidates(body: String): List<Candidate> = NUMBER.findAll(body).mapNotNull { m ->
        val i = m.range.first
        val j = m.range.last + 1
        val prev = if (i > 0) body.substring(i - 1, i) else ""
        val prev2 = body.substring(maxOf(0, i - 2), i)
        val next = if (j < body.length) body.substring(j, j + 1) else ""
        val after = body.substring(j, minOf(body.length, j + 4))
        when {
            ALNUM.matches(next) || (ALNUM.matches(prev) && prev2 != "G-") -> null // part of a longer token
            prev == "/" || prev == ":" || (prev == "-" && !prev2.equals("G-", ignoreCase = true)) ||
                DATE_AFTER.containsMatchIn(after) -> null // dates, times
            DECIMAL_AFTER.containsMatchIn(after) || SUFFIX_AFTER.containsMatchIn(after) -> null // amounts, percent
            BEFORE_EXCLUDE.containsMatchIn(body.substring(maxOf(0, i - 16), i)) -> null
            else -> Candidate(m.value, i)
        }
    }.toList()

    fun detect(from: String, body: String): Detected? {
        val masked = NOT_KEYWORD.replace(body) { " ".repeat(it.value.length) }
        val keywords = KEYWORD.findAll(masked).map { it.range.first + it.value.length / 2.0 }.toList()
        if (keywords.isEmpty()) return null

        var best: Candidate? = null
        var bestDist = Double.MAX_VALUE
        for (c in candidates(body)) {
            val centre = c.at + c.code.length / 2.0
            val dist = keywords.minOf { kotlin.math.abs(it - centre) }
            if (best == null || dist < bestDist) {
                best = c
                bestDist = dist
            }
        }
        if (best == null || bestDist > 80) return null

        val sender = normalizeSender(from)
        val kind = when {
            RIDE.containsMatchIn(body) || RIDE_SENDER.containsMatchIn(sender) -> "ride"
            DELIVERY.containsMatchIn(body) -> "delivery"
            else -> "otp"
        }
        return Detected(best.code, kind, sender)
    }
}
