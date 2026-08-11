package com.LayarKaca21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class LayarKaca21 : MainAPI() {
    override var mainUrl = "https://lk21.de"
    private var seriesUrl = "https://series.lk21.de"
    private var searchUrl = "https://gudangvape.com"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
        "$mainUrl/populer/page/" to "Film Terplopuler",
        "$mainUrl/nonton-bareng-keluarga/page/" to "Nonton Bareng Keluarga",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "$mainUrl/genre/horror/page/" to "Film Horor Terbaru",
        "$mainUrl/genre/comedy/page/" to "Film Comedy Terbaru",
        "$mainUrl/country/thailand/page/" to "Film Thailand Terbaru",
        "$mainUrl/country/china/page/" to "Film China Terbaru",
        "$seriesUrl/latest-series/page/" to "Series Terbaru",
        "$seriesUrl/series/asian/page/" to "Film Asian Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("article figure").mapNotNull { runCatching { it.toSearchResult() }.getOrNull() }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun normalizeLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        return try {
            val res = app.get(url).document
            res.selectFirst("a#openNow")?.attr("href") ?: res.selectFirst("div.links a")?.attr("href") ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.ownText()?.trim() ?: this.selectFirst("h3")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href").orEmpty())
        val posterUrl = fixUrlNull(this.selectFirst("img")?.extractImageAttr().orEmpty())
        val ratingText = this.selectFirst("span.rating")?.ownText()?.trim()
        val type = if (this.selectFirst("span.episode") == null) TvType.Movie else TvType.TvSeries
        val posterheaders = mapOf("Referer" to getBaseUrl(posterUrl ?: ""))

        return if (type == TvType.TvSeries) {
            val episode = this
                .selectFirst("span.episode strong")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull() ?: 0
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                addQuality(this@toSearchResult.select("div.quality").text().trim())
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val refer = app.get(mainUrl).url
        val res = app.get("$searchUrl/search.php?s=$query", referer = refer).text
        val results = mutableListOf<SearchResponse>()
        val root = JSONObject(res)
        val arr = root.getJSONArray("data")

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = item.getString("title")
            val slug = item.getString("slug")
            val type = item.getString("type")
            val posterUrl = "https://static-jpg.lk21.party/wp-content/uploads/" + item.optString("poster")
            if (type == "series") {
                results.add(
                    newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixUrl = normalizeLink(url)
        val document = app.get(fixUrl).document

        val baseUrl = getBaseUrl(fixUrl)
        val title = document.selectFirst("div.movie-info h1")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: "Unknown Title"
        val poster = document.selectFirst("div.poster img")?.extractImageAttr().orEmpty()
        val tags = document.select("div.tag-list span").map { it.text() }
        val posterHeaders = mapOf("Referer" to getBaseUrl(poster))
        val year = Regex("\\d, (\\d+)")
            .find(document.select("div.movie-info h1").text().trim())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        val tvType = if (document.selectFirst("#season-data") != null) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.meta-info")?.text()?.trim()
            ?: document.selectFirst("div.description")?.text()?.trim()
            ?: ""
        val trailer = document.selectFirst("ul.action-left > li:nth-child(3) > a")?.attr("href")
        val rating = document.selectFirst("div.info-tag strong")?.text()

        val recommendations = document.select("li.slider article").mapNotNull {
            val recTitle = it.selectFirst("h3")?.text()?.trim().orEmpty()
            val recHref = baseUrl + it.selectFirst("a")?.attr("href").orEmpty()
            newTvSeriesSearchResponse(recTitle, recHref, TvType.TvSeries) {
                this.posterUrl = fixUrl(it.selectFirst("img")?.extractImageAttr().orEmpty())
                this.posterHeaders = posterHeaders
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            val json = document.selectFirst("script#season-data")?.data()
            if (!json.isNullOrEmpty()) {
                val root = JSONObject(json)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val seasonKey = keys.next()
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        episodes.add(
                            newEpisode(fixUrl("$baseUrl/" + ep.getString("slug"))) {
                                this.name = "Episode ${ep.optInt("episode_no")}"
                                this.season = ep.optInt("s")
                                this.episode = ep.optInt("episode_no")
                            }
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
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
        val videolar = document.select("ul#player-list a")
        if (videolar.isEmpty()) return false

        for (video in videolar) {
            try {
                val playerAl = app.get(video.attr("href"), referer = "$mainUrl/").document
                var iframe = playerAl.selectFirst("iframe")?.attr("src").toString()
                if (iframe.contains("short.icu")) {
                    iframe = app.get(iframe, allowRedirects = true).url
                }

                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
            }
        }
        return true
    }
}
