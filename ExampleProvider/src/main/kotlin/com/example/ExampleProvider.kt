package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    override var name = "Example Provider"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "id"
    
    override val hasMainPage = false

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("title")?.text() ?: "No Title"

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = null
            this.plot = "Plugin sederhana tanpa halaman utama."
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val videoUrl = document.selectFirst("iframe")?.attr("src") ?: return false

        callback(
            newExtractorLink(
                name,
                name,
                videoUrl,
                mainUrl,
                Qualities.P1080.value
            )
        )
        
        return true
    }
}
