package com.sakanaclient.cloud.data.model

import kotlinx.serialization.Serializable

/**
 * Represents media content extracted from an Instagram post.
 */
@Serializable
data class InstagramMedia(
    val shortcode: String,
    val mediaType: MediaType,
    val displayUrl: String,
    val thumbnailUrl: String? = null,
    val videoUrl: String? = null,
    val caption: String? = null,
    val timestamp: Long? = null,
    val ownerUsername: String? = null,
    val isCarousel: Boolean = false,
    val carouselMedia: List<CarouselItem> = emptyList(),
    /**
     * Indicates if only a low-resolution thumbnail could be retrieved.
     * This happens when the embed is disabled for the post.
     */
    val isLowResolution: Boolean = false
)

@Serializable
data class CarouselItem(
    val displayUrl: String,
    val mediaType: MediaType,
    val videoUrl: String? = null
)

@Serializable
enum class MediaType {
    IMAGE,
    VIDEO,
    CAROUSEL
}

/**
 * Result wrapper for Instagram media extraction.
 */
sealed class MediaResult {
    data class Success(val media: InstagramMedia) : MediaResult()
    data class Error(val message: String, val exception: Throwable? = null) : MediaResult()
    data object Loading : MediaResult()
}
