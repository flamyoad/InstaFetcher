package com.flamyoad.instafetcher.domain.usecase

import com.flamyoad.instafetcher.data.model.MediaResult
import com.flamyoad.instafetcher.data.repository.MediaRepository

/**
 * Use case for extracting media URL from an Instagram link.
 */
class ExtractMediaUrlUseCase(
    private val mediaRepository: MediaRepository
) {
    /**
     * Extracts media information from an Instagram URL.
     * 
     * @param url The Instagram URL to process
     * @return MediaResult containing either the extracted media or an error
     */
    suspend operator fun invoke(url: String): MediaResult {
        if (url.isBlank()) {
            return MediaResult.Error("URL cannot be empty")
        }
        
        return mediaRepository.fetchMediaFromUrl(url)
    }
}
