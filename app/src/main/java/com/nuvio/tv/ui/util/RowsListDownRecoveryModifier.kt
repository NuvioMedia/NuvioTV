package com.nuvio.tv.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.launch

/**
 * Speculare di [rowsListUpRecovery] per GIÙ: intercetta la pressione sulle
 * LazyColumn verticali delle righe (home e schermate sezione) e verifica dove
 * finisce il focus invece di affidarsi al focus search geometrico di Compose.
 *
 * Il tranello che questo modificatore aggira: il focus search non ha vincoli
 * sulle X, quindi da un elemento a sinistra (es. righe di dettaglio di
 * Impostazioni che iniziano a x≈47) o dall'ultimo poster in fondo, un GIÙ
 * alla cieca aggancia le icone del rail laterale (x≈14-54) invece di
 * restare sul contenuto.
 *
 * Qui si muove sempre il focus e si *verifica* dove è finito (lo stato di
 * focus del contenuto è letto a qualche frame di distanza):
 *  - il focus è avanzato dentro le righe: fatto;
 *  - è uscito sul rail: si rientra nel contenuto (focusRestorer riaggancia
 *    la card attiva), si scrolla di una riga per comporre quella successiva
 *    e si riprova — se la lista è già in fondo si resta semplicemente
 *    sull'ultimo elemento, senza mai consegnare il focus al rail;
 *  - non è uscito ma non c'è candidato composto sotto: fai emergere la riga
 *    successiva e riprova.
 */
fun Modifier.rowsListDownRecovery(state: LazyListState): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val contentFocusRequester = LocalContentFocusRequester.current
    var focusInside by remember { mutableStateOf(true) }
    var recovering by remember { mutableStateOf(false) }

    onFocusChanged { focusInside = it.hasFocus }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
                return@onKeyEvent false
            }
            // Una ripetizione è già in corso: assorbi le pressioni ripetute.
            if (recovering) return@onKeyEvent true
            recovering = true
            scope.launch {
                val moved = focusManager.moveFocus(FocusDirection.Down)
                // Attendi che gli eventi di focus siano distribuiti prima di leggere.
                repeat(3) { withFrameNanos { } }
                val escaped = !focusInside
                when {
                    // Focus avanzato dentro le righe (la riga sotto era composta):
                    // fatto. Copre anche il caso in fondo alla lista: se non c'è
                    // nessun candidato il focus resta dov'è e non si esce mai.
                    moved && !escaped -> Unit

                    // Uscito sul rail: rientra nel contenuto. Con la lista già in
                    // fondo il restore riaggancia l'ultimo elemento e ci si ferma
                    // lì (focusRestorer mantiene la card attiva).
                    escaped && !state.canScrollForward -> {
                        runCatching { contentFocusRequester.requestFocusAfterFrames() }
                    }

                    // Uscito con la lista ancora scrollabile: rientra nel contenuto,
                    // scrolla di una riga e riprova, così la riga sotto viene
                    // composta prima del secondo tentativo.
                    escaped && state.canScrollForward -> {
                        runCatching { contentFocusRequester.requestFocusAfterFrames() }
                        val info = state.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: state.firstVisibleItemIndex
                        val target = (lastVisible + 1).coerceAtMost(
                            (info.totalItemsCount - 1).coerceAtLeast(0)
                        )
                        if (target > lastVisible) {
                            state.scrollToItem(target)
                            repeat(3) { withFrameNanos { } }
                            focusManager.moveFocus(FocusDirection.Down)
                            repeat(3) { withFrameNanos { } }
                            if (!focusInside) {
                                runCatching { contentFocusRequester.requestFocusAfterFrames() }
                            }
                        }
                    }

                    // Ancora dentro ma nessun candidato composto sotto e la lista
                    // può ancora scrollare: fai emergere la riga sotto e riprova.
                    !moved && !escaped && state.canScrollForward -> {
                        val info = state.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                            ?: state.firstVisibleItemIndex
                        val target = (lastVisible + 1).coerceAtMost(
                            (info.totalItemsCount - 1).coerceAtLeast(0)
                        )
                        if (target > lastVisible) {
                            state.scrollToItem(target)
                            repeat(3) { withFrameNanos { } }
                            focusManager.moveFocus(FocusDirection.Down)
                        }
                    }

                    // In fondo alla lista e il focus non può avanzare: resta sul
                    // contenuto (l'ultimo elemento) invece di uscire sul rail.
                    else -> {
                        runCatching { contentFocusRequester.requestFocusAfterFrames() }
                    }
                }
                recovering = false
            }
            true
        }
}
