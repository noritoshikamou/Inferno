package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.util.Base64
import org.jsoup.nodes.Element

class Idlix : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com/"
    private var directUrl = mainUrl
    override var name = "Idlix Asia"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage =
        mainPageOf(
            "$mainUrl/" to "Featured",
            "$mainUrl/trending/page/?get=movies" to "Trending Movies",
            "$mainUrl/trending/page/?get=tv" to "Trending TV Series",
            "$mainUrl/movie/page/" to "Film Terbaru",
            "$mainUrl/genre/action/page/" to "Film Action",
            "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
            "$mainUrl/genre/anime/page/" to "Anime",
            "$mainUrl/tvseries/page/" to "Serial TV",
            "$mainUrl/season/page/" to "Season Terbaru",
            "$mainUrl/episode/page/" to "Episode Terbaru",
        )

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.split("?")
        val nonPaged = request.name == "Featured" && page <= 1
        val req =
            if (nonPaged) {
                app.get(request.data)
            } else {
                app.get("${url.first()}$page/?${url.lastOrNull()}")
            }
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home =
            (if (nonPaged) {
                    document.select("div.items.featured article, div.items.full article")
                } else {
                    document.select("div.items.full article, div#archive-content article, div.result-item")
                })
                .mapNotNull { it.toSearchResult() }
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
        val titleElement = this.selectFirst("h3 > a") ?: this.selectFirst("div.title > a") ?: return null
        val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(titleElement.attr("href"))
        val posterUrl = this.selectFirst("div.poster > img, img")?.attr("src") ?: ""
        val quality = getQualityFromString(this.selectFirst("span.quality")?.text())
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val req = app.get("$mainUrl/search/$query")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        return document.select("div.result-item, div.item").mapNotNull {
            val titleElement = it.selectFirst("div.title > a, h3 > a") ?: return@mapNotNull null
            val title = titleElement.text()
                .replace(Regex("\\(\\d{4}\\)"), "")
                .trim()
            val href = getProperLink(titleElement.attr("href"))
            val posterUrl = it.selectFirst("img")?.attr("src") ?: ""
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title =
            document.selectFirst("div.data > h1, h1.entry-title")
                ?.text()
                ?.replace(Regex("\\(\\d{4}\\)"), "")
                ?.trim()
                .toString()
        val poster = document.selectFirst("div.poster > img, div.thumb > img")?.attr("src") ?: ""
        val tags = document.select("div.sgeneros > a, span.genres > a").map { it.text() }

        val yearText = document.selectFirst("span.date, span.year")?.text() ?: ""
        val year = Regex("(\\d{4})").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val tvType =
            if (document.select("ul#section > li:nth-child(1)").text().contains("Episodes", true) ||
                document.select("div.seasons").isNotEmpty() ||
                url.contains("/tvseries/")
            )
                TvType.TvSeries
            else TvType.Movie

        val description = document.select("div.wp-content > p, div.description > p").text().trim()
        val trailer = document.selectFirst("div.embed iframe, div.trailer iframe")?.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs, span.rating")?.text()
        val actors =
            document.select("div.persons > div[itemprop=actor], div.cast div.actor").map {
                Actor(
                    it.selectFirst("meta[itemprop=name], span.name")?.attr("content") ?: it.text(),
                    it.selectFirst("img")?.attr("src")
                )
            }

        val recommendations =
            document.select("div.owl-item, div.items.recommendations article").mapNotNull {
                val el = it.selectFirst("a") ?: return@mapNotNull null
                val recName = el.attr("href").removeSuffix("/").split("/").last()
                val recHref = el.attr("href")
                val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val episodes =
                document.select("ul.episodios > li, div.les-content a").mapNotNull { it ->
                    val href = it.selectFirst("a")?.attr("href") ?: it.attr("href")
                    val titleEl = it.selectFirst("div.episodiotitle > a, span")
                    val name = fixTitle(titleEl?.text()?.trim() ?: "")
                    val image = it.selectFirst("div.imagen > img, img")?.attr("src")
                    val numerando = it.selectFirst("div.numerando")?.text() ?: ""
                    
                    val parts = numerando.replace(" ", "").split("-")
                    val season = parts.firstOrNull()?.toIntOrNull() ?: 1
                    val episode = parts.lastOrNull()?.toIntOrNull() ?: 1

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
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
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
        directUrl = getBaseUrl(document.location())

        document.select("ul#playeroptionsul > li")
            .map { Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type")) }
            .amap { (id, nume, type) ->
                try {
                    val json =
                        app.post(
                                url = "$directUrl/wp-admin/admin-ajax.php",
                                data =
                                    mapOf(
                                        "action" to "doo_player_ajax",
                                        "post" to id,
                                        "nume" to nume,
                                        "type" to type
                                    ),
                                referer = data,
                                headers =
                                    mapOf(
                                        "Accept" to "*/*",
                                        "X-Requested-With" to "XMLHttpRequest"
                                    )
                            )
                            .parsedSafe<ResponseHash>()
                            ?: return@amap

                    val password = createKey(json.key, json.embedurl)
                    val decrypted = CryptoJsAes.decrypt(json.embedurl, password) ?: return@amap

                    val embedJson =
                        AppUtils.tryParseJson<Map<String, String>>(decrypted as String?)
                            ?: return@amap
                    val hash = embedJson["m"]?.split("/")?.last() ?: return@amap

                    getUrl(
                        url = "https://jeniusplay.com/player/index.php?data=$hash&do=getVideo",
                        referer = directUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                } catch (e: Exception) {
                    println("Error processing player: ${e.message}")
                }
            }

        return true
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.chunked(4).map { it.substring(2) }
        val reversedM = m.reversed()

        val paddedM = addBase64Padding(reversedM)
        val decodedBytes = Base64.getDecoder().decode(paddedM)
        val decodedM = String(decodedBytes, Charsets.UTF_8)

        return decodedM.split("|").joinToString("") { "\\x${rList.getOrNull(it.toInt()) ?: "00"}" }
    }

    private fun addBase64Padding(input: String): String {
        return input + "=".repeat((4 - input.length % 4) % 4)
    }

    private suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val m3uResponse =
                app.post(
                        url = url,
                        data =
                            mapOf(
                                "hash" to url.split("data=").last(),
                                "r" to (referer ?: "")
                            ),
                        headers =
                            mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            )
                    )
                    .parsedSafe<ResponseSource>()
                    ?: return

            M3u8Helper.generateM3u8(name, m3uResponse.videoSource, referer ?: directUrl)
                .forEach(callback)

            val document = app.get(url, referer = referer).document
            document.select("script")
                .find { script -> script.data().contains("eval(function(p,a,c,k,e,d)") }
                ?.let { script ->
                    val subData =
                        getAndUnpack(script.data())
                            .substringAfter("\"tracks\":[")
                            .substringBefore("],")

                    AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                        subtitleCallback.invoke(
                            SubtitleFile(getLanguage(subtitle.label ?: ""), subtitle.file)
                        )
                    }
                }
        } catch (e: Exception) {
            println("Error in getUrl: ${e.message}")
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) -> "Indonesian"
            str.contains("english", true) -> "English"
            else -> str
        }
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
        @JsonProperty("embedurl") val embedurl: String,
        @JsonProperty("key") val key: String,
    )
}
