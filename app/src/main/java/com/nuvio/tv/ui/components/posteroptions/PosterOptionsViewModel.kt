package com.nuvio.tv.ui.components.posteroptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Standalone ViewModel that owns a [PosterOptionsController] for screens that
 * don't have a dedicated ViewModel of their own (e.g. screens that reuse another
 * feature's ViewModel as their primary). Obtain via `hiltViewModel<PosterOptionsViewModel>()`.
 */
@HiltViewModel
class PosterOptionsViewModel @Inject constructor(
    val controller: PosterOptionsController
) : ViewModel() {

    init {
        controller.bind(viewModelScope)
    }
}
