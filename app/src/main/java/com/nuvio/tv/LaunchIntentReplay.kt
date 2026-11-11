package com.nuvio.tv

import android.content.Intent

/**
 * Whether the intent passed to `onCreate` is a fresh launch to act on. After process death
 * Android recreates the activity with its original intent (and restores the back stack), and
 * Recents can relaunch with it too; acting on a replayed Continue Watching launch reopens the
 * episode the app was first launched for, not the one being watched.
 */
internal fun isFreshLaunchIntent(hasSavedInstanceState: Boolean, intentFlags: Int): Boolean =
    !hasSavedInstanceState && intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
