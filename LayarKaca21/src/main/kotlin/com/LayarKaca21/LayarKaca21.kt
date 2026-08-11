package com.LayarKaca21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LayarKaca21 : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    override var name = "LK21"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "latest-series" to "Series Terbaru",
        "series/ongoing" to "Series Ongoing",
        "series/complete" to "Series Complete",
        "populer" to "Terpopuler",
        "rekomendasi-film-pintar" to "Rekomendasi",
        "year/2026" to "2026",
        "latest" to "Film Terbaru",
        "nontondrama" to "Series Unggulan",
        "series/update" to "Series Update",
        "quality/bluray" to "Bluray Terbaru",
        "genre/action" to "Action Terbaru",
        "genre/drama" to "Drama Terbaru",
        "genre/horror" to "Horror Terbaru",
        "genre/animation" to "Animation Terbaru",
        "genre/comedy" to "Comedy Terbaru",
        "genre/romance" to "Romance Terbaru",
        "country/south-korea" to "Korea Terbaru",
        "country/thailand" to "Thailand Terbaru",
        "country/india" to "India Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            "$mainUrl/page/$page/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }
        
        val document = app.get(url).document
        val items = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("figure a") ?: selectFirst("a") ?: return null
        val title = selectFirst("h3.poster-title")?.text()?.trim() ?: aTag.attr("title").trim()
        if (title.isEmpty()) return null
    
        val href = fixUrl(aTag.attr("href"))
        val imgElement = selectFirst("img")
        val poster = imgElement?.attr("data-src")
            .takeIf { !it.isNullOrEmpty() }
            ?: imgElement?.attr("data-original")
                .takeIf { !it.isNullOrEmpty() }
            ?: imgElement?.attr("src")
                .takeIf { !it.isNullOrEmpty() }
            ?: selectFirst("div.poster img")?.attr("src")
        
        val cleanPoster = poster?.trim()
        val episodeText = selectFirst("span.episode")?.text()
        val isSeries = episodeText != null || selectFirst("span.duration")?.text()?.contains("S.") == true || href.contains("series")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = cleanPoster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = cleanPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, h2.entry-title, .m-title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: doc.selectFirst(".poster img, .foto img, img.attachment-post-thumbnail")?.attr("src")
        val description = doc.selectFirst("div.synopsis, div.desc, meta[name='description']")?.attr("content") 
            ?: doc.selectFirst("div.entry-content")?.text()

        val rating = doc.selectFirst("span[itemprop='ratingValue'], .rating")?.text()?.trim()
        val year = doc.selectFirst("span[itemprop='datePublished'], .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = doc.select(".genres a, .genre a, meta[itemprop='genre']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodeElements = doc.select(".episodelist ul li a, .seasons-box a, .list-eps a, .episodenotice a, .eps-list a")
        
        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapIndexed { index, element ->
                val epHref = fixUrl(element.attr("href"))
                val epName = element.text().trim()
                newEpisode(epHref) {
                    name = epName
                    episode = index + 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addScore(rating)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addScore(rating)
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
    
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { !it.isNullOrEmpty() && it.startsWith("http") } 
                ?: iframe.attr("data-src").takeIf { !it.isNullOrEmpty() }
            
            if (src != null && !src.contains("facebook") && !src.contains("telegram")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
