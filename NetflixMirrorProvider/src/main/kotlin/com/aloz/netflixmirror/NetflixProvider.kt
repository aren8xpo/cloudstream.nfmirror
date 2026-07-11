package com.aloz.netflixmirror

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.regex.Pattern
import kotlin.random.Random

@CloudstreamPlugin
class NetflixMirrorUltimatePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        // All providers should be registered in the load function
        registerMainAPI(NetflixMirrorUltimate())
    }
}

class NetflixMirrorUltimate : MainAPI() {
    override var name = "Netflix Mirror Ultimate"
    override var mainUrl = "https://vidsrc.me"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TRICK 1: Expanded Browser Fingerprint Pool
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    )

    // TRICK 4: Fallback Chain (If Source 1 fails, try Source 2)
    private val MIRROR_SOURCES = listOf(
        "https://vidsrc.me",
        "https://2embed.me",
        "https://vidsrc.to"
    )

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse("Netflix Content", url, TvType.Movie, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Iterate through the fallback chain until a link is found
        for (source in MIRROR_SOURCES) {
            val currentUrl = if (data.contains("/movie/")) {
                "$source/embed/movie/$data"
            } else {
                // This is a simplification, ideally we'd parse the data
                "$source/embed/tv/$data"
            }
            val result = attemptExtraction(currentUrl)
            if (result != null) {
                callback.invoke(result)
                return true
            }
        }
        return false
    }

    private suspend fun attemptExtraction(url: String): ExtractorLink? {
        try {
            // TRICK 1 & 2 & 3: Full Browser Simulation
            val randomUA = USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
            val stealthHeaders = mapOf(
                "Referer" to url, // TRICK: Pivot referer to the specific page
                "User-Agent" to randomUA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )
            val response = app.get(url, headers = stealthHeaders)

            // TRICK: Regex to find high-quality streams while ignoring tracking pixels
            val pattern = Pattern.compile("https://[^\"]+?\\.(?:m3u8|mp4)[^\"]*")
            val matcher = pattern.matcher(response.text)
            if (matcher.find()) {
                var finalUrl = matcher.group()

                // TRICK 5: URL Sanitization (Strip tracking params after the file extension)
                if (finalUrl.contains("?")) {
                    // Extract extension from the matched URL
                    val extMatch = Regex("\\.(m3u8|mp4)").find(finalUrl)
                    val ext = extMatch?.value ?: ".m3u8"
                    finalUrl = finalUrl.substringBefore("?") + ext
                }

                return newExtractorLink(
                    "Stealth Source",
                    "Stealth Source [${url.substringAfter("://").substringBefore("/")}]",
                    finalUrl,
                    type = if (finalUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.P720.value
                }
            }
        } catch (e: Exception) {
            // TRICK 6: Silent failure to avoid app lag
            return null
        }
        return null
    }

}