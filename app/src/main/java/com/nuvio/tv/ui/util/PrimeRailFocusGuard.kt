package com.nuvio.tv.ui.util

import androidx.compose.runtime.mutableStateOf

/**
 * Esclude temporaneamente le icone del rail laterale (Prime) dal focus
 * search geometrico.
 *
 * Con il focus dentro il dettaglio Impostazioni, GIÙ da una riga aggancia
 * le icone del rail (più vicine in Y, x≈24) invece dell'elemento sotto
 * ("Esci", molto più in basso): il focus search non ha vincoli sulle X.
 * Mentre il dettaglio ha il focus le icone del rail escono dallo search;
 * SX le riapre esplicitamente per non perdere la navigazione verso il rail.
 */
object PrimeRailFocusGuard {
    val railFocusSearchExcluded = mutableStateOf(false)
}
