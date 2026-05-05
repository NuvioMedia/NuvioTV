package com.nuvio.tv.ui.util

import androidx.compose.runtime.Immutable

/**
 * A wrapper to make standard Maps stable in the eyes of the Compose compiler.
 * This prevents parent recompositions from forcing child recompositions when
 * the map instance is technically new but the contents are relevant only to
 * specific children.
 */
@Immutable
data class StableMap<K, V>(val map: Map<K, V> = emptyMap()) : Map<K, V> by map

/**
 * A wrapper to make standard Lists stable.
 */
@Immutable
data class StableList<T>(val list: List<T> = emptyList()) : List<T> by list

/**
 * A wrapper to make standard Sets stable.
 */
@Immutable
data class StableSet<T>(val set: Set<T> = emptySet()) : Set<T> by set

/**
 * Extension to easily wrap a Map.
 */
fun <K, V> Map<K, V>.asStable(): StableMap<K, V> = StableMap(this)

/**
 * Extension to easily wrap a List.
 */
fun <T> List<T>.asStable(): StableList<T> = StableList(this)

/**
 * Extension to easily wrap a Set.
 */
fun <T> Set<T>.asStable(): StableSet<T> = StableSet(this)
