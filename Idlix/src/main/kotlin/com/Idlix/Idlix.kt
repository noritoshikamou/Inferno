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
        "$mainUrl/movie" to "Movie Terbaru",
        "$mainUrl/series" to "TV Series Terbaru",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/adventure" to "Adventure",
        "$mainUrl/genre/animation" to "Animation",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/crime" to "Crime",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/science-fiction" to "Science Fiction",
        "$mainUrl/genre/thriller" to "Thriller"
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val req = app.get(request.data)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val home = document.select("article.item, div.item, .result-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home)
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

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = aTag.attr("href")
        if (href.isBlank()) return null
        
        val title = this.selectFirst(".title, h3, h2")?.text()?.trim() ?: aTag.attr("title")
        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val posterUrl = fixImageUrl(imgElement?.attr("data-src")?.ifEmpty { imgElement?.attr("src") })
        
        val quality = getQualityFromString(this.select(".quality, .mepo").text())
        val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search?q=$query")
        mainUrl = getBaseUrl(req.url)
        return req.document.select("article.item, div.item, .result-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        
        val title = document.selectFirst("h1.entry-title, .sheader .data h1")?.text()?.trim() ?: "Unknown"
        val poster = fixImageUrl(document.selectFirst("div.poster img, .sheader .poster img")?.attr("src"))
        val tags = document.select("div.genres a, .sheader .genres a").map { it.text() }
        
        val year = document.selectFirst("span.date, time")?.text()?.let { 
            Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() 
        }
        
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.synopsis p, .wp-content p")?.text()?.trim()
        val rating = document.selectFirst("span.rating, .dt_rating-stat")?.text()?.toDoubleOrNull()
        val actors = document.select(".actor, .cast-item").map {
            Actor(it.select(".name").text(), fixImageUrl(it.selectFirst("img")?.attr("src")))
        }

        val recommendations = document.select("div.related article, .cates div.item").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").map {
                val epHref = it.select("a").attr("href")
                val epName = it.select(".title").text().trim()
                val epImage = fixImageUrl(it.selectFirst("img")?.attr("src"))
                val epNumText = it.select(".numerando").text()
                
                val episode = Regex("E(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                val season = Regex("S(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                
                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epImage
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating, 10)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating, 10)
                addActors(actors)
                this.recommendations = recommendations
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
        document.select("ul#playeroptionsul > li").amap { element ->
            val playerUrl = element.attr("data-url")
            if (playerUrl.isNotEmpty() && !playerUrl.contains("youtube")) {
                loadExtractor(playerUrl, directUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
