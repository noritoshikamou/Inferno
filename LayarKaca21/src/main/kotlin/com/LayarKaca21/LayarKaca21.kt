package com.LayarKaca21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class LayarKaca21 : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    private var seriesUrl = "https://tv12.lk21official.cc"
    private var searchUrl = "https://tv12.lk21official.cc"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-series/" to "Series Terbaru",
        "$mainUrl/series/ongoing/" to "Series Ongoing",
        "$mainUrl/series/complete/" to "Series Complete",
        "$mainUrl/year/2026/" to "Katalog Utama",
        "$mainUrl/populer/" to "Terpopuler",
        "$mainUrl/latest/" to "Film Terbaru",
        "$mainUrl/quality/bluray/" to "Bluray",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/drama/" to "Drama",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/animation/" to "Animation",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/romance/" to "Romance",
        "$mainUrl/country/china/" to "China",
        "$mainUrl/country/india/" to "India",
        "$mainUrl/country/japan/" to "Jepang",
        "$mainUrl/country/south-korea/" to "Korea",
        "$mainUrl/country/thailand/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.endsWith("/")) {
            "${request.data}page/$page/"
        } else {
            "${request.data}/page/$page/"
        }
        val document = app.get(targetUrl).document
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
        val title = this.selectFirst("h3.poster-title")?.text()?.trim() 
            ?: this.selectFirst("h3")?.text()?.trim() 
            ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href").orEmpty())
        val posterUrl = fixUrlNull(this.selectFirst("img")?.extractImageAttr().orEmpty())
        
        // Mengambil skor rating berdasarkan itemprop ratingValue dari HTML
        val ratingText = this.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
            ?: this.selectFirst("span.rating")?.text()?.trim()
            
        // Mengambil jenis video / kualitas (misal: HD, CAM, atau Web jika kosong)
        val qualityText = this.selectFirst("span.label")?.text()?.trim() 
            ?: this.selectFirst("span.quality")?.text()?.trim() 
            ?: "Web"

        val type = if (this.selectFirst("span.episode") == null) TvType.Movie else TvType.TvSeries
        val posterheaders = mapOf("Referer" to getBaseUrl(posterUrl ?: ""))

        return if (type == TvType.TvSeries) {
            val episode = this
                .selectFirst("span.episode strong")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull() ?: 0
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                addQuality(qualityText)
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                addQuality(qualityText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val document = app.get("$searchUrl/?s=$query").document
            results.addAll(
                document.select("article figure").mapNotNull { runCatching { it.toSearchResult() }.getOrNull() }
            )
        } catch (_: Exception) {
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
            runCatching { it.toSearchResult() }.getOrNull() 
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
