package com.flamyoad.instafetcher.data.remote

import android.util.Log
import com.flamyoad.instafetcher.data.local.InstagramCookieManager
import com.flamyoad.instafetcher.data.model.CarouselItem
import com.flamyoad.instafetcher.data.model.InstagramMedia
import com.flamyoad.instafetcher.data.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class InstagramService(
    private val httpClient: HttpClient,
    private val json: Json,
    private val cookieManager: InstagramCookieManager
) {
    /**
     * Exception thrown when Instagram has disabled embedding for a post.
     * This typically means the content owner or Instagram has restricted public access.
     */
    class EmbedBrokenException(message: String) : Exception(message)
    
    companion object {
        private const val TAG = "InstagramService"
        private const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
        
        // Regex patterns for extracting embedded JSON data
        private val SHARED_DATA_REGEX = Regex("""window\._sharedData\s*=\s*(\{.+?\});</script>""")
        private val ADDITIONAL_DATA_REGEX = Regex("""window\.__additionalDataLoaded\s*\([^,]+,\s*(\{.+?\})\);""")
        
        // User agent to mimic Instagram mobile app - more reliable for API access
        private const val MOBILE_USER_AGENT = "Instagram 275.0.0.27.98 Android (33/13; 420dpi; 1080x2400; Google/google; Pixel 6; oriole; oriole; en_US; 458229237)"
        private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /**
     * Fetches media information from an Instagram post URL.
     * Uses authenticated requests if cookies are available, otherwise falls back to public methods.
     * 
     * @param shortcode The Instagram post shortcode (e.g., "ABC123" from instagram.com/p/ABC123/)
     * @return InstagramMedia containing the media URLs and metadata
     */
    suspend fun fetchMedia(shortcode: String): InstagramMedia {
        // Try authenticated GraphQL first if logged in
        val cookies = cookieManager.getCookies()
        if (cookies?.isValid() == true) {
            Log.d(TAG, "Using authenticated requests with session cookies")
            return try {
                fetchMediaViaAuthenticatedGraphQL(shortcode, cookies)
            } catch (e: Exception) {
                Log.w(TAG, "Authenticated GraphQL failed, trying public methods", e)
                fetchMediaPublic(shortcode)
            }
        }
        
        // Fall back to public methods
        return fetchMediaPublic(shortcode)
    }
    
    /**
     * Fetches media using public (non-authenticated) methods.
     */
    private suspend fun fetchMediaPublic(shortcode: String): InstagramMedia {
        // Try the GraphQL API first (most reliable for public posts)
        return try {
            fetchMediaViaGraphQL(shortcode)
        } catch (e: Exception) {
            Log.w(TAG, "GraphQL API failed, trying embed endpoint", e)
            try {
                fetchMediaViaEmbed(shortcode)
            } catch (e2: EmbedBrokenException) {
                // Embed is explicitly disabled for this post
                Log.w(TAG, "Embed is broken/disabled, falling back to HTML scraping", e2)
                fetchMediaViaHtml(shortcode)
            } catch (e2: Exception) {
                Log.w(TAG, "Embed endpoint failed, trying HTML scraping", e2)
                fetchMediaViaHtml(shortcode)
            }
        }
    }
    
    /**
     * Fetch media using authenticated GraphQL API with session cookies.
     * This can access content that requires login and returns full-resolution images.
     */
    private suspend fun fetchMediaViaAuthenticatedGraphQL(
        shortcode: String, 
        cookies: InstagramCookieManager.InstagramCookies
    ): InstagramMedia {
        // Try the media info API endpoint first (most reliable for authenticated requests)
        return try {
            fetchMediaViaApiV1(shortcode, cookies)
        } catch (e: Exception) {
            Log.w(TAG, "API v1 fetch failed: ${e.message}, trying web page", e)
            try {
                fetchMediaViaAuthenticatedPage(shortcode, cookies)
            } catch (e2: Exception) {
                Log.w(TAG, "Authenticated page fetch failed: ${e2.message}, trying GraphQL API", e2)
                fetchMediaViaAuthenticatedGraphQLQuery(shortcode, cookies)
            }
        }
    }
    
    /**
     * Fetch media using Instagram's internal API v1 endpoint with cookies.
     * This endpoint returns full resolution media.
     */
    private suspend fun fetchMediaViaApiV1(
        shortcode: String,
        cookies: InstagramCookieManager.InstagramCookies
    ): InstagramMedia {
        // First, get the media ID from the shortcode
        val mediaId = convertShortcodeToMediaId(shortcode)
        val url = "https://i.instagram.com/api/v1/media/${mediaId}/info/"
        
        Log.d(TAG, "Fetching via API v1 for $shortcode (mediaId: $mediaId)")
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, MOBILE_USER_AGENT)
                append(HttpHeaders.Accept, "*/*")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                append(HttpHeaders.Cookie, cookies.toCookieHeader())
                append("X-CSRFToken", cookies.csrfToken)
                append("X-IG-App-ID", "936619743392459")
                append("X-IG-WWW-Claim", "hmac.AR0ygTXvkSqMO0nZvsMf5Z-u_CG4dxstsQtVb7hwqRXBfT8H")
                append("X-ASBD-ID", "129477")
                append(HttpHeaders.Referrer, "$INSTAGRAM_BASE_URL/")
            }
        }
        
        val responseText = response.bodyAsText()
        Log.d(TAG, "API v1 response status: ${response.status}")
        Log.d(TAG, "API v1 response length: ${responseText.length}")
        Log.d(TAG, "API v1 response preview: ${responseText.take(500)}")
        
        if (responseText.isBlank() || responseText.startsWith("<!DOCTYPE")) {
            throw IllegalStateException("API v1 returned HTML instead of JSON")
        }
        
        if (responseText.contains("login_required") || response.status.value == 401) {
            throw IllegalStateException("Session expired or login required")
        }
        
        val jsonElement = json.parseToJsonElement(responseText)
        val jsonObj = jsonElement.jsonObject
        
        val items = jsonObj["items"]?.jsonArray
        if (items.isNullOrEmpty()) {
            throw IllegalStateException("API v1 returned no items")
        }
        
        val media = items.first().jsonObject
        return parseMediaObject(media, shortcode)
    }
    
    /**
     * Convert Instagram shortcode to numeric media ID.
     * Instagram uses a base64-like encoding for shortcodes.
     */
    private fun convertShortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (char in shortcode) {
            id = id * 64 + alphabet.indexOf(char)
        }
        return id.toString()
    }
    
    /**
     * Fetch media by loading the Instagram post page with cookies.
     * The page contains embedded JSON with full media data.
     */
    private suspend fun fetchMediaViaAuthenticatedPage(
        shortcode: String,
        cookies: InstagramCookieManager.InstagramCookies
    ): InstagramMedia {
        // Try the JSON endpoint first
        val url = "$INSTAGRAM_BASE_URL/p/$shortcode/?__a=1&__d=dis"
        
        Log.d(TAG, "Fetching authenticated page for $shortcode")
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                append(HttpHeaders.Accept, "*/*")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                append(HttpHeaders.Cookie, cookies.toCookieHeader())
                append("X-CSRFToken", cookies.csrfToken)
                append("X-IG-App-ID", "936619743392459")
                append("X-IG-WWW-Claim", "0")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.Referrer, "$INSTAGRAM_BASE_URL/")
                append("Sec-Fetch-Dest", "empty")
                append("Sec-Fetch-Mode", "cors")
                append("Sec-Fetch-Site", "same-origin")
            }
        }
        
        val responseText = response.bodyAsText()
        Log.d(TAG, "Authenticated page response status: ${response.status}")
        Log.d(TAG, "Authenticated page response length: ${responseText.length}")
        Log.d(TAG, "Response preview: ${responseText.take(500)}")
        
        if (responseText.isBlank() || responseText.startsWith("<!DOCTYPE")) {
            throw IllegalStateException("Authenticated page returned HTML instead of JSON")
        }
        
        // Check for login required error
        if (responseText.contains("login_required") || responseText.contains("Please wait")) {
            throw IllegalStateException("Session expired or login required")
        }
        
        val jsonElement = json.parseToJsonElement(responseText)
        val jsonObj = jsonElement.jsonObject
        
        // Parse the response - try different structures
        val media = jsonObj["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: jsonObj["graphql"]?.jsonObject?.get("shortcode_media")?.jsonObject
            ?: throw IllegalStateException("Could not parse authenticated page response: ${responseText.take(200)}")
        
        return parseMediaObject(media, shortcode)
    }
    
    /**
     * Fetch media using GraphQL query with cookies.
     */
    private suspend fun fetchMediaViaAuthenticatedGraphQLQuery(
        shortcode: String,
        cookies: InstagramCookieManager.InstagramCookies
    ): InstagramMedia {
        // GraphQL query to fetch media by shortcode
        val queryHash = "b3055c01b4b222b8a47dc12b090e4e64" // media query hash
        val variables = """{"shortcode":"$shortcode"}"""
        val url = "$INSTAGRAM_BASE_URL/graphql/query/?query_hash=$queryHash&variables=$variables"
        
        Log.d(TAG, "Fetching via authenticated GraphQL query for $shortcode")
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                append(HttpHeaders.Accept, "*/*")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                append(HttpHeaders.Cookie, cookies.toCookieHeader())
                append("X-CSRFToken", cookies.csrfToken)
                append("X-IG-App-ID", "936619743392459")
                append("X-Requested-With", "XMLHttpRequest")
                append(HttpHeaders.Referrer, "$INSTAGRAM_BASE_URL/p/$shortcode/")
            }
        }
        
        val responseText = response.bodyAsText()
        Log.d(TAG, "GraphQL query response length: ${responseText.length}")
        
        if (responseText.isBlank() || responseText.startsWith("<!DOCTYPE")) {
            throw IllegalStateException("GraphQL query returned HTML instead of JSON")
        }
        
        if (responseText.contains("login_required") || responseText.contains("Please wait")) {
            throw IllegalStateException("Session expired or login required")
        }
        
        val jsonElement = json.parseToJsonElement(responseText)
        val jsonObj = jsonElement.jsonObject
        
        val data = jsonObj["data"]?.jsonObject
        val media = data?.get("shortcode_media")?.jsonObject
            ?: throw IllegalStateException("Could not parse GraphQL query response")
        
        return parseMediaObject(media, shortcode)
    }

    /**
     * Fetch media using Instagram's GraphQL API with the __a=1 parameter
     */
    private suspend fun fetchMediaViaGraphQL(shortcode: String): InstagramMedia {
        val url = "$INSTAGRAM_BASE_URL/p/$shortcode/?__a=1&__d=dis"
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, MOBILE_USER_AGENT)
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                append("X-IG-App-ID", "936619743392459")
                append("X-ASBD-ID", "198387")
                append("X-Requested-With", "XMLHttpRequest")
            }
        }
        
        val responseText = response.bodyAsText()
        Log.d(TAG, "GraphQL response length: ${responseText.length}")
        
        if (responseText.isBlank() || responseText.startsWith("<!DOCTYPE")) {
            throw IllegalStateException("GraphQL API returned HTML instead of JSON")
        }
        
        val jsonElement = json.parseToJsonElement(responseText)
        val jsonObj = jsonElement.jsonObject
        
        // The response structure can vary
        val media = jsonObj["graphql"]?.jsonObject?.get("shortcode_media")?.jsonObject
            ?: jsonObj["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("Could not parse GraphQL response")
        
        return parseMediaObject(media, shortcode)
    }

    /**
     * Fetch media using Instagram's embed endpoint (public, doesn't require auth)
     */
    private suspend fun fetchMediaViaEmbed(shortcode: String): InstagramMedia {
        val url = "$INSTAGRAM_BASE_URL/p/$shortcode/embed/captioned/"
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                append("Sec-Fetch-Dest", "iframe")
                append("Sec-Fetch-Mode", "navigate")
                append("Sec-Fetch-Site", "cross-site")
                append(HttpHeaders.CacheControl, "no-cache")
                append("Pragma", "no-cache")
            }
        }
        
        val html = response.bodyAsText()
        Log.d(TAG, "Embed response length: ${html.length}")
        
        return parseEmbedHtml(html, shortcode)
    }

    /**
     * Parse the embed page HTML to extract media URLs
     */
    private fun parseEmbedHtml(html: String, shortcode: String): InstagramMedia {
        Log.d(TAG, "parseEmbedHtml called, html length: ${html.length}")
        
        // Check if embed is broken (content restricted by Instagram/creator)
        val isEmbedBroken = html.contains("EmbedIsBroken") || 
            html.contains("The link to this photo or video may be broken")
        Log.d(TAG, "Embed is broken: $isEmbedBroken")
        
        if (isEmbedBroken) {
            throw EmbedBrokenException("Embed is disabled for this post")
        }
        
        // Debug: Check if EmbeddedMediaImage exists in HTML
        val hasEmbeddedMediaImage = html.contains("EmbeddedMediaImage")
        Log.d(TAG, "HTML contains EmbeddedMediaImage: $hasEmbeddedMediaImage")
        
        // Also try extracting from JSON embedded in the page
        val embedJsonRegex = Regex("""window\.__additionalDataLoaded\('extra',(\{.+?\})\);""")
        val embedJsonMatch = embedJsonRegex.find(html)
        
        if (embedJsonMatch != null) {
            try {
                val jsonData = json.parseToJsonElement(embedJsonMatch.groupValues[1]).jsonObject
                val mediaData = jsonData["shortcode_media"]?.jsonObject
                if (mediaData != null) {
                    return parseMediaObject(mediaData, shortcode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse embed JSON", e)
            }
        }
        
        // Try to extract all display_url entries from embedded JSON in the page
        // The embed page contains escaped JSON with display_url for each carousel item
        val displayUrls = extractAllDisplayUrlsFromEmbed(html)
        Log.d(TAG, "Found ${displayUrls.size} unique display_url entries from embedded JSON")
        
        // If we found multiple display URLs, this is a carousel
        if (displayUrls.size > 1) {
            Log.d(TAG, "Detected carousel with ${displayUrls.size} items")
            val carouselItems = displayUrls.map { url ->
                CarouselItem(
                    displayUrl = url,
                    mediaType = MediaType.IMAGE,
                    videoUrl = null
                )
            }
            return InstagramMedia(
                shortcode = shortcode,
                mediaType = MediaType.CAROUSEL,
                displayUrl = displayUrls.first(),
                isCarousel = true,
                carouselMedia = carouselItems
            )
        }
        
        var imageUrl: String? = displayUrls.firstOrNull()
        
        // Extract from EmbeddedMediaImage - try srcset first for highest resolution
        // The HTML may have newlines/whitespace, so use DOT_MATCHES_ALL
        // srcset format: "url1 640w,url2 750w,url3 1080w"
        if (imageUrl == null) {
            val srcsetRegex = Regex("""<img\s+class="EmbeddedMediaImage"[^>]*srcset="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            val srcsetMatch = srcsetRegex.find(html)
            
            Log.d(TAG, "srcset match found: ${srcsetMatch != null}")
            
            if (srcsetMatch != null) {
                val srcset = srcsetMatch.groupValues[1].replace("&amp;", "&")
                Log.d(TAG, "Found srcset: ${srcset.take(200)}...")
                
                // Parse srcset and find highest resolution
                val srcsetEntries = srcset.split(",").mapNotNull { entry ->
                    val parts = entry.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val url = parts[0]
                        val width = parts[1].removeSuffix("w").toIntOrNull() ?: 0
                        Pair(url, width)
                    } else null
                }
                // Get the URL with highest width
                imageUrl = srcsetEntries.maxByOrNull { it.second }?.first
                Log.d(TAG, "Embed srcset parsed - found ${srcsetEntries.size} entries, max width: ${srcsetEntries.maxByOrNull { it.second }?.second}")
            }
        }
        
        // Fallback to src attribute if srcset not found
        // The src attribute often already contains a high-res URL (1080x1080)
        if (imageUrl == null) {
            // Try multiple patterns - img tag may have various attribute orderings
            val patterns = listOf(
                Regex("""<img\s+class="EmbeddedMediaImage"[^>]*\ssrc="([^"]+)"""", RegexOption.DOT_MATCHES_ALL),
                Regex("""class="EmbeddedMediaImage"[^>]*\ssrc="([^"]+)"""", RegexOption.DOT_MATCHES_ALL),
                Regex("""EmbeddedMediaImage"[^>]*src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            )
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    imageUrl = match.groupValues[1].replace("&amp;", "&")
                    Log.d(TAG, "Found image with pattern: ${pattern.pattern.take(50)}, url: ${imageUrl?.take(100)}")
                    break
                }
            }
        }
        
        // If still no image URL, try to find any Instagram CDN URL
        if (imageUrl == null) {
            val cdnRegex = Regex(""""(https://instagram[^"]+\.(?:jpg|heic|webp)[^"]*)"""")
            val cdnMatch = cdnRegex.find(html)
            if (cdnMatch != null) {
                imageUrl = cdnMatch.groupValues[1].replace("&amp;", "&")
                Log.d(TAG, "Found CDN URL: ${imageUrl?.take(100)}")
            }
        }
        
        val videoRegex = Regex("""<video[^>]+src="([^"]+)"""")
        val captionRegex = Regex("""class="Caption"[^>]*>.*?<div[^>]*>([^<]+)""", RegexOption.DOT_MATCHES_ALL)
        
        val videoUrl = videoRegex.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        val caption = captionRegex.find(html)?.groupValues?.get(1)?.trim()
        
        val displayUrl = imageUrl ?: videoUrl ?: ""
        
        Log.d(TAG, "Embed parsed - imageUrl: ${imageUrl?.take(100)}, videoUrl: ${videoUrl?.take(100)}")
        
        if (displayUrl.isEmpty()) {
            throw IllegalStateException("Could not extract media URL from embed page")
        }
        
        return InstagramMedia(
            shortcode = shortcode,
            mediaType = if (videoUrl != null) MediaType.VIDEO else MediaType.IMAGE,
            displayUrl = displayUrl,
            videoUrl = videoUrl,
            caption = caption
        )
    }

    /**
     * Fallback: Fetch media via regular HTML page scraping
     */
    private suspend fun fetchMediaViaHtml(shortcode: String): InstagramMedia {
        val url = "$INSTAGRAM_BASE_URL/p/$shortcode/"
        
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.5")
                append("Sec-Fetch-Dest", "document")
                append("Sec-Fetch-Mode", "navigate")
            }
        }
        
        val html = response.bodyAsText()
        Log.d(TAG, "HTML response length: ${html.length}")
        return parseMediaFromHtml(html, shortcode)
    }

    /**
     * Parses Instagram media data from the HTML response.
     * Instagram embeds JSON data in the page which we can extract.
     */
    private fun parseMediaFromHtml(html: String, shortcode: String): InstagramMedia {
        // Try to extract data from window._sharedData first
        val sharedDataMatch = SHARED_DATA_REGEX.find(html)
        val additionalDataMatch = ADDITIONAL_DATA_REGEX.find(html)
        
        val jsonData = when {
            sharedDataMatch != null -> {
                Log.d(TAG, "Found sharedData")
                val jsonString = sharedDataMatch.groupValues[1]
                extractMediaFromSharedData(json.parseToJsonElement(jsonString).jsonObject, shortcode)
            }
            additionalDataMatch != null -> {
                Log.d(TAG, "Found additionalData")
                val jsonString = additionalDataMatch.groupValues[1]
                extractMediaFromAdditionalData(json.parseToJsonElement(jsonString).jsonObject)
            }
            else -> {
                Log.d(TAG, "Falling back to meta tags")
                // Fallback: Try to extract from meta tags
                extractMediaFromMetaTags(html, shortcode)
            }
        }
        
        return jsonData
    }

    private fun extractMediaFromSharedData(data: JsonObject, shortcode: String): InstagramMedia {
        val entryData = data["entry_data"]?.jsonObject
        val postPage = entryData?.get("PostPage")?.jsonArray?.firstOrNull()?.jsonObject
        val graphql = postPage?.get("graphql")?.jsonObject
        val media = graphql?.get("shortcode_media")?.jsonObject
            ?: throw IllegalStateException("Could not find media data in response")
        
        return parseMediaObject(media, shortcode)
    }

    private fun extractMediaFromAdditionalData(data: JsonObject): InstagramMedia {
        val media = data["graphql"]?.jsonObject?.get("shortcode_media")?.jsonObject
            ?: data["shortcode_media"]?.jsonObject
            ?: throw IllegalStateException("Could not find media data in additional data")
        
        return parseMediaObject(media, media["shortcode"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    private fun parseMediaObject(media: JsonObject, fallbackShortcode: String): InstagramMedia {
        val typeName = media["__typename"]?.jsonPrimitive?.contentOrNull ?: ""
        val shortcode = media["shortcode"]?.jsonPrimitive?.contentOrNull 
            ?: media["code"]?.jsonPrimitive?.contentOrNull
            ?: fallbackShortcode
        
        // Extract display_url - try multiple sources for highest resolution
        var displayUrl = media["display_url"]?.jsonPrimitive?.contentOrNull
        
        // For API responses, image_versions2 contains candidates sorted by resolution
        // Get the highest resolution candidate (usually first, but verify by width)
        if (displayUrl.isNullOrEmpty()) {
            val candidates = media["image_versions2"]?.jsonObject?.get("candidates")?.jsonArray
            if (candidates != null && candidates.isNotEmpty()) {
                // Find the highest resolution candidate
                var maxWidth = 0
                candidates.forEach { candidate ->
                    val candidateObj = candidate.jsonObject
                    val width = candidateObj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val url = candidateObj["url"]?.jsonPrimitive?.contentOrNull
                    if (width > maxWidth && url != null) {
                        maxWidth = width
                        displayUrl = url
                    }
                }
                Log.d(TAG, "Selected image from image_versions2 with width: $maxWidth")
            }
        }
        
        // Fallback to display_resources for highest resolution
        if (displayUrl.isNullOrEmpty()) {
            val displayResources = media["display_resources"]?.jsonArray
            if (displayResources != null && displayResources.isNotEmpty()) {
                // Get the last one (highest resolution)
                val highRes = displayResources.lastOrNull()?.jsonObject
                displayUrl = highRes?.get("src")?.jsonPrimitive?.contentOrNull
                val width = highRes?.get("config_width")?.jsonPrimitive?.contentOrNull
                Log.d(TAG, "Selected image from display_resources with width: $width")
            }
        }
        
        displayUrl = displayUrl ?: ""
        
        // Extract video URL
        var videoUrl = media["video_url"]?.jsonPrimitive?.contentOrNull
        if (videoUrl.isNullOrEmpty()) {
            val videoVersions = media["video_versions"]?.jsonArray
            if (videoVersions != null && videoVersions.isNotEmpty()) {
                // Find the highest quality video
                var maxVideoWidth = 0
                videoVersions.forEach { version ->
                    val versionObj = version.jsonObject
                    val width = versionObj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val url = versionObj["url"]?.jsonPrimitive?.contentOrNull
                    if (width > maxVideoWidth && url != null) {
                        maxVideoWidth = width
                        videoUrl = url
                    }
                }
            }
        }
        
        val isVideo = media["is_video"]?.jsonPrimitive?.boolean 
            ?: (media["media_type"]?.jsonPrimitive?.contentOrNull == "2")
            ?: (videoUrl != null)
        
        val caption = media["edge_media_to_caption"]
            ?.jsonObject?.get("edges")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("node")
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
            ?: media["caption"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        
        val timestamp = media["taken_at_timestamp"]?.jsonPrimitive?.longOrNull
            ?: media["taken_at"]?.jsonPrimitive?.longOrNull
        
        val owner = media["owner"]?.jsonObject ?: media["user"]?.jsonObject
        val ownerUsername = owner?.get("username")?.jsonPrimitive?.contentOrNull
        
        // Handle carousel posts - check both edge_sidecar_to_children (GraphQL) and carousel_media (API)
        val sidecarChildren = media["edge_sidecar_to_children"]?.jsonObject?.get("edges")?.jsonArray
        val carouselMedia = media["carousel_media"]?.jsonArray
        val isCarousel = (sidecarChildren != null && sidecarChildren.isNotEmpty()) ||
                         (carouselMedia != null && carouselMedia.isNotEmpty())
        
        val carouselItems = when {
            sidecarChildren != null && sidecarChildren.isNotEmpty() -> {
                sidecarChildren.map { edge ->
                    val node = edge.jsonObject["node"]?.jsonObject ?: return@map null
                    val itemDisplayUrl = node["display_url"]?.jsonPrimitive?.contentOrNull 
                        ?: getHighestResolutionUrl(node)
                        ?: ""
                    val itemIsVideo = node["is_video"]?.jsonPrimitive?.boolean ?: false
                    val itemVideoUrl = node["video_url"]?.jsonPrimitive?.contentOrNull
                    
                    CarouselItem(
                        displayUrl = itemDisplayUrl,
                        mediaType = if (itemIsVideo) MediaType.VIDEO else MediaType.IMAGE,
                        videoUrl = itemVideoUrl
                    )
                }.filterNotNull()
            }
            carouselMedia != null && carouselMedia.isNotEmpty() -> {
                carouselMedia.map { item ->
                    val itemObj = item.jsonObject
                    val itemDisplayUrl = getHighestResolutionUrl(itemObj) ?: ""
                    val itemIsVideo = itemObj["media_type"]?.jsonPrimitive?.contentOrNull == "2"
                    val itemVideoUrl = getHighestResolutionVideoUrl(itemObj)
                    
                    CarouselItem(
                        displayUrl = itemDisplayUrl,
                        mediaType = if (itemIsVideo) MediaType.VIDEO else MediaType.IMAGE,
                        videoUrl = itemVideoUrl
                    )
                }
            }
            else -> emptyList()
        }
        
        val mediaType = when {
            isCarousel -> MediaType.CAROUSEL
            isVideo -> MediaType.VIDEO
            else -> MediaType.IMAGE
        }
        
        Log.d(TAG, "parseMediaObject result - shortcode: $shortcode, displayUrl length: ${displayUrl.length}, isCarousel: $isCarousel, carouselItems: ${carouselItems.size}")
        
        return InstagramMedia(
            shortcode = shortcode,
            mediaType = mediaType,
            displayUrl = displayUrl,
            thumbnailUrl = media["thumbnail_src"]?.jsonPrimitive?.contentOrNull,
            videoUrl = videoUrl,
            caption = caption,
            timestamp = timestamp,
            ownerUsername = ownerUsername,
            isCarousel = isCarousel,
            carouselMedia = carouselItems
        )
    }
    
    /**
     * Extract the highest resolution image URL from a media object.
     */
    private fun getHighestResolutionUrl(media: JsonObject): String? {
        // Try display_url first
        media["display_url"]?.jsonPrimitive?.contentOrNull?.let { return it }
        
        // Try image_versions2
        val candidates = media["image_versions2"]?.jsonObject?.get("candidates")?.jsonArray
        if (candidates != null && candidates.isNotEmpty()) {
            var maxWidth = 0
            var bestUrl: String? = null
            candidates.forEach { candidate ->
                val obj = candidate.jsonObject
                val width = obj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                if (width > maxWidth && url != null) {
                    maxWidth = width
                    bestUrl = url
                }
            }
            return bestUrl
        }
        
        // Try display_resources
        val displayResources = media["display_resources"]?.jsonArray
        if (displayResources != null && displayResources.isNotEmpty()) {
            return displayResources.lastOrNull()?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull
        }
        
        return null
    }
    
    /**
     * Extract the highest resolution video URL from a media object.
     */
    private fun getHighestResolutionVideoUrl(media: JsonObject): String? {
        media["video_url"]?.jsonPrimitive?.contentOrNull?.let { return it }
        
        val videoVersions = media["video_versions"]?.jsonArray
        if (videoVersions != null && videoVersions.isNotEmpty()) {
            var maxWidth = 0
            var bestUrl: String? = null
            videoVersions.forEach { version ->
                val obj = version.jsonObject
                val width = obj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                if (width > maxWidth && url != null) {
                    maxWidth = width
                    bestUrl = url
                }
            }
            return bestUrl
        }
        
        return null
    }

    private fun extractMediaFromMetaTags(html: String, shortcode: String): InstagramMedia {
        // Fallback extraction from Open Graph meta tags
        // Handle both attribute orders: property then content, or content then property
        val ogImageRegex = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
        val ogImageRegex2 = Regex("""<meta\s+content="([^"]+)"\s+property="og:image"""")
        val ogVideoRegex = Regex("""<meta\s+property="og:video"\s+content="([^"]+)"""")
        val ogVideoRegex2 = Regex("""<meta\s+content="([^"]+)"\s+property="og:video"""")
        val ogDescriptionRegex = Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""")
        
        var imageUrl = ogImageRegex.find(html)?.groupValues?.get(1)
        if (imageUrl == null) {
            imageUrl = ogImageRegex2.find(html)?.groupValues?.get(1)
        }
        var videoUrl = ogVideoRegex.find(html)?.groupValues?.get(1)
        if (videoUrl == null) {
            videoUrl = ogVideoRegex2.find(html)?.groupValues?.get(1)
        }
        val description = ogDescriptionRegex.find(html)?.groupValues?.get(1)
        
        // Decode HTML entities
        imageUrl = imageUrl?.replace("&amp;", "&")
        videoUrl = videoUrl?.replace("&amp;", "&")
        
        // Note: og:image URLs often contain thumbnail resolution
        // We don't try to modify the URL as Instagram validates signatures
        // The embed endpoint should be used for full resolution images
        
        Log.d(TAG, "Meta tags parsed - imageUrl: ${imageUrl?.take(100)}, videoUrl: ${videoUrl?.take(100)}")
        
        val mediaType = if (videoUrl != null) MediaType.VIDEO else MediaType.IMAGE
        
        // Check if the URL contains crop parameters or low-resolution indicators
        // og:image URLs typically contain cropped thumbnails (e.g., "c202.0.608.607a_s640x640")
        val isLowRes = imageUrl?.let { url ->
            url.contains("_s640x640") ||
            url.contains("_s480x480") || 
            url.contains("_s320x320") ||
            url.contains(Regex("""c\d+\.\d+\.\d+\.\d+""")) // crop parameter like c202.0.608.607a
        } ?: false
        
        Log.d(TAG, "Meta tags extraction - isLowRes: $isLowRes")
        
        return InstagramMedia(
            shortcode = shortcode,
            mediaType = mediaType,
            displayUrl = imageUrl ?: "",
            videoUrl = videoUrl,
            caption = description,
            isLowResolution = isLowRes
        )
    }

    /**
     * Downloads raw bytes of the media file.
     */
    suspend fun downloadMediaBytes(url: String): ByteArray {
        val response = httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            }
        }
        return response.bodyAsText().encodeToByteArray()
    }
    
    /**
     * Extract all unique display_url entries from the embedded JSON in the embed page HTML.
     * The embed page contains escaped JSON with "display_url" for each carousel item.
     * We extract URLs ending with s1080x1080 (highest resolution) and deduplicate by image filename.
     */
    private fun extractAllDisplayUrlsFromEmbed(html: String): List<String> {
        // The HTML contains JSON with escaped characters like:
        // display_url\":\"https:\/\/instagram... (escaped quotes and slashes)
        val displayUrlRegex = Regex("""display_url\\":\\"(https:[^"]+)""")
        val matches = displayUrlRegex.findAll(html)
        
        // Unescape the URLs - the URLs contain literal backslash-slash sequences (\/) 
        val allUrls = matches.map { match ->
            var url = match.groupValues[1]
            // Replace escaped slashes using regex: one or more backslashes followed by / -> /
            url = url.replace(Regex("""\\+/"""), "/")
            url.replace("&amp;", "&")
        }.toList()
        
        Log.d(TAG, "extractAllDisplayUrlsFromEmbed: found ${allUrls.size} total display_url entries")
        
        // Deduplicate by extracting the unique image filename (e.g., "624545063_1467674882072125_3979993827732787719_n.jpg")
        // and keeping only the highest resolution version (with s1080x1080)
        val imageNameRegex = Regex("""/([^/]+_n\.(?:jpg|jpeg|png|webp|heic))""")
        
        val urlsByImageName = mutableMapOf<String, String>()
        
        for (url in allUrls) {
            val imageNameMatch = imageNameRegex.find(url)
            if (imageNameMatch != null) {
                val imageName = imageNameMatch.groupValues[1]
                val existingUrl = urlsByImageName[imageName]
                
                // Prefer URLs with 1080 resolution
                if (existingUrl == null || 
                    (url.contains("s1080x1080") && !existingUrl.contains("s1080x1080")) ||
                    (url.contains("1080") && !existingUrl.contains("1080"))) {
                    urlsByImageName[imageName] = url
                }
            }
        }
        
        val uniqueUrls = urlsByImageName.values.toList()
        Log.d(TAG, "extractAllDisplayUrlsFromEmbed: deduplicated to ${uniqueUrls.size} unique images")
        
        return uniqueUrls
    }
}
