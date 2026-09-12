package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/**
 * The retained map remains authoritative for restoration callbacks and lazy items.
 * Remember only creation: caching the whole getOrPut result would return an orphan
 * requester after an owner prunes/clears its map without disposing a lazy item yet.
 */
@Composable
internal fun <K> MutableMap<K, FocusRequester>.rememberFocusRequester(key: K): FocusRequester =
    getOrPut(key) { remember(this, key) { FocusRequester() } }
