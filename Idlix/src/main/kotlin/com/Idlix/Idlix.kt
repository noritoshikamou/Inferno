package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest
        ): HomePageResponse {
            val req = app.get(request.data)
            mainUrl = getBaseUrl(req.url)
            val document = req.document
        
            val home = document.select("div.grid article, main article, div.group, .item-root, div[class*='grid'] a, div[class*='flex'] a, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        
            return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") || uri.contains("/season/") -> {
                val cleanUri = uri.substringBefore("?")
                val parts = cleanUri.split("/")
                val title = parts.getOrNull(parts.indexOf("series") + 1) ?: parts.lastOrNull() ?: ""
                "$mainUrl/series/$title"
            }
            else -> uri
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
        
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-bg", "src", "srcset")
        for (attr in attrs) {
            val value = element.attr(attr)
            if (value.isNotBlank() && !value.contains("data:image") && !value.contains("pixel")) {
                if (attr == "srcset") {
                    val firstSrc = value.split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull()
                    if (!firstSrc.isNullOrBlank()) return fixImageUrl(firstSrc)
                } else {
                    return fixImageUrl(value)
                }
            }
        }

        val style = element.attr("style")
        if (style.contains("background-image")) {
            val regex = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)
            val bgUrl = regex?.groupValues?.get(1)
            if (!bgUrl.isNullOrBlank()) return fixImageUrl(bgUrl)
        }

        return ""
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else (this.selectFirst("a") ?: this.selectFirst("a[href*='/movie/'], a[href*='/series/']") ?: return null)
        val href = getProperLink(aTag.attr("href"))
        if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/series/"))) return null
        
        val titleElement = this.selectFirst("h3, h2, .title, span[class*='title'], div[class*='title']") ?: aTag
        val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        var posterUrl = extractImageUrl(imgElement)
        
        if (posterUrl.isBlank()) {
            val bgElement = this.selectFirst("[style*='background-image'], [data-bg], [data-background]")
            posterUrl = extractImageUrl(bgElement)
        }

        val quality = getQualityFromString(this.select("span.quality, .badge, div[class*='quality']").text())
        val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

   override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search?q=$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        return document.select("div.grid article, main article, div.group, div[class*='grid'] a, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        
        val title = document.selectFirst("h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().toString()
        
        val posterElement = document.selectFirst("div.poster img, img.poster, main img, .thumb img, .entry-cover img, .poster-container img")
        var poster = extractImageUrl(posterElement)
        if (poster.isBlank()) {
            val altPoster = document.selectFirst("div.poster, .thumb, [style*='background-image']")
            poster = extractImageUrl(altPoster)
        }

        val tags = document.select("div.genres a, .tags a, span.genre").map { it.text() }
        
        val yearText = document.select("span.date, .released, time").text().trim()
        val year = Regex("(\\d{4})").find(yearText)?.groupValues?.get(1)?.toIntOrNull()
        
        val tvType = if (url.contains("/series/") || document.select(".seasons, ul.episodios").isNotEmpty()) TvType.TvSeries else TvType.Movie
        val description = document.select("div.synopsis p, div.content p, article p").text().trim()
        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
        
        val rating = document.selectFirst("span.rating, .score")?.text()?.toDoubleOrNull()
        val actors = document.select("div.cast-item, .actor").map {
            Actor(it.select(".name, span").text(), extractImageUrl(it.selectFirst("img")))
        }
        
        val duration = document.selectFirst("span.duration")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull() ?: 0

        val recommendations = document.select("div.related article, .recommendations a").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li, .episode-item, a[href*='/episode/']").map {
                val href = it.select("a").attr("href").ifEmpty { it.attr("href") }
                val epName = fixTitle(it.select(".title, span").text().trim())
                
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
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("ul#playeroptionsul > li, .player-option, iframe").amap { element ->
            val playerUrl = element.attr("data-url").ifEmpty { element.attr("src") }
            if (playerUrl.isNotEmpty() && !playerUrl.contains("youtube")) {
                loadExtractor(playerUrl, directUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }

    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )
}
