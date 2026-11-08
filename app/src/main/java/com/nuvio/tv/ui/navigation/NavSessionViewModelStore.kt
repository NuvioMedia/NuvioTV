package com.nuvio.tv.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

/**
 * Store owner for the main NavHost. Provided by MainActivity next to its NavController;
 * when absent the NavHost falls back to the activity's store.
 */
val LocalNavViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

/**
 * Keeps the back stack ViewModels of the current main UI session in a store of their own.
 *
 * Switching profile takes the main UI, and with it the NavController, out of composition
 * without popping the back stack. With the activity's store the back stack ViewModels were
 * never cleared, so every switch left another Home (and any open Search or Library) running
 * its pipelines for whichever profile became active next.
 *
 * The holder lives in the activity's store so the session survives activity recreation
 * (e.g. a language change), and the session store is cleared when the session ends.
 */
class NavSessionViewModelStoreHolder : ViewModel() {
    private var store: ViewModelStore? = null

    val viewModelStore: ViewModelStore
        get() = store ?: ViewModelStore().also { store = it }

    fun endSession() {
        store?.clear()
        store = null
    }

    override fun onCleared() {
        endSession()
    }
}

/**
 * Remembers the store owner for [navController], created in the same composition. When that
 * composition goes away, unless the activity is only being recreated, the session ends: the
 * controller's back stack is destroyed and the store is cleared.
 */
@Composable
fun rememberNavSessionViewModelStoreOwner(navController: NavHostController): ViewModelStoreOwner {
    val holder: NavSessionViewModelStoreHolder = viewModel()
    val activity = LocalActivity.current
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, navController) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                // When the activity itself is being destroyed, the controller has already been told.
                if (hostLifecycle.currentState != Lifecycle.State.DESTROYED) {
                    navController.detachFromHostLifecycle()
                }
                holder.endSession()
            }
        }
    }
    return remember(holder) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore
                get() = holder.viewModelStore
        }
    }
}

/**
 * NavHost registers the controller as an observer of the activity lifecycle and never removes it,
 * so a controller that is no longer shown stays referenced until the activity is destroyed. Moves
 * it to a lifecycle of its own and ends that, which also destroys its back stack entries.
 */
private fun NavHostController.detachFromHostLifecycle() {
    val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    owner.lifecycle.currentState = Lifecycle.State.CREATED
    setLifecycleOwner(owner)
    owner.lifecycle.currentState = Lifecycle.State.DESTROYED
}
