package com.nuvio.tv.domain.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY

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
