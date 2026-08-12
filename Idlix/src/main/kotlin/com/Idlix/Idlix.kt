package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
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
        "$mainUrl/trending/page/?get=movies" to "Trending Movies",
        "$mainUrl/trending/page/?get=tv" to "Trending TV Series",
        "$mainUrl/movie/page/" to "Movie Terbaru",
        "$mainUrl/tvseries/page/" to "TV Series Terbaru",
        "$mainUrl/network/amazon/page/" to "Amazon Prime",
        "$mainUrl/network/apple-tv/page/" to "Apple TV+ Series",
        "$mainUrl/network/disney/page/" to "Disney+ Series",
        "$mainUrl/network/HBO/page/" to "HBO Series",
        "$mainUrl/network/netflix/page/" to "Netflix Series",
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
        val url = request.data.split("?")
        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data)
        } else {
            app.get("${url.first()}$page/?${url.lastOrNull()}")
        }
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        // Menyesuaikan penyeleksi untuk struktur Next.js / layout baru
        val home = (if (nonPaged) {
            document.select("div.items.featured article, div.grid article, main article")
        } else {
            document.select("div.items.full article, div#archive-content article, div.grid article, main article")
        }).mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3 > a, h2 > a, a.title") ?: return null
        val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(titleElement.attr("href"))
        val posterUrl = this.selectFirst("div.poster img, img")?.attr("src") ?: ""
        val quality = getQualityFromString(this.select("span.quality").text())
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search/$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        return document.select("div.result-item, article").mapNotNull {
            val titleElement = it.selectFirst("div.title > a, h3 > a") ?: return@mapNotNull null
            val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(titleElement.attr("href"))
            val posterUrl = it.selectFirst("img")?.attr("src") ?: ""
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title =
            document.selectFirst("div.data > h1, h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")
                ?.trim().toString()
        val images = document.select("div.g-item")

        val poster = images
            .shuffled()
            .firstOrNull()
            ?.selectFirst("a")
            ?.attr("href")
            ?: document.select("div.poster > img, img.poster").attr("src")
        val tags = document.select("div.sgeneros > a, .genres a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date, .released").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1), .seasons").text().contains("Episodes")
        ) TvType.TvSeries else TvType.Movie
        
        val description = if (tvType == TvType.Movie) 
            document.select("div.wp-content > p, .synopsis p").text().trim() else 
            document.select("div.content > center > p:nth-child(3), .synopsis p").text().trim()
            
        val trailer = document.selectFirst("div.embed iframe, iframe")?.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs[itemprop=ratingValue], .rating-value")
        ?.text()
        ?.toDoubleOrNull()
        
        val actors = document.select("div.persons > div[itemprop=actor], .cast-item").map {
            Actor(it.select("meta[itemprop=name], .name").attr("content").ifEmpty { it.text() }, it.select("img").attr("src"))
        }
        val duration = document.selectFirst("div.extra span[itemprop=duration], .duration")?.text()
                        ?.replace(Regex("\\D"), "")
                        ?.toIntOrNull() ?: 0
                        
        val recommendations = document.select("#single_relacionados article, .related article").map {
            val imgEl = it.selectFirst("img")
            val aEl = it.selectFirst("a")
            val recName = imgEl?.attr("alt")?.replace(Regex("\\(\\d{4}\\)"), "") ?: ""
            val recHref = aEl?.attr("href") ?: ""
            val recPosterUrl = imgEl?.attr("src").toString()
            newMovieSearchResponse(recName, recHref,
                if (recHref.contains("/movie/")) TvType.Movie 
                    else TvType.TvSeries
            ) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li, .episode-item").map {
                val href = it.select("a").attr("href")
                val name = fixTitle(it.select("div.episodiotitle > a, .ep-title").text().trim())
                val image = it.select("div.imagen > img, img").attr("src")
                val numerandoText = it.select("div.numerando, .ep-number").text()
                val episode = numerandoText.replace(" ", "").split("-").last().toIntOrNull()
                val season = numerandoText.replace(" ", "").split("-").first().toIntOrNull()
                newEpisode(href) {
                    this.name = name
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
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = document.select("script:containsData(window.idlix)").toString()
        val match = scriptRegex.find(script)
        val idlixNonce = match?.groups?.get(1)?.value ?: ""
        val idlixTime = match?.groups?.get(2)?.value ?: ""

        document.select("ul#playeroptionsul > li, .player-option").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type, "_n" to idlixNonce, "_p" to id, "_t" to idlixTime
                ),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>() ?: return@amap
            val metrix = AppUtils.parseJson<AesData>(json.embed_url).m
            val password = createKey(json.key, metrix)
            val decrypted =
                AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)
                    ?.fixBloat() ?: return@amap
            Log.d("Phisher", decrypted.toJson())

            when {
                !decrypted.contains("youtube") ->
                    loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                else -> return@amap
            }
        }

        return true
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) {
            return ""
        }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) {
                    n += "\\x" + rList[index]
                }
            } catch (_: Exception) {
            }
        }
        return n
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
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
