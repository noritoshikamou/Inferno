package com.lk21

// Mengimpor konteks Android dan komponen plugin bawaan Cloudstream
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

// Anotasi penanda bahwa kelas ini adalah titik masuk utama (entry point) untuk plugin Cloudstream
@CloudstreamPlugin
class Lk21Plugin : Plugin() {
    
    // Fungsi utama yang dipanggil saat plugin pertama kali dimuat oleh aplikasi Cloudstream
    override fun load(context: Context) {
        // Mendaftarkan API utama LK21 yang sudah kita buat
        registerMainAPI(Lk21Provider())
        
        // Catatan: Karena kita belum pakai extractor kustom, baris registerExtractorAPI di bawah bisa dilewati/dihapus dulu.
    }
}
