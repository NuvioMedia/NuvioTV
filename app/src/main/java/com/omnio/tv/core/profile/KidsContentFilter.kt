package com.omnio.tv.core.profile

import android.content.Context
import com.omnio.tv.R
import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.Meta
import com.omnio.tv.domain.model.MetaPreview
import com.omnio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KidsContentFilter @Inject constructor(
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    val isActive: Boolean
        get() = profileManager.activeProfile?.isKids == true

    val activeMaxRating: AgeRatingTier?
        get() = profileManager.activeProfile?.takeIf { it.isKids }?.maxAgeRating

    fun isAllowedDefinitive(meta: Meta): Boolean {
        val ceiling = activeMaxRating ?: return true
        val tier = AgeRatingTier.normalize(meta.ageRating) ?: return false
        return ceiling.allowsUpTo(tier)
    }

    fun filterPreviews(previews: List<MetaPreview>): List<MetaPreview> {
        if (!isActive) return previews
        val ceiling = activeMaxRating ?: return previews
        return previews.filter { preview ->
            val tier = AgeRatingTier.normalize(preview.ageRating)
            tier == null || ceiling.allowsUpTo(tier)
        }
    }

    fun reasonBlocked(profile: UserProfile?): String? {
        val ceiling = profile?.takeIf { it.isKids }?.maxAgeRating ?: return null
        return context.getString(R.string.kids_restricted_with_rating, ceiling.label)
    }
}
