package com.nuvio.tv.domain.model

/** URL used by shimmer/skeleton placeholder cards before real catalog data arrives. */
const val PLACEHOLDER_IMAGE_URL = "placeholder://empty"

/** Returns true if this URL is a placeholder (not a real image). */
fun String?.isPlaceholder(): Boolean = this == PLACEHOLDER_IMAGE_URL
