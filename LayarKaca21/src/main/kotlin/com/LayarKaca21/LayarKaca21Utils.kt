package com.LayarKaca21

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import java.net.URI

object AutoUsedConstants {
    const val DEFAULT_TIMEOUT = 10000L
}

fun logDebug(tag: String, message: String) = Log.d(tag, message)

fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message)
    error?.let { Log.e(tag, "Cause: ${it.message}") }
}

fun Element.extractImageAttr(): String {
    val attrs = listOf(
        "data-src",
        "src",
        "data-original",
        "data-lazy-src",
        "data-srcset",
        "",
    )
    return attrs
        .asSequence()
        .map { attr(it) }
        .firstOrNull { it.isNotBlank() }
        ?.split(" ")
        ?.firstOrNull() ?: ""
}

fun getBaseUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        ""
    }
}
