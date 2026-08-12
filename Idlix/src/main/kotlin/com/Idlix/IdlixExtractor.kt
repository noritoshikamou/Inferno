package com.Idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

object MasterLinkGenerator {
    suspend fun createLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null
    ): ExtractorLink? {
        val detectedQuality = quality ?: detectQualityFromUrl(url)
        return newExtractorLink(
            source = source,
            name = source,
            url = url,
            type = INFER_TYPE
        ) {
            this.quality = detectedQuality
            if (referer != null) this.referer = referer
            this.headers = headers ?: emptyMap()
        }
    }

    fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("1080") -> 1080
            urlLower.contains("720") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 480
        }
    }
}

suspend fun loadExtractorWithFallback(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var success = false
    try {
        if (loadExtractor(url, referer, subtitleCallback, callback)) {
            success = true
        }
    } catch (_: Exception) {}

    if (!success) {
        try {
            val doc = app.get(url, referer = referer).document
            // Coba ambil dari iframe jika ada di dalam halaman embed
            val iframe = doc.selectFirst("iframe")?.attr("src")
            if (!iframe.isNullOrEmpty()) {
                val fixedIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
                if (loadExtractor(fixedIframe, url, subtitleCallback, callback)) {
                    success = true
                }
            }
        } catch (_: Exception) {}
    }

    val urlDomain = url.removePrefix("http://").removePrefix("https://").split("/").first().lowercase()
    val matchingExtractors = IdlixEkstraktors.list.filter { extractor ->
        urlDomain.contains(extractor.mainUrl.removePrefix("http://").removePrefix("https://").split("/").first().lowercase())
    }

    for (extractor in matchingExtractors) {
        try {
            extractor.getUrl(url, referer, subtitleCallback, callback)
            success = true
        } catch (_: Exception) {}
    }
    
    return success
}

class Jeniusplay : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hash = url.split("/").last().substringAfter("data=")
            val res = app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = referer ?: mainUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            ).parsedSafe<ResponseSource>()

            res?.videoSource?.let { m3uLink ->
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = m3uLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                    }
                )
            }
        } catch (_: Exception) {}
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

object IdlixEkstraktors {
    val list = listOf(
        Jeniusplay()
    )
}
