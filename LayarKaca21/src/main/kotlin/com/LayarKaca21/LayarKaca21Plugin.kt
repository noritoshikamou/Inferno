package com.LayarKaca21

import com.baseprovider.extractor.Lk21PlayerPage
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class LayarKaca21Plugin : BasePlugin() {
    override fun load() {
        // Mendaftarkan API Utama LayarKaca21
        registerMainAPI(LayarKaca21())
        
        // Mendaftarkan Ekstraktor Kustom agar terhubung
        registerExtractorAPI(LayarKaca21PlayerPage())
    }
}
