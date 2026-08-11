package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class Moviebox : MainAPI() {

    override var mainUrl = "https://moviebox.ph"
    private val apiUrl = "https://fmoviesunblocked.net"
    override val instantLinkLoading = true
    override var name = "Moviebox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending Now",
        "997144265920760504" to "Popular Movie",
        "5283462032510044280" to "Drama Indonesia Terkini",
        "6528093688173053896" to "Trending Indonesian Movies",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Western TV",
        "8624142774394406504" to "Most Popular C-Drama",
        "5404290953194750296" to "Trending Anime",
        "5848753831881965888" to "Indonesian Horror Stories",
        "1164329479448281992" to "Thai-Drama",
        "7132534597631837112" to "Animated Film",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/wefeed-h5-bff/web/ranking-list/content?id=${request.data}&page=$page&perPage=12"
        val res = app.get(url).parsedSafe<Media>()
        val home = res?.data?.subjectList?.mapNotNull { it.toSearchResponse(this) }
            ?: throw ErrorLoadingException("No Data Found")
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val bodyMap = mapOf("keyword" to query, "page" to "1", "perPage" to "0", "subjectType" to "0")
        val body = AppUtils.toJson(bodyMap).toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        
        val res = app.post("$mainUrl/wefeed-h5-bff/web/subject/search", requestBody = body).parsedSafe<Media>()
        return res?.data?.items?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val doc = app.get("$mainUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id").parsedSafe<MediaDetail>()?.data
        val subject = doc?.subject ?: throw ErrorLoadingException("Invalid details")
        
        val title = subject.title ?: ""
        val poster = subject.cover?.url
        val tags = subject.genre?.split(",")?.map { it.trim() }
        val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (subject.subjectType == 2) TvType.TvSeries else TvType.Movie
        val description = subject.description
        val trailer = subject.trailer?.videoAddress?.url
        val ratingVal = subject.imdbRatingValue?.toDoubleOrNull()
        
        val actors = doc.stars?.mapNotNull { cast ->
            val castName = cast.name ?: return@mapNotNull null
            ActorData(Actor(castName, cast.avatarUrl), roleString = cast.character)
        }?.distinctBy { it.actor }

        val recommendations = app.get("$mainUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12")
            .parsedSafe<Media>()?.data?.items?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()

        return if (tvType == TvType.TvSeries) {
            val episodes = doc.resource?.seasons?.map { seasons ->
                val allEpList = seasons.allEp
                val maxEpCount = seasons.maxEp
                val epRange = if (allEpList.isNullOrEmpty()) {
                    1..(maxEpCount ?: 1)
                } else {
                    allEpList.split(",").mapNotNull { it.toIntOrNull() }
                }
                
                epRange.map { episode -> 
                    val epData = LoadData(id, seasons.se, episode, subject.detailPath)
                    Episode(
                        data = AppUtils.toJson(epData),
                        name = "Episode $episode",
                        season = seasons.se,
                        episode = episode
                    )
                }
            }?.flatten() ?: emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingVal != null) this.rating = ratingVal.toInt()
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            val movieData = LoadData(id, detailPath = subject.detailPath)
            newMovieLoadResponse(title, url, TvType.Movie, AppUtils.toJson(movieData)) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingVal != null) this.rating = ratingVal.toInt()
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = parseJson<LoadData>(data)
        val referer = "$apiUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"
        
        val streams = app.get("$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}", referer = referer)
            .parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.forEach { source ->
            val videoUrl = source.url ?: return@forEach
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = "$apiUrl/",
                    quality = getQualityFromName(source.resolutions),
                    type = INFER_TYPE
                )
            )
        }

        val firstStream = streams?.firstOrNull()
        val streamId = firstStream?.id
        val format = firstStream?.format

        if (streamId != null) {
            app.get("$apiUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$streamId&subjectId=${media.id}", referer = referer)
                .parsedSafe<Media>()?.data?.captions?.forEach { subtitle ->
                    val subUrl = subtitle.url ?: return@forEach
                    subtitleCallback(SubtitleFile(subtitle.lanName ?: "", subUrl))
                }
        }

        return true
    }

    data class LoadData(val id: String? = null, val season: Int? = null, val episode: Int? = null, val detailPath: String? = null)

    data class Media(@JsonProperty("data") val data: Data? = null) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf()
        ) {
            data class Streams(@JsonProperty("id") val id: String? = null, @JsonProperty("format") val format: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("resolutions") val resolutions: String? = null)
            data class Captions(@JsonProperty("lan") val lan: String? = null, @JsonProperty("lanName") val lanName: String? = null, @JsonProperty("url") val url: String? = null)
        }
    }

    data class MediaDetail(@JsonProperty("data") val data: Data? = null) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null
        ) {
            data class Stars(@JsonProperty("name") val name: String? = null, @JsonProperty("character") val character: String? = null, @JsonProperty("avatarUrl") val avatarUrl: String? = null)
            data class Resource(@JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf()) {
                data class Seasons(@JsonProperty("se") val se: Int? = null, @JsonProperty("maxEp") val maxEp: Int? = null, @JsonProperty("allEp") val allEp: String? = null)
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null
    ) {
        fun toSearchResponse(provider: Moviebox): SearchResponse? {
            val sId = subjectId ?: return null
            return provider.newMovieSearchResponse(title ?: "No Title", sId, if (subjectType == 1) TvType.Movie else TvType.TvSeries, false) {
                this.posterUrl = cover?.url
            }
        }
        data class Cover(@JsonProperty("url") val url: String? = null)
        data class Trailer(@JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
            data class VideoAddress(@JsonProperty("url") val url: String? = null)
        }
    }
}
