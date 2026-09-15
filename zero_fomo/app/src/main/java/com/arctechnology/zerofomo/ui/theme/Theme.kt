package com.arctechnology.zerofomo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.arctechnology.zerofomo.model.Country
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arctechnology.zerofomo.model.EventCategory

// 0 FOMO brand palette (docs/BRAND.md): Void, Volt, Aqua, Paper, Coral, Graphite.
// The value names are kept from the first release so screens compile unchanged;
// the colours behind them are the brand tokens.
val Aquamarine = Color(0xFF0E9A90)        // Aqua, darkened for light ground
val AquamarineLight = Color(0xFF19D3C5)   // Aqua
val AquaDeep = Color(0xFF0B0F14)          // Void (header gradient start, dark ground)
val Gold = Color(0xFFC8FF2E)              // Volt (saved / live signals on dark)
val GoldDark = Color(0xFF9CCB00)          // Volt, darkened for light ground
val Coral = Color(0xFFC8433E)             // Coral, darkened for light ground
val CoralLight = Color(0xFFFF5C5C)        // Coral
val Sand = Color(0xFFF5F4EF)              // Paper
val SandDim = Color(0xFFECEBE4)           // Paper, dimmed
val Charcoal = Color(0xFF0B0F14)          // Void
val Graphite = Color(0xFF141C26)          // elevated surfaces on Void

private val LightColors = lightColorScheme(
    primary = Aquamarine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDF1EB),
    onPrimaryContainer = Color(0xFF00332F),
    secondary = Color(0xFF4A5A00),
    onSecondary = Color.White,
    secondaryContainer = Gold,
    onSecondaryContainer = Charcoal,
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF5C1210),
    background = Sand,
    onBackground = Charcoal,
    surface = Color.White,
    onSurface = Charcoal,
    surfaceVariant = SandDim,
    onSurfaceVariant = Color(0xFF3D4650),
    outline = Color(0xFF6B7680),
    outlineVariant = Color(0xFFD8D7CF),
    error = Coral,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Charcoal,
    primaryContainer = Color(0xFF3F5200),
    onPrimaryContainer = Color(0xFFE6FF9A),
    secondary = AquamarineLight,
    onSecondary = Color(0xFF00332F),
    secondaryContainer = Color(0xFF0E5F58),
    onSecondaryContainer = Color(0xFFBDF1EB),
    tertiary = CoralLight,
    onTertiary = Color(0xFF4A0A0A),
    tertiaryContainer = Color(0xFF7A2A26),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Charcoal,
    onBackground = Sand,
    surface = Graphite,
    onSurface = Sand,
    surfaceVariant = Color(0xFF1C2631),
    onSurfaceVariant = Color(0xFFC3C8CE),
    outline = Color(0xFF8B949E),
    outlineVariant = Color(0xFF223040),
    error = CoralLight,
    onError = Color(0xFF4A0A0A),
)

/** Rounded, friendly geometry: chips stay pill-ish, cards get soft corners. */
private val ZeroFomoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** System fonts, tuned: heavier display for the wordmark energy, tighter
 *  titles so long event names hold two lines comfortably. */
private val ZeroFomoTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.ExtraBold, fontSize = 25.sp,
        lineHeight = 31.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 14.sp,
        lineHeight = 20.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
        lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

/**
 * [country] tints the mark's arms and the primary/secondary accents with the
 * selected country's flag colours (contrast-checked in [countryPalette]);
 * null keeps the plain brand palette. Ground, surfaces, error and type never
 * change with the country.
 */
@Composable
fun ZeroFomoTheme(
    country: Country? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accents = remember(country, darkTheme) { countryPalette(country, darkTheme).toAccents() }
    val onDark = remember(country) { countryPalette(country, dark = true).toAccents() }
    val base = if (darkTheme) DarkColors else LightColors
    val scheme = if (country == null) base else base.copy(
        primary = accents.primary,
        onPrimary = accents.onPrimary,
        secondary = accents.secondary,
        onSecondary = accents.onSecondary,
    )
    CompositionLocalProvider(
        LocalCountryAccents provides accents,
        LocalCountryAccentsOnDark provides onDark,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = ZeroFomoShapes,
            typography = ZeroFomoTypography,
            content = content,
        )
    }
}

/** Stable per-category accent used by cards, chips and detail headers. */
val EventCategory.accent: Color
    get() = when (this) {
        EventCategory.JUNKANOO_CULTURAL -> Color(0xFFE65100)
        EventCategory.REGATTA_MARITIME -> Color(0xFF0277BD)
        EventCategory.FARMERS_CRAFT_MARKET -> Color(0xFF558B2F)
        EventCategory.FAIR_POPUP -> Color(0xFF9E9D24)
        EventCategory.CONFERENCE_EXPO -> Color(0xFF1565C0)
        EventCategory.CLUB_PROMOTION -> Color(0xFF7B1FA2)
        EventCategory.FESTIVAL -> Color(0xFFAD1457)
        EventCategory.CONCERT_LIVE_MUSIC -> Color(0xFF6A1B9A)
        EventCategory.NIGHTLIFE_PARTY -> Color(0xFF283593)
        EventCategory.BEACH_PARTY -> Color(0xFF00838F)
        EventCategory.COMEDY -> Color(0xFFF9A825)
        EventCategory.PAGEANT -> Color(0xFFC2185B)
        EventCategory.FOOD_DRINK -> Color(0xFF2E7D32)
        EventCategory.SPORTS_FITNESS -> Color(0xFF00695C)
        EventCategory.ARTS_THEATRE -> Color(0xFF4527A0)
        EventCategory.BUSINESS_NETWORKING -> Color(0xFF455A64)
        EventCategory.FAITH_COMMUNITY -> Color(0xFF795548)
        EventCategory.GENERAL, EventCategory.UNKNOWN -> Color(0xFF546E7A)
    }

/** Glyph identity for each category — carries the card medallions and the
 *  detail hero without shipping a single image asset. */
val EventCategory.emoji: String
    get() = when (this) {
        EventCategory.JUNKANOO_CULTURAL -> "🥁"
        EventCategory.REGATTA_MARITIME -> "⛵"
        EventCategory.FARMERS_CRAFT_MARKET -> "🧺"
        EventCategory.FAIR_POPUP -> "🎪"
        EventCategory.CONFERENCE_EXPO -> "💼"
        EventCategory.FESTIVAL -> "🎉"
        EventCategory.CONCERT_LIVE_MUSIC -> "🎶"
        EventCategory.CLUB_PROMOTION -> "🍾"
        EventCategory.NIGHTLIFE_PARTY -> "🪩"
        EventCategory.BEACH_PARTY -> "🏖️"
        EventCategory.COMEDY -> "🎙️"
        EventCategory.PAGEANT -> "👑"
        EventCategory.FOOD_DRINK -> "🍽️"
        EventCategory.SPORTS_FITNESS -> "🏅"
        EventCategory.ARTS_THEATRE -> "🎭"
        EventCategory.BUSINESS_NETWORKING -> "🤝"
        EventCategory.FAITH_COMMUNITY -> "🕊️"
        EventCategory.GENERAL, EventCategory.UNKNOWN -> "📅"
    }
