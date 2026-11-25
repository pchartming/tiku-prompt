package io.github.pchartming.tikuprompt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

val TikuTypography = Typography(
    titleLarge = base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleSmall = base.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = base.bodyLarge.copy(
        lineHeight = 24.sp,
    ),
    bodyMedium = base.bodyMedium.copy(
        lineHeight = 22.sp,
    ),
    bodySmall = base.bodySmall.copy(
        lineHeight = 19.sp,
    ),
    labelLarge = base.labelLarge.copy(
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = base.labelMedium.copy(
        letterSpacing = 0.sp,
    ),
)