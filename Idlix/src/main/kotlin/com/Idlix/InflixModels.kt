package com.Idlix

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
