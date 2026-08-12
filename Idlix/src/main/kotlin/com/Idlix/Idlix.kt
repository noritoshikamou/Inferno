package com.Idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.CloudflareInterceptor
import java.security.MessageDigest

data class IdlixApiResponse(
    val data: List<IdlixApiItem> = emptyList()
)

data class IdlixApiItem(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val posterPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: String? = null,
    val quality: String? = null,
    val contentType: String? = null
)

data class IdlixDetailResponse(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Any? = null,
    val quality: String? = null,
    val trailerUrl: String? = null,
    val genres: List<IdlixGenre>? = null,
    val cast: List<IdlixCast>? = null,
    val seasons: List<IdlixSeason>? = null,
    val firstSeason: IdlixSeason? = null
)

data class IdlixGenre(
    val id: String? = null,
    val name: String? = null
)

data class IdlixCast(
    val id: String? = null,
    val name: String? = null,
    val profilePath: String? = null
)

data class IdlixSeason(
    val id: String? = null,
    val seasonNumber: Int? = null,
    val name: String? = null,
    val episodes: List<IdlixEpisode>? = null
)

data class IdlixEpisode(
    val id: String? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null
)

data class IdlixSearchResponse(
    val results: List<IdlixSearchResult> = emptyList()
)

data class IdlixSearchResult(
    val id: String = "",
    val contentType: String = "",
    val title: String = "",
    val posterPath: String = "",
    val slug: String = "",
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double = 0.0,
    val quality: String? = null
)

data class IdlixChallengeResponse(
    val challenge: String = "",
    val signature: String = "",
    val difficulty: Int = 0
)

data class IdlixSolveResponse(
    val embedUrl: String? = null,
    val url: String? = null,
    val file: String? = null
)

data class IdlixLoadData(
    val id: String,
    val type: String
)

data class IdlixSeasonWrapper(
    val season: IdlixSeason? = null
)

class Idlix : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Inisialisasi Cloudflare Interceptor untuk melewati proteksi otomatis
    private val cloudflareInterceptor = CloudflareInterceptor()

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L, interceptor = cloudflareInterceptor).parsedSafe<IdlixApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        val home = res.data.mapNotNull { item ->
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title ?: return@mapNotNull null, "$mainUrl/api/movies/${item.slug}", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getQualityFromString(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                newTvSeriesSearchResponse(item.title ?: return@mapNotNull null, "$mainUrl/api/series/${item.slug}", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                    this.quality = getQualityFromString(item.quality)
                }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/api/search?q=$query&page=1&limit=20", interceptor = cloudflareInterceptor).parsedSafe<IdlixSearchResponse>()
            ?: return emptyList()
        return res.results.mapNotNull { item ->
            val poster = "https://image.tmdb.org/t/p/w342${item.posterPath}"
            val link = if (item.contentType == "movie") {
                "$mainUrl/api/movies/${item.slug}"
            } else {
                "$mainUrl/api/series/${item.slug}"
            }
            if (item.contentType == "movie") {
                newMovieSearchResponse(item.title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getQualityFromString(item.quality)
                    this.score = Score.from10(item.voteAverage.toString())
                }
            } else {
                newTvSeriesSearchResponse(item.title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage.toString())
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = app.get(url, timeout = 10000L, interceptor = cloudflareInterceptor).parsedSafe<IdlixDetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON response")
        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val actors = data.cast?.mapNotNull {
            it.name?.let { name ->
                Actor(name, it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
            }
        } ?: emptyList()

        if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()
            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                        this.name = ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }
            data.seasons.forEach { season ->
                val sn = season.seasonNumber ?: return@forEach
                if (sn == data.firstSeason?.seasonNumber) return@forEach
                app.get("$mainUrl/api/series/${data.slug}/season/$sn", interceptor = cloudflareInterceptor)
                    .parsedSafe<IdlixSeasonWrapper>()
                    ?.season
                    ?.episodes
                    ?.forEach { ep ->
                        episodes.add(
                            newEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) {
                                this.name = ep.name
                                this.season = sn
                                this.episode = ep.episodeNumber
                                this.description = ep.overview
                                this.runTime = ep.runtime
                                this.score = Score.from10(ep.voteAverage?.toString())
                                this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                            }
                        )
                    }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            return newMovieLoadResponse(
                title, url, TvType.Movie,
                IdlixLoadData(id = data.id ?: "", type = "movie").toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = AppUtils.parseJson<IdlixLoadData>(data)
        val aclrResponse = app.get("$mainUrl/pagead/ad_frame.js?_=${System.currentTimeMillis()}", interceptor = cloudflareInterceptor)
        val aclr = aclrResponse.text.let {
            Regex("""__aclr\s*=\s*"([a-f0-9]+)""").find(it)?.groupValues?.getOrNull(1)
        }
        val headers = mapOf(
            "accept" to "*/*", "content-type" to "application/json", "origin" to mainUrl,
            "referer" to mainUrl, "user-agent" to USER_AGENT
        )
        val challenge = app.post(
            "$mainUrl/api/watch/challenge",
            data = mapOf("contentType" to parsed.type, "contentId" to parsed.id, "clearance" to (aclr ?: "")),
            headers = headers,
            interceptor = cloudflareInterceptor
        ).parsedSafe<IdlixChallengeResponse>() ?: return false

        val solve = app.post(
            "$mainUrl/api/watch/solve",
            data = mapOf(
                "challenge" to challenge.challenge,
                "signature" to challenge.signature,
                "nonce" to solvePow(challenge.challenge, challenge.difficulty).toString()
            ),
            headers = headers,
            interceptor = cloudflareInterceptor
        ).parsedSafe<IdlixSolveResponse>() ?: return false

        val embedUrl = solve.embedUrl ?: solve.url ?: solve.file ?: return false
        val finalUrl = if (embedUrl.startsWith("http")) embedUrl else "$mainUrl$embedUrl"
        
        return loadExtractorWithFallback(finalUrl, mainUrl, subtitleCallback, callback)
    }

    private fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (nonce < 20000000) {
            if (sha256(challenge + nonce).startsWith(target)) return nonce
            nonce++
        }
        return 0
    }

    private fun sha256(input: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

// ============================================
// HELPER: LOAD EXTRACTOR WITH FALLBACK
// ============================================

suspend fun loadExtractorWithFallback(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var deliveredLinks = 0
    val trackedCallback: (ExtractorLink) -> Unit = { link ->
        deliveredLinks++
        callback(link)
    }

    try {
        if (loadExtractor(url, referer, subtitleCallback, trackedCallback)) return true
    } catch (_: Exception) {}

    val urlDomain = url
        .removePrefix("http://")
        .removePrefix("https://")
        .split("/")
        .first()
        .lowercase()

    val matchingExtractors = IdlixEkstraktors.list.filter { extractor ->
        urlDomain.contains(
            extractor.mainUrl
                .removePrefix("http://")
                .removePrefix("https://")
                .split("/")
                .first()
                .lowercase()
        )
    }

    for (extractor in matchingExtractors) {
        try {
            extractor.getUrl(url, referer, subtitleCallback, trackedCallback)
        } catch (_: Exception) {}
    }

    return deliveredLinks > 0
}

// ============================================
// EXTRACTOR: JENIUSPLAY
// ============================================

class Jeniusplay : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hash = url.split("/").last().substringAfter("data=")
            val res = app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                referer = referer ?: mainUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            ).parsedSafe<ResponseSource>()

            res?.videoSource?.let { m3uLink ->
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = m3uLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                    }
                )
            }
        } catch (_: Exception) {}
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

// ============================================
// EXTRACTORS LIST
// ============================================

object IdlixEkstraktors {
    val list = listOf(
        Jeniusplay()
    )
}
