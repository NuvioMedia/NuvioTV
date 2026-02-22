@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun CompanyLogosSection(
    title: String,
    companies: List<MetaCompany>
) {
    if (companies.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = companies,
                key = { company -> "$title-${company.name}-${company.logo.orEmpty()}" }
            ) { company ->
                CompanyLogoCard(company = company)
            }
        }
    }
}

@Composable
private fun CompanyLogoCard(company: MetaCompany) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidthPx = remember(density) { with(density) { 140.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 56.dp.roundToPx() } }
    val logoModel = remember(context, company.logo, logoWidthPx, logoHeightPx) {
        company.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(false)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }

    Card(
        onClick = { },
        modifier = Modifier
            .width(140.dp)
            .height(56.dp),
        colors = CardDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
        contentAlignment = Alignment.Center
        ) {
            if (logoModel != null) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = company.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = company.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
