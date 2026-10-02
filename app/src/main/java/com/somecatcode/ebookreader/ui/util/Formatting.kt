package com.somecatcode.ebookreader.ui.util

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import com.somecatcode.ebookreader.data.account.Account
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

fun formatRelativeTime(timeMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(timeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()

fun percentText(fraction: Double?): String = "${((fraction ?: 0.0) * 100).toInt().coerceIn(0, 100)} %"

/** `user@host` label of an account. */
fun Account.serverLabel(): String = serverUrl.toHttpUrlOrNull()?.let { url ->
    url.host + (if (url.encodedPath.length > 1) url.encodedPath.trimEnd('/') else "")
} ?: serverUrl

fun Account.title(): String = displayName?.takeIf { it.isNotBlank() } ?: loginName

/**
 * Normalises what the user typed into a server base URL: adds `https://`, trims a trailing `/` and
 * `/index.php`. Returns null for empty input, for plain `http://` (HTTPS only, PLAN.md section 5)
 * and for anything that is not a valid URL with a host.
 */
fun normalizeServerInput(input: String): String? {
    var value = input.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null
    value = when {
        value.startsWith("https://", ignoreCase = true) -> value
        value.contains("://") -> return null
        else -> "https://$value"
    }
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.host.isBlank() || url.query != null || url.fragment != null) return null
    var path = url.encodedPath.trimEnd('/')
    if (path.endsWith("/index.php")) path = path.removeSuffix("/index.php")
    val port = if (url.port != 443) ":${url.port}" else ""
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    return "https://$host$port$path"
}
