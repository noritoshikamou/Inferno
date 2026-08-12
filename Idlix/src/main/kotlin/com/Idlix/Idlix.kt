package com.Idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.security.MessageDigest

class Idlix : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val cloudflareInterceptor = CloudflareKiller()

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
                    this.quality = getQualityFromString(item.quality)
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
                data.trailerUrl?.let { addTrailer(it) }
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
                data.trailerUrl?.let { addTrailer(it) }
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

 override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") || data.contains("youtube") || data.contains("youtu.be")) {
            return loadExtractor(data, "", subtitleCallback, callback)
        }

        val parsed = try {
            AppUtils.parseJson<IdlixLoadData>(data)
        } catch (_: Exception) {
            if (data.isNotEmpty()) return loadExtractor(data, "", subtitleCallback, callback)
            return false
        }

        val watchUrl = if (parsed.type == "movie") {
            "$mainUrl/api/movies/watch/${parsed.id}"
        } else {
            "$mainUrl/api/series/watch/${parsed.id}"
        }

        try {
            // Gunakan User-Agent menyerupai Google Chrome asli agar lolos dari validasi skrip frontend
            val customHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/"
            )

            val doc = app.get(watchUrl, headers = customHeaders, interceptor = cloudflareInterceptor).document
            
            // Ekstrak semua string URL atau iframe yang mencurigakan di dalam script/HTML halaman watch
            val scriptContent = doc.select("script").html()
            val extractedIframe = Regex("""https?://[^\s"'<>]+(?:embed|player|googlesyndication|stream)[^\s"'<>]*""").find(scriptContent)?.value

            val sources = mutableListOf<String>()
            if (!extractedIframe.isNullOrEmpty()) {
                sources.add(extractedIframe)
            }

            // Tambahkan elemen iframe standar HTML jika ada
            doc.select("iframe, source, video").forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("data-src") }
                if (src.isNotEmpty()) sources.add(src)
            }

            for (src in sources) {
                val targetUrl = if (src.startsWith("http", true)) src else "$mainUrl$src"
                if (loadExtractorWithFallback(targetUrl, mainUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
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
