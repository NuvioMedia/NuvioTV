package com.nuvio.tv.domain.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.TrackingProviderId

@Composable
fun LibraryListTab.localizedMembershipTitle(): String {
    val provider = when (TrackingProviderId.fromStorage(trackingProviderId)) {
        TrackingProviderId.TRAKT -> stringResource(R.string.trakt_name)
        TrackingProviderId.SIMKL -> stringResource(R.string.simkl_name)
        TrackingProviderId.MDBLIST -> stringResource(R.string.mdblist_name)
        null -> null
    }
    return provider?.let { "$it · ${localizedTitle()}" } ?: localizedTitle()
}

@Composable
fun LibraryListTab.localizedTitle(): String {
    return when {
        key == LOCAL_LIBRARY_LIST_KEY -> title
        key == "simkl:status:watching" -> stringResource(R.string.library_status_watching)
        key == "simkl:status:plantowatch" -> stringResource(R.string.library_status_plan_to_watch)
        key == "simkl:status:hold" -> stringResource(R.string.library_status_on_hold)
        key == "simkl:status:completed" -> stringResource(R.string.library_status_completed)
        key == "simkl:status:dropped" -> stringResource(R.string.library_status_dropped)
        type == LibraryListTab.Type.WATCHLIST -> stringResource(R.string.library_watchlist)
        else -> title
    }
}
