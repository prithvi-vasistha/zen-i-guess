package dev.zig.notificationfilter.domain

import java.text.Normalizer

/** Conservative, local extraction: never guess between distinct codes or copy unrelated numbers. */
object VerificationCodeExtractor {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val attachedLabel = Regex("\\b(otp|pin|code)(?=[0-9]{4,10}\\b)", options)
    private val labels = Regex(
        "\\b(?:otps?|passcode|pin|one[-\\s]+time(?:[-\\s]+(?:password|code|pin))?|" +
            "(?:verification|security|authentication|auth|authorization|login|log[- ]in|sign[- ]?in|" +
            "access|activation|confirmation|reset|delivery|pickup|entry|gate|door|unlock)[-\\s]+(?:code|pin|password|key)|" +
            "your(?:\\s+[\\p{L}]+){0,3}\\s+code|code\\s+(?:is|for\\s+(?:verification|login|sign[- ]?in|access|delivery))|" +
            "(?:log|sign)[-\\s]+in|(?:verify|confirm)\\s+(?:your\\s+)?(?:identity|account|email|phone(?:\\s+number)?))\\b", options)
    private val unrelatedLabels = Regex("\\b(?:promo|coupon|discount|offer|referral|voucher|gift|tracking|error|status)\\s+code\\b", options)
    private val candidates = Regex("(?<![\\p{L}\\p{N}])(?:[0-9]{2,4}[- \\u00a0][0-9]{2,4}(?:[- \\u00a0][0-9]{2,4})*|[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)(?![\\p{L}\\p{N}])")
    private val alphabeticJoiners = Regex("[\\s:=\"'`<>()-]*(?:(?:is|your|the)[\\s:=\"'`<>()-]*)*", options)
    private val ordinaryWords = setOf("THIS", "THAT", "YOUR", "CODE", "CODES", "PLEASE", "NEVER", "SHARE", "VALID",
        "EXPIRES", "READY", "SENT", "USED", "ONLY", "REQUIRED", "INVALID", "FAILED", "REQUEST", "PASSWORD")
    private val digitDescriptor = Regex("[0-9]+-?digits?", options)
    private val retrieverHash = Regex("(?m)^[A-Za-z0-9+/]{11}=?\\s*$")
    private val googleCode = Regex("G-[0-9]{6}", options)
    private val ignored = listOf(
        Regex("(?:https?://|www\\.)\\S+", options),
        Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", options),
        Regex("\\b(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}|[0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{2,4})\\b"),
        Regex("\\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:\\s*[AP]M)?\\b", options),
        Regex("(?:₹|\\bRs\\.?|\\bINR|\\bUSD|\\bEUR|\\bGBP|\\$|€|£)\\s*[0-9][0-9,.]*", options),
        Regex("\\b[0-9][0-9,.]*\\s*(?:rupees|dollars|USD|INR|EUR)\\b", options),
        Regex("\\b(?:account|a/c|card|transaction(?:\\s+(?:id|number|ref))|order(?:\\s+(?:id|number))|reference|ref|ticket|invoice|tracking|error|status|version)" +
            "(?:\\s+(?:number|no\\.?|ending(?:\\s+in)?|id|is))?\\s*[:#*-]*\\s*[A-Z0-9*-]*[0-9][A-Z0-9*-]*\\b", options),
        Regex("(?<![\\p{L}\\p{N}])[xX*•●]{2,}[- ]*[0-9]{3,10}\\b"),
        Regex("\\b[0-9]+\\s*(?:minutes?|mins?|seconds?|secs?|hours?|hrs?|days?)\\b", options),
        Regex("\\+[0-9][0-9() .-]{7,}[0-9]"),
        Regex("\\b(?:call|phone|mobile|helpline|contact|tel)\\s*(?:us|number|at|on|is|no\\.?)?\\s*[:+(-]*[0-9][0-9() .-]{6,}[0-9]", options),
        Regex("\\b(?:sent|sending|delivered)\\s+to\\s*[:+(-]*[0-9][0-9() .-]{6,}[0-9]", options),
        Regex("\\b[0-9]+[.,][0-9]+\\b"),
    )

    fun extract(title: String, latestMessage: String): String? {
        val source = Normalizer.normalize(title.take(512) + "\n" + latestMessage.take(16_384), Normalizer.Form.NFKC)
            .map { char -> Character.digit(char, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() } ?: char }
            .joinToString("").let { attachedLabel.replace(it, "$1 ") }
        // Keep offsets intact so labels and candidates are compared in the same message.
        val masked = source.toCharArray()
        ignored.forEach { regex -> regex.findAll(source).forEach { match -> match.range.forEach { masked[it] = ' ' } } }
        if (source.contains("<#>")) retrieverHash.findAll(source).forEach { match -> match.range.forEach { masked[it] = ' ' } }
        val text = String(masked)
        val otherAnchors = unrelatedLabels.findAll(text).toList()
        val anchors = labels.findAll(text).filterNot { anchor ->
            otherAnchors.any { anchor.range.first <= it.range.last && it.range.first <= anchor.range.last }
        }.toList()
        if (anchors.isEmpty()) return null
        val titleEnd = text.indexOf('\n')
        val matches = candidates.findAll(text).mapNotNull { candidate ->
            if (candidate.range.last < titleEnd && anchors.none { it.range.last < titleEnd }) return@mapNotNull null
            if (anchors.any { candidate.range.first <= it.range.last && it.range.first <= candidate.range.last }) return@mapNotNull null
            val raw = if (googleCode.matches(candidate.value) && text.contains("google", ignoreCase = true)) candidate.value.drop(2) else candidate.value
            val compact = raw.filterNot { it == '-' || it.isWhitespace() }
            val numeric = compact.all(Char::isDigit)
            val alphabetic = compact.all(Char::isLetter)
            if (numeric && compact.length !in 4..10 || !numeric && compact.length !in 4..16) return@mapNotNull null
            if (!numeric && !alphabetic && (!compact.any(Char::isDigit) || !compact.any(Char::isLetter))) return@mapNotNull null
            if (digitDescriptor.matches(raw)) return@mapNotNull null
            val closest = anchors.minBy { distance(candidate.range, it.range) }
            val gap = gap(text, candidate.range, closest.range)
            if (gap.length > 80 || otherAnchors.any { distance(candidate.range, it.range) <= distance(candidate.range, closest.range) }) return@mapNotNull null
            if (alphabetic && (raw != raw.uppercase() || compact in ordinaryWords || !alphabeticJoiners.matches(gap))) return@mapNotNull null
            if (numeric) compact else raw
        }.distinct().take(2).toList()
        return matches.singleOrNull()
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last - 1
        b.last < a.first -> a.first - b.last - 1
        else -> 0
    }

    private fun gap(text: String, a: IntRange, b: IntRange): String = when {
        a.last < b.first -> text.substring(a.last + 1, b.first)
        b.last < a.first -> text.substring(b.last + 1, a.first)
        else -> ""
    }
}
