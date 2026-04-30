package nl.deruever.vorrin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import nl.deruever.vorrin.R

@OptIn(ExperimentalTextApi::class)
val OutfitFamily = FontFamily(
    Font(
        R.font.outfit,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(400)
        )
    )
)

@OptIn(ExperimentalTextApi::class)
val LoraFamily = FontFamily(
    Font(
        R.font.lora,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(400)
        )
    )
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 11.sp
    ),
    titleLarge = TextStyle(
        fontFamily = LoraFamily,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = LoraFamily,
        fontSize = 16.sp
    ),
    titleSmall = TextStyle(
        fontFamily = LoraFamily,
        fontSize = 14.sp
    ),
)