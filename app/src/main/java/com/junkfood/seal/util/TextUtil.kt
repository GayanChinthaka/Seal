package com.junkfood.seal.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.text.isDigitsOnly
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Deprecated("Use extension functions of Context to show a toast")
object ToastUtil {
    fun makeToast(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    fun makeToastSuspend(text: String) {
        applicationScope.launch(Dispatchers.Main) { makeToast(text) }
    }

    fun makeToast(stringId: Int) {
        Toast.makeText(context.applicationContext, context.getString(stringId), Toast.LENGTH_SHORT)
            .show()
    }
}

@MainThread
fun Context.makeToast(stringId: Int) {
    Toast.makeText(applicationContext, getString(stringId), Toast.LENGTH_SHORT).show()
}

@MainThread
fun Context.makeToast(message: String) {
    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}

private const val GIGA_BYTES = 1024f * 1024f * 1024f
private const val MEGA_BYTES = 1024f * 1024f
private const val KILO_BYTES = 1024f

@Composable
fun Number?.toFileSizeText(): String {
    if (this == null || this.toDouble() <= 0.0) return stringResource(id = R.string.unknown)

    return this.toFloat().run {
        when {
            this >= GIGA_BYTES -> stringResource(R.string.filesize_gb).format(this / GIGA_BYTES)
            this >= MEGA_BYTES -> stringResource(R.string.filesize_mb).format(this / MEGA_BYTES)
            else -> stringResource(R.string.filesize_kb).format(this / KILO_BYTES)
        }
    }
}

fun sanitizeBinaryUnits(text: String): String {
    return text.replace(Regex("""([KMGTPEkmgtpe])iB(/s)?""")) { match ->
        val prefix = match.groupValues[1].uppercase()
        if (match.groupValues[2].isNotEmpty()) "${prefix}B/s" else "${prefix}B"
    }
}

fun parseSpeedFromText(text: String): String? {
    val ytDlpRegex =
        Regex("""\bat\s+([~]?\s*[\d.]+)\s*([a-zA-Z]+(?:/[a-zA-Z]+)?)""", RegexOption.IGNORE_CASE)
    val match = ytDlpRegex.find(text)
    if (match != null) {
        val valueStr = match.groupValues[1].trim()
        val rawUnit = match.groupValues[2].trim()
        val unit =
            when {
                rawUnit.startsWith("G", ignoreCase = true) -> "GBps"
                rawUnit.startsWith("M", ignoreCase = true) -> "MBps"
                rawUnit.startsWith("K", ignoreCase = true) -> "KBps"
                rawUnit.startsWith("B", ignoreCase = true) -> "Bps"
                else -> "MBps"
            }
        val value = valueStr.replace("~", "").trim().toDoubleOrNull()
        return if (value != null) {
            "%.1f %s".format(value, unit)
        } else {
            "$valueStr $unit"
        }
    }
    val aria2cRegex = Regex("""DL:\s*([\d.]+)\s*([a-zA-Z]+)""", RegexOption.IGNORE_CASE)
    val ariaMatch = aria2cRegex.find(text)
    if (ariaMatch != null) {
        val valueStr = ariaMatch.groupValues[1].trim()
        val rawUnit = ariaMatch.groupValues[2].trim()
        val unit =
            when {
                rawUnit.startsWith("G", ignoreCase = true) -> "GBps"
                rawUnit.startsWith("M", ignoreCase = true) -> "MBps"
                rawUnit.startsWith("K", ignoreCase = true) -> "KBps"
                rawUnit.startsWith("B", ignoreCase = true) -> "Bps"
                else -> "MBps"
            }
        val value = valueStr.toDoubleOrNull()
        return if (value != null) {
            "%.1f %s".format(value, unit)
        } else {
            "$valueStr $unit"
        }
    }
    return null
}

fun parseFileSizeFromText(text: String): Double? {
    val ytDlpRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGTPE]?i?B)""", RegexOption.IGNORE_CASE)
    val match = ytDlpRegex.find(text)
    if (match != null) {
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val multiplier =
            when {
                unit.startsWith("K") -> 1024.0
                unit.startsWith("M") -> 1024.0 * 1024.0
                unit.startsWith("G") -> 1024.0 * 1024.0 * 1024.0
                unit.startsWith("T") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
                unit.startsWith("P") -> 1024.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0
                else -> 1.0
            }
        return value * multiplier
    }
    val aria2cRegex = Regex("""/\s*([\d.]+)\s*([KMGTPE]?i?B)""", RegexOption.IGNORE_CASE)
    val ariaMatch = aria2cRegex.find(text)
    if (ariaMatch != null) {
        val value = ariaMatch.groupValues[1].toDoubleOrNull() ?: return null
        val unit = ariaMatch.groupValues[2].uppercase()
        val multiplier =
            when {
                unit.startsWith("K") -> 1024.0
                unit.startsWith("M") -> 1024.0 * 1024.0
                unit.startsWith("G") -> 1024.0 * 1024.0 * 1024.0
                unit.startsWith("T") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
                unit.startsWith("P") -> 1024.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0
                else -> 1.0
            }
        return value * multiplier
    }
    return null
}

/** Convert time in **seconds** to `hh:mm:ss` or `mm:ss` */
fun Int.toDurationText(): String =
    this.run {
        if (this > 3600) "%d:%02d:%02d".format(this / 3600, (this % 3600) / 60, this % 60)
        else "%02d:%02d".format(this / 60, this % 60)
    }

fun String.isNumberInRange(start: Int, end: Int): Boolean {
    return this.isNotEmpty() &&
        this.isDigitsOnly() &&
        this.length < 10 &&
        this.toInt() >= start &&
        this.toInt() <= end
}

private const val URL_REGEX_PATTERN =
    "(http|https)://[\\w\\-_]+(\\.[\\w\\-_]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?"

fun String.isNumberInRange(range: IntRange): Boolean = this.isNumberInRange(range.first, range.last)

fun ClosedFloatingPointRange<Float>.toIntRange() =
    IntRange(start.roundToInt(), endInclusive.roundToInt())

fun String?.toHttpsUrl(): String =
    this?.run { if (matches(Regex("^(http:).*"))) replaceFirst("http", "https") else this } ?: ""

fun matchUrlFromClipboard(string: String, isMatchingMultiLink: Boolean = false): String {
    findURLsFromString(string, !isMatchingMultiLink).joinToString(separator = "\n").run {
        if (isEmpty()) ToastUtil.makeToast(R.string.paste_fail_msg)
        else ToastUtil.makeToast(R.string.paste_msg)
        return this
    }
}

fun matchUrlFromSharedText(s: String): String {
    findURLsFromString(s, true).joinToString(separator = "\n").run {
        if (isEmpty()) ToastUtil.makeToast(R.string.share_fail_msg)
        //            else makeToast(R.string.share_success_msg)
        return this
    }
}

fun Number?.toBitrateText(): String {
    val br = this?.toFloat() ?: return ""
    return when {
        br <= 0f -> "" // i don't care
        br < 1024f -> "%.1f Kbps".format(br)

        else -> "%.2f Mbps".format(br / 1024f)
    }
}

fun getErrorReport(th: Throwable, url: String): String =
    App.getVersionReport() + "\nURL: ${url}\n${th.message}"

@Deprecated(
    "Use findURLsFromString instead",
    ReplaceWith("findURLsFromString(s, !isMatchingMultiLink).joinToString(separator = \"\\n\")"),
)
fun matchUrlFromString(s: String, isMatchingMultiLink: Boolean = false): String =
    findURLsFromString(s, !isMatchingMultiLink).joinToString(separator = "\n")

fun findURLsFromString(input: String, firstMatchOnly: Boolean = false): List<String> {
    val result = mutableListOf<String>()
    val pattern = Pattern.compile(URL_REGEX_PATTERN)

    with(pattern.matcher(input)) {
        if (!firstMatchOnly) {
            while (find()) {
                result += group()
            }
        } else {
            if (find()) result += (group())
        }
    }
    return result
}

fun connectWithDelimiter(vararg strings: String?, delimiter: String): String =
    strings
        .toList()
        .filter { !it.isNullOrBlank() }
        .joinToString(separator = delimiter) { it.toString() }

fun connectWithBlank(s1: String, s2: String): String {
    val blank = if (s1.isEmpty() || s2.isEmpty()) "" else " "
    return s1 + blank + s2
}
