package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

data class TopBarSectionTab(
    val route: String,
    val label: String
)

/**
 * Banda nera sotto la barra: le tab finiscono a 64dp (topPadding sm=8dp +
 * altezza tab 56dp), quindi la banda copre esattamente la fascia delle tab.
 * Ridurla da 96dp a 64dp alza il tetto del blocco testo dell'hero di 32dp (il
 * padding top dello spec è questa stessa costante): con logo grande e premi su
 * due righe il blocco supera i 190dp e senza spazio in cima la descrizione
 * finiva tagliata a metà riga. Mantenere questa costante ≥64dp, o le tab
 * restano scoperte.
 */
internal val TOP_BAR_BAND_HEIGHT = 64.dp

/**
 * Barra alta delle sezioni — il riferimento è Prime Video: una striscia di
 * tab testuali in alto (Home · Extra · Anime · Calendario) raggiungibile
 * con SU dalla prima riga di contenuti, che si dimmera quando il focus è
 * altrove. A destra resta vuoto: nessun marchio e nessun avatar (richiesta
 * dell'utente: la barra deve restare solo navigazione). Le etichette delle
 * sezioni vivono QUI; il rail a sinistra resta a icone.
 *
 * Sotto le tab c'è una banda nera opaca (come la colonna nera del rail): il
 * contenuto che scorre in alto sparisce dietro la barra invece di passarci
 * attraverso, e la dissolvenza finale evita il taglio netto.
 */
@Composable
internal fun NuvioSectionTopBar(
    tabs: List<TopBarSectionTab>,
    selectedRoute: String?,
    tabFocusRequesters: Map<String, FocusRequester>,
    onNavigate: (String) -> Unit,
    startPadding: Dp = 0.dp,
    topPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    var barHasFocus by remember { mutableStateOf(false) }
    val barAlpha by animateFloatAsState(
        targetValue = if (barHasFocus) 1f else 0.72f,
        label = "topBarAlpha"
    )

    Box(
        modifier = modifier.height(TOP_BAR_BAND_HEIGHT)
    ) {
        // Banda nera: opaca dietro le tab (fino a ~48dp) con la dissolvenza
        // che termina esattamente a fine tab (64dp), così il contenuto che
        // scorre in alto sparisce dietro la fascia delle tab senza taglio netto.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black,
                            0.75f to Color.Black,
                            0.93f to Color.Black.copy(alpha = 0.85f),
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding + NuvioTheme.spacing.lg, top = topPadding)
                .height(56.dp)
                // Solo le tab si dimmeranno: la banda resta sempre opaca.
                .graphicsLayer { alpha = barAlpha }
                .onFocusChanged { state -> barHasFocus = state.hasFocus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            // Nessun marchio "nuvio" e nessun avatar: la barra è solo una
            // striscia di tab, così non c'è nulla da sovrapporre ai testi.
            tabs.forEach { tab ->
                SectionTab(
                    label = tab.label,
                    selected = tab.route == selectedRoute,
                    focusRequester = tabFocusRequesters[tab.route],
                    onClick = { onNavigate(tab.route) }
                )
            }
        }
    }
}

@Composable
private fun SectionTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = when {
        focused -> Color.White
        selected -> NuvioTheme.colors.TextPrimary
        else -> NuvioTheme.colors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) NuvioTheme.colors.FocusBackground else Color.Transparent)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state -> focused = state.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1
        )
    }
}
