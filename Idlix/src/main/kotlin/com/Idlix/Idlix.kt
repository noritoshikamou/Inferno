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
        "$mainUrl/" to "Featured",
        "$mainUrl/movie" to "Film Terbaru",
        "$mainUrl/series" to "Serial TV Terbaru",
    )

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val req = app.get(request.data)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val home = document.select("a.content-card, div.item, article, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return try {
            when {
                uri.contains("/episode/") || uri.contains("/season/") -> {
                    val cleanUri = uri.substringBefore("?")
                    val parts = cleanUri.split("/")
                    val title = parts.getOrNull(parts.indexOf("series") + 1) ?: parts.lastOrNull() ?: ""
                    "$mainUrl/series/$title"
                }
                else -> uri
            }
        } catch (e: Exception) {
            uri
        }
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

    private fun extractImageUrl(element: Element?): String {
        if (element == null) return ""
        try {
            val srcset = element.attr("srcset")
            if (srcset.isNotBlank()) {
                val lastSrc = srcset.split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!lastSrc.isNullOrBlank()) return fixImageUrl(lastSrc)
            }

            val attrs = listOf("src", "data-src", "data-lazy-src", "data-original", "data-bg")
            for (attr in attrs) {
                val value = element.attr(attr)
                if (value.isNotBlank() && !value.contains("data:image") && !value.contains("pixel")) {
                    return fixImageUrl(value)
                }
            }

            val style = element.attr("style")
            if (style.contains("background-image")) {
                val regex = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)
                val bgUrl = regex?.groupValues?.get(1)
                if (!bgUrl.isNullOrBlank()) return fixImageUrl(bgUrl)
            }
        } catch (e: Exception) {
            // Ignore parsing error for images
        }
        return ""
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            val aTag = if (this.hasClass("content-card") || this.tagName() == "a") this else (this.selectFirst("a.content-card, a[href*='/movie/'], a[href*='/series/']") ?: return null)
            val href = getProperLink(aTag.attr("href"))
            if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/series/"))) return null
            
            val imgElement = aTag.selectFirst("img")
            val titleElement = aTag.selectFirst("h3.text-sm, h3, h2, .title")
            
            val title = titleElement?.text()?.ifBlank {
                imgElement?.attr("alt") ?: ""
            }?.replace(Regex("\\(\\d{4}\\)"), "")?.trim() ?: ""

            if (title.isBlank()) return null

            val posterUrl = extractImageUrl(imgElement)
            val quality = getQualityFromString(aTag.select("span.quality, .badge, div[class*='quality'], .res").text())
            val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val req = app.get("$mainUrl/search?q=$query")
            mainUrl = getBaseUrl(req.url)
            val document = req.document
            document.select("a.content-card, div.item, article, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val request = app.get(url)
            directUrl = getBaseUrl(request.url)
            val document = request.document
            
            val title = document.selectFirst("h1, .sheader .data h1, .entry-title")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")?.trim() ?: "Unknown"
            
            val posterElement = document.selectFirst("div.poster img, img.poster, main img, .thumb img, .entry-cover img, .poster-container img, .sheader .poster img")
            var poster = extractImageUrl(posterElement)
            if (poster.isBlank()) {
                val altPoster = document.selectFirst("div.poster, .thumb, [style*='background-image']")
                poster = extractImageUrl(altPoster)
            }

            val tags = document.select("div.genres a, .tags a, span.genre, .sheader .genres a").map { it.text() }
            
            val yearText = document.select("span.date, .released, time, .custom_fields").text().trim()
            val year = Regex("(\\d{4})").find(yearText)?.groupValues?.get(1)?.toIntOrNull()
            
            val tvType = if (url.contains("/series/") || document.select(".seasons, ul.episodios, .episodios").isNotEmpty()) TvType.TvSeries else TvType.Movie
            val description = document.select("div.synopsis p, div.content p, article p, .wp-content p").text().trim()
            val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
            
            val rating = document.selectFirst("span.rating, .score, .dt_rating-stat")?.text()?.toDoubleOrNull()
            val actors = document.select("div.cast-item, .actor, .person").map {
                Actor(it.select(".name, span").text(), extractImageUrl(it.selectFirst("img")))
            }
            
            val duration = document.selectFirst("span.duration, .runtime")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull() ?: 0

            val recommendations = document.select("a.content-card, div.item, article, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
                it.toSearchResult()
            }

            if (tvType == TvType.TvSeries) {
                val episodes = document.select("ul.episodios > li, .episode-item, a[href*='/episode/'], .episodios li").mapNotNull {
                    try {
                        val href = it.select("a").attr("href").ifEmpty { it.attr("href") }
                        if (href.isBlank()) return@mapNotNull null
                        val epName = fixTitle(it.select(".title, span, .episodiotitle").text().trim())
                        
                        val epImgElement = it.selectFirst("img")
                        val image = extractImageUrl(epImgElement)

                        val numerandoText = it.select(".numerando, .ep-number").text()
                        val episode = Regex("E(pisode)?\\s?(\\d+)").find(numerandoText)?.groupValues?.get(2)?.toIntOrNull()
                        val season = Regex("S(eason)?\\s?(\\d+)").find(numerandoText)?.groupValues?.get(2)?.toIntOrNull()
                        
                        newEpisode(href) {
                            this.name = epName
                            this.season = season
                            this.episode = episode
                            this.posterUrl = image
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.duration = duration
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.duration = duration
                    this.tags = tags
                    if (rating != null) addScore(rating.toString(), 10)
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val serverElements = document.select("ul#playeroptionsul > li, .player-option, iframe")
            
            serverElements.forEach { element ->
                try {
                    val dataId = element.attr("data-post")
                    val dataNum = element.attr("data-nume")
                    val dataType = element.attr("data-type")
                    
                    var playerUrl = element.attr("data-url").ifEmpty { element.attr("src") }
                    
                    if (playerUrl.isEmpty() && dataId.isNotEmpty() && dataNum.isNotEmpty()) {
                        val ajaxUrl = "$directUrl/wp-admin/admin-ajax.php"
                        val res = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "player_ajax",
                                "post" to dataId,
                                "nume" to dataNum,
                                "type" to dataType
                            ),
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).text
                        
                        val doc = org.jsoup.Jsoup.parse(res)
                        playerUrl = doc.selectFirst("iframe")?.attr("src") ?: ""
                    }

                    if (playerUrl.isNotEmpty() && !playerUrl.contains("youtube")) {
                        loadExtractor(fixUrl(playerUrl), directUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Lanjutkan jika salah satu server error
                }
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }
}
