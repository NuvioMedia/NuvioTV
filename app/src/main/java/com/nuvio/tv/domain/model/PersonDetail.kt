package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CrewJobInfo(
    val jobName: String,
    val hasMovies: Boolean,
    val hasTv: Boolean
)

@Immutable
data class PersonDetail(
    val tmdbId: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePhoto: String?,
    val knownFor: String?,
    val movieCredits: List<MetaPreview>,
    val tvCredits: List<MetaPreview>,
    val hasCastCredits: Boolean = false,
    val crewJobs: List<CrewJobInfo> = emptyList()
)
