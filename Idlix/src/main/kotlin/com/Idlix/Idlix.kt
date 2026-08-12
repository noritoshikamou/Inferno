package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class Idlix : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    private var directUrl = mainUrl
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Beranda",
        "$mainUrl/movie" to "Film",
        "$mainUrl/series" to "Serial TV"
    )

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val req = app.get(request.data)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val items = document.select("a[href*='/movie/'], a[href*='/series/']").mapNotNull { element ->
            try {
                val href = element.attr("href")
                if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/series/"))) return@mapNotNull null
                
                // Pencarian gambar yang lebih komprehensif di dalam elemen link atau pembungkusnya
                val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
                val posterUrl = extractBestImage(img)
                
                // Ambil teks judul atau fallback ke atribut alt gambar
                val title = element.text().ifBlank { 
                    element.selectFirst("h3, h2, .title")?.text() ?: img?.attr("alt") ?: "" 
                }.trim()
                
                if (title.isBlank() || title.length < 2) return@mapNotNull null

                val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
                
                newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun fixImageUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val clean = url.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> clean
        }
    }

    private fun extractBestImage(img: Element?): String {
        if (img == null) return ""
        val attrs = listOf("src", "data-src", "data-lazy-src", "data-original", "data-srcset")
        for (attr in attrs) {
            val value = img.attr(attr)
            if (value.isNotBlank() && !value.contains("data:image") && !value.contains("pixel")) {
                // Handle jika menggunakan srcset (mengambil link pertama/terakhir yang valid)
                val targetUrl = if (attr == "data-srcset" || attr == "srcset") {
                    value.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                } else {
                    value
                }
                if (!targetUrl.isNullOrBlank()) {
                    return fixImageUrl(targetUrl)
                }
            }
        }
        return ""
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/?s=$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        return document.select("a[href*='/movie/'], a[href*='/series/']").mapNotNull { element ->
            try {
                val href = element.attr("href")
                if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/series/"))) return@mapNotNull null
                
                val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
                val posterUrl = extractBestImage(img)
                val title = element.text().ifBlank { 
                    element.selectFirst("h3, h2, .title")?.text() ?: img?.attr("alt") ?: "" 
                }.trim()
                
                if (title.isBlank()) return@mapNotNull null

                val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val posterElement = document.selectFirst(".poster img, .thumb img, main img, img")
        val poster = extractBestImage(posterElement)
        
        val description = document.selectFirst(".synopsis p, .entry-content p, p")?.text()?.trim()
        val year = document.selectFirst(".date, time")?.text()?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = document.select(".genres a, .soter a").map { it.text() }
        val rating = document.selectFirst(".rating, .score")?.text()?.toDoubleOrNull()

        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li, .episodios li, a[href*='/episode/']").mapNotNull { el ->
                try {
                    val href = el.selectFirst("a")?.attr("href") ?: el.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    val name = el.selectFirst(".title, span")?.text()?.trim() ?: "Episode"
                    newEpisode(href) {
                        this.name = name
                    }
                } catch (e: Exception) {
                    null
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("iframe, .player-option").forEach { element ->
            try {
                val src = element.attr("src").ifBlank { element.attr("data-url") }
                if (src.isNotBlank() && !src.contains("youtube")) {
                    loadExtractor(fixUrl(src), directUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Ignore individual link errors
            }
        }
        return true
    }
}
