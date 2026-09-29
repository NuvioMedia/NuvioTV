package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.isPlaceholder

/**
 * A catalog row draws the sweeping placeholder shimmer only while it is still loading and its
 * cards are placeholders. Once the real posters arrive nothing reads the shimmer offset any more.
 */
internal fun showsPlaceholderShimmer(isLoading: Boolean, firstItemImageUrl: String?): Boolean =
    isLoading && firstItemImageUrl.isPlaceholder()

internal fun HeroCarouselRow.showsPlaceholderShimmer(): Boolean =
    showsPlaceholderShimmer(isLoading, items.list.firstOrNull()?.imageUrl)
