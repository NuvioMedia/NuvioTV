package com.omnio.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography as TvTypography
import com.omnio.tv.R
import com.omnio.tv.core.uishared.OmnioTypography
import com.omnio.tv.domain.model.AppFont

val DMSansFamily = FontFamily(
    Font(R.font.dm_sans_variable, FontWeight.Normal),
    Font(R.font.dm_sans_variable, FontWeight.Medium),
    Font(R.font.dm_sans_variable, FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_variable, FontWeight.Bold)
)

val OpenSansFamily = FontFamily(
    Font(R.font.opensans_variable, FontWeight.Normal),
    Font(R.font.opensans_variable, FontWeight.Medium),
    Font(R.font.opensans_variable, FontWeight.SemiBold),
    Font(R.font.opensans_variable, FontWeight.Bold)
)

fun getFontFamily(appFont: AppFont): FontFamily = when (appFont) {
    AppFont.INTER -> InterFamily
    AppFont.DM_SANS -> DMSansFamily
    AppFont.OPEN_SANS -> OpenSansFamily
}

/**
 * Form-factor-neutral typography filled with TV-tuned sizes.
 * The phone variant uses :core-ui-shared#phoneTypography. Both produce the
 * same [OmnioTypography] shape; the per-app-module adapter below converts
 * each into its respective MaterialTheme typography type.
 *
 * Sizes match the pre-extraction TV scale verbatim (10-foot-distance tuned).
 * displaySmall and headlineSmall are intentionally placeholder values here
 * because the prior code never set them on androidx.tv.material3.Typography
 * — call sites that read those two slots fall back to TV defaults via the
 * adapter, which omits them when constructing the TvTypography.
 */
fun tvOmnioTypography(fontFamily: FontFamily): OmnioTypography = OmnioTypography(
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(fontFamily = fontFamily),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(fontFamily = fontFamily),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Adapt the neutral [OmnioTypography] into androidx.tv.material3.Typography.
 * Omits displaySmall and headlineSmall so TV defaults apply at those slots,
 * matching pre-extraction behavior (callers that read those slots got the
 * tv-material3 defaults previously).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun OmnioTypography.toTvMaterial3Typography(): TvTypography = TvTypography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall
)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildTvMaterial3Typography(fontFamily: FontFamily): TvTypography =
    tvOmnioTypography(fontFamily).toTvMaterial3Typography()
