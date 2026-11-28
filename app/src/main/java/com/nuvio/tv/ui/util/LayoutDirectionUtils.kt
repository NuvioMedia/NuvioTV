package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** True when the ambient layout direction is right-to-left (e.g. Hebrew or Arabic UI). */
@Composable
@ReadOnlyComposable
fun isRtlLayout(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl
