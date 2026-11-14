package ltd.usage.tracker.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ltd.usage.tracker.data.Metric

/** 账号名与服务商名重复（相同或互相包含，忽略大小写）时不再单独显示服务商。 */
internal fun providerLabelOrNull(accountName: String, providerName: String): String? {
    val a = accountName.trim()
    val p = providerName.trim()
    if (p.isEmpty()) return null
    if (a.contains(p, ignoreCase = true) || p.contains(a, ignoreCase = true)) return null
    return p
}

/**
 * 错误说明改成人话。只翻译确定含义的状态码，其余保留原文，避免误导排查。
 */
internal fun humanizeError(raw: String): String {
    val t = raw.trim()
    val code = Regex("""^HTTP\s+(\d{3})\b""").find(t)?.groupValues?.get(1)
    return when (code) {
        "401" -> "API Key 无效（401）"
        else -> t
    }
}

/**
 * 单指标条下说明：「标签 · 已用 x%」，有重置时间时沿用指标 note 里现成的「· MM-dd 重置」。
 * note 本身就是「已用 …」开头的完整文案时直接接在标签后，否则补上已用百分比。
 */
internal fun metricCaption(m: Metric): String {
    val note = m.note?.trim().orEmpty()
    val used = when {
        m.usedPercent != null -> "已用 ${m.usedPercent.coerceIn(0.0, 100.0).toInt()}%"
        m.quotaMinor != null && m.quotaMinor > 0 && m.balanceMinor != null ->
            "已用 ${((m.quotaMinor - m.balanceMinor).toDouble() / m.quotaMinor * 100).coerceIn(0.0, 100.0).toInt()}%"
        else -> null
    }
    val body = when {
        note.startsWith("已用") -> note
        used != null && note.isNotEmpty() -> "$used · $note"
        used != null -> used
        else -> note
    }
    return if (body.isEmpty()) m.label else "${m.label} · $body"
}

/** 比较两句说明是否同义重复：忽略空白和「·」分隔，前者被后者包含即视为重复。 */
internal fun isSameText(note: String, caption: String): Boolean {
    fun norm(t: String) = t.filterNot { it.isWhitespace() || it == '·' }.lowercase()
    val n = norm(note)
    return n.isNotEmpty() && norm(caption).contains(n)
}

internal fun humanizeNote(raw: String): String {
    val t = raw.trim()
    return when {
        t.startsWith("可用:") -> if (t.substringAfter(":").trim().equals("true", true)) "服务可用" else "服务不可用"
        t.startsWith("团队封禁:") -> if (t.substringAfter(":").trim().equals("false", true)) "团队正常" else "团队封禁"
        t.startsWith("ZDR:") -> if (t.substringAfter(":").trim().equals("zdr", true)) "零数据留存" else "未开启 ZDR"
        t.startsWith("PAYG:") -> if (t.substringAfter(":").trim().equals("true", true)) "按量已开启" else "按量未开启"
        else -> t
    }
}

internal fun formatMoney(minor: Long): String =
    String.format(Locale.getDefault(), "%,.2f", minor / 100.0)

internal fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
