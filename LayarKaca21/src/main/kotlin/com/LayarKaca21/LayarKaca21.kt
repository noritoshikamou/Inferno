package com.lk21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI

class LayarKaca21 : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc" // Sesuaikan domain aktif LK21 terbaru
    override var name = "LK21"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    // Konfigurasi Halaman Utama dan Kategori
    override val mainPage = mainPageOf(
        // Tombol Navigasi Utama (Tab Beranda/Header)
        "" to "Terbaru",
        "latest-series" to "Series Terbaru",
        "series/ongoing" to "Series Ongoing",
        "series/complete" to "Series Complete",
        "populer" to "Terpopuler",
        "rekomendasi-film-pintar" to "Rekomendasi",
        "year/2026" to "2026",

        // Kategori Berdasarkan List di Halaman Utama
        "latest" to "Film Terbaru",
        "nontondrama" to "Series Unggulan",
        "series/update" to "Series Update",
        "quality/bluray" to "Bluray Terbaru",
        "rekomendasi-film-pintar" to "Rekomendasi Lainnya",

        // Genre Pilihan di Halaman Depan
        "genre/action" to "Action Terbaru",
        "genre/drama" to "Drama Terbaru",
        "genre/horror" to "Horror Terbaru",
        "genre/animation" to "Animation Terbaru",
        "genre/comedy" to "Comedy Terbaru",
        "genre/romance" to "Romance Terbaru",

        // Negara Pilihan di Halaman Depan
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
        // Berdasarkan HTML slider/grid LK21: membungkus artikel film
        val items = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("figure a") ?: selectFirst("a") ?: return null
        val title = selectFirst("h3.poster-title")?.text()?.trim() ?: aTag.attr("title").trim()
        if (title.isEmpty()) return null
        
        val href = fixUrl(aTag.attr("href"))
        
        // Versi yang disempurnakan: Mencoba ambil data-src (lazy load), baru src, lalu srcset
        val imgElement = selectFirst("img")
        val poster = imgElement?.attr("data-src") // Banyak situs pakai lazyload
            ?: imgElement?.attr("src")             // Gambar utama
            ?: selectFirst("source")?.attr("srcset")?.substringBefore(" ") // Untuk tag <picture>
            
        // Bersihkan URL poster agar tidak ada spasi di awal/akhir
        val cleanPoster = poster?.trim()

        // Cek apakah konten berupa Series atau Movie
        val episodeText = selectFirst("span.episode")?.text()
        val isSeries = episodeText != null || selectFirst("span.duration")?.text()?.contains("S.") == true

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
        
        val title = doc.selectFirst("h1, h2.entry-title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: doc.selectFirst(".poster img")?.attr("src")
        val description = doc.selectFirst("div.synopsis, div.desc, meta[name='description']")?.attr("content") 
            ?: doc.selectFirst("div.entry-content")?.text()

        // Mengambil rating
        val rating = doc.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()

        // [TAMBAHAN OPSIONAL] Mengambil Tahun dan Genre agar lebih lengkap
        val year = doc.selectFirst("span[itemprop='datePublished'], .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val tags = doc.select(".genres a, .genre a").map { it.text().trim() }

        // Deteksi apakah halaman ini memiliki episode (Series)
        val episodeElements = doc.select("div.episodelist ul li a, .seasons-box a, .list-eps a")
        
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
        
        // Mengambil semua link server dari list "GANTI PLAYER"
        document.select("ul#player-list li a").forEach { element ->
            val serverUrl = element.attr("data-url")
            val serverName = element.text().trim()
            
            // Kita coba load extractor untuk setiap server yang ditemukan
            // Jika serverUrl valid, kita proses
            if (serverUrl.isNotEmpty()) {
                loadExtractor(fixUrl(serverUrl), data, subtitleCallback, callback)
            }
        }
        
        // Tambahan: Fallback jika list di atas gagal, ambil iframe utama
        document.select("iframe#main-player").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
