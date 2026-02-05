package com.flamyoad.instafetcher.util

/**
 * Utility class for parsing Instagram URLs and extracting shortcodes.
 */
class InstagramUrlParser {

    companion object {
        // Matches Instagram post/reel URLs and extracts the shortcode
        // Supports:
        // - https://www.instagram.com/p/ABC123/
        // - https://instagram.com/p/ABC123/
        // - https://www.instagram.com/reel/ABC123/
        // - https://www.instagram.com/tv/ABC123/
        // - https://instagr.am/p/ABC123/
        private val INSTAGRAM_URL_REGEX = Regex(
            """(?:https?://)?(?:www\.)?(?:instagram\.com|instagr\.am)/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?"""
        )
        
        // Matches Instagram story URLs
        private val INSTAGRAM_STORY_REGEX = Regex(
            """(?:https?://)?(?:www\.)?instagram\.com/stories/([^/]+)/(\d+)/?"""
        )
    }

    /**
     * Extracts the shortcode from an Instagram URL.
     * 
     * @param url The Instagram URL
     * @return The shortcode if found, null otherwise
     */
    fun extractShortcode(url: String): String? {
        // First try standard post/reel/tv URLs
        INSTAGRAM_URL_REGEX.find(url)?.let { match ->
            return match.groupValues[1]
        }
        
        return null
    }

    /**
     * Extracts story information from an Instagram story URL.
     * 
     * @param url The Instagram story URL
     * @return Pair of (username, storyId) if found, null otherwise
     */
    fun extractStoryInfo(url: String): Pair<String, String>? {
        INSTAGRAM_STORY_REGEX.find(url)?.let { match ->
            val username = match.groupValues[1]
            val storyId = match.groupValues[2]
            return Pair(username, storyId)
        }
        return null
    }

    /**
     * Validates if the given URL is a valid Instagram URL.
     */
    fun isValidInstagramUrl(url: String): Boolean {
        return INSTAGRAM_URL_REGEX.containsMatchIn(url) || INSTAGRAM_STORY_REGEX.containsMatchIn(url)
    }

    /**
     * Extracts Instagram URL from shared text that may contain other content.
     * Instagram share often includes caption text along with the URL.
     */
    fun extractInstagramUrlFromText(text: String): String? {
        // Look for Instagram URL anywhere in the text
        INSTAGRAM_URL_REGEX.find(text)?.let { match ->
            return match.value
        }
        
        INSTAGRAM_STORY_REGEX.find(text)?.let { match ->
            return match.value
        }
        
        return null
    }
    
    /**
     * Extracts ALL Instagram URLs from shared text.
     * Useful when multiple links are shared at once.
     */
    fun extractAllInstagramUrls(text: String): List<String> {
        val urls = mutableSetOf<String>() // Use set to avoid duplicates
        
        // Find all post/reel/tv URLs
        INSTAGRAM_URL_REGEX.findAll(text).forEach { match ->
            urls.add(match.value)
        }
        
        // Find all story URLs
        INSTAGRAM_STORY_REGEX.findAll(text).forEach { match ->
            urls.add(match.value)
        }
        
        return urls.toList()
    }

    /**
     * Determines the type of Instagram content from the URL.
     */
    fun getContentType(url: String): InstagramContentType {
        return when {
            url.contains("/p/") -> InstagramContentType.POST
            url.contains("/reel/") -> InstagramContentType.REEL
            url.contains("/tv/") -> InstagramContentType.IGTV
            url.contains("/stories/") -> InstagramContentType.STORY
            else -> InstagramContentType.UNKNOWN
        }
    }
}

enum class InstagramContentType {
    POST,
    REEL,
    IGTV,
    STORY,
    UNKNOWN
}
