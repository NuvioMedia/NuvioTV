package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.MetaPreview

interface RecommendationRepository {
    suspend fun getSurpriseRecommendation(): MetaPreview?
}
