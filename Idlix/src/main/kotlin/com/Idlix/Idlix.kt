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
        "$mainUrl/collection" to "Koleksi",
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
        
        val home = document.select("div.grid article, main article, div.group, .item-root, a[href*='/movie/'], a[href*='/series/']").mapNotNull {
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

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else (this.selectFirst("a") ?: return null)
        val href = getProperLink(aTag.attr("href"))
        if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/series/"))) return null
        
        val titleElement = this.selectFirst("h3, h2, .title, span") ?: aTag
        val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val posterUrl = if (imgElement != null) {
            val dataSrc = imgElement.attr("data-src")
            val dataLazy = imgElement.attr("data-lazy-src")
            val dataOrig = imgElement.attr("data-original")
            val src = imgElement.attr("src")
            when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazy.isNotEmpty() -> dataLazy
                dataOrig.isNotEmpty() -> dataOrig
                src.isNotEmpty() && !src.contains("data:image") -> src
                else -> ""
            }
        } else ""

        val quality = getQualityFromString(this.select("span.quality, .badge").text())
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
        return document.select("div.grid article, main article, div.group, a").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        
        val title = document.selectFirst("h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")?.trim().toString()
        
        val posterElement = document.selectFirst("div.poster img, img.poster, main img, .thumb img, .entry-cover img")
        val poster = if (posterElement != null) {
            val dataSrc = posterElement.attr("data-src")
            val dataLazy = posterElement.attr("data-lazy-src")
            val dataOrig = posterElement.attr("data-original")
            val src = posterElement.attr("src")
            when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazy.isNotEmpty() -> dataLazy
                dataOrig.isNotEmpty() -> dataOrig
                src.isNotEmpty() && !src.contains("data:image") -> src
                else -> ""
            }
        } else ""

        val tags = document.select("div.genres a, .tags a, span.genre").map { it.text() }
        
        val yearText = document.select("span.date, .released, time").text().trim()
        val year = Regex("(\\d{4})").find(yearText)?.groupValues?.get(1)?.toIntOrNull()
        
        val tvType = if (url.contains("/series/") || document.select(".seasons, ul.episodios").isNotEmpty()) TvType.TvSeries else TvType.Movie
        val description = document.select("div.synopsis p, div.content p, article p").text().trim()
        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
        
        val rating = document.selectFirst("span.rating, .score")?.text()?.toDoubleOrNull()
        val actors = document.select("div.cast-item, .actor").map {
            Actor(it.select(".name, span").text(), it.select("img").attr("src"))
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
                val image = if (epImgElement != null) {
                    val dSrc = epImgElement.attr("data-src")
                    val dLazy = epImgElement.attr("data-lazy-src")
                    val sSrc = epImgElement.attr("src")
                    when {
                        dSrc.isNotEmpty() -> dSrc
                        dLazy.isNotEmpty() -> dLazy
                        sSrc.isNotEmpty() && !sSrc.contains("data:image") -> sSrc
                        else -> ""
                    }
                } else ""

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
