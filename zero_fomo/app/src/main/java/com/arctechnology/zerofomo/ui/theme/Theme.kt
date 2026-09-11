package com.arctechnology.zerofomo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arctechnology.zerofomo.model.EventCategory

// Bahamian flag palette: aquamarine, gold, black — plus conch-shell coral.
val Aquamarine = Color(0xFF00778B)
val AquamarineLight = Color(0xFF4FA8B8)
val AquaDeep = Color(0xFF00505E)
val Gold = Color(0xFFFFC72C)
val GoldDark = Color(0xFFB8860B)
val Coral = Color(0xFFB3574A)
val CoralLight = Color(0xFFE8A398)
val Sand = Color(0xFFFAF6EE)
val SandDim = Color(0xFFEFE8DA)
val Charcoal = Color(0xFF121417)

private val LightColors = lightColorScheme(
    primary = Aquamarine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E7EF),
    onPrimaryContainer = AquaDeep,
    secondary = GoldDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7A3),
    onSecondaryContainer = Color(0xFF4A3A00),
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD3),
    onTertiaryContainer = Color(0xFF5C1A10),
    background = Sand,
    onBackground = Color(0xFF1B1D1E),
    surface = Color.White,
    onSurface = Color(0xFF1B1D1E),
    surfaceVariant = SandDim,
    onSurfaceVariant = Color(0xFF49484A),
    outline = Color(0xFF7A797B),
    outlineVariant = Color(0xFFDCD5C8),
)

private val DarkColors = darkColorScheme(
    primary = AquamarineLight,
    onPrimary = Color(0xFF00363F),
    primaryContainer = AquaDeep,
    onPrimaryContainer = Color(0xFFB7E7EF),
    secondary = Gold,
    onSecondary = Color(0xFF3D2F00),
    secondaryContainer = Color(0xFF574400),
    onSecondaryContainer = Color(0xFFFFE7A3),
    tertiary = CoralLight,
    onTertiary = Color(0xFF44160D),
    tertiaryContainer = Color(0xFF7A3A2E),
    onTertiaryContainer = Color(0xFFFFDAD3),
    background = Charcoal,
    onBackground = Color(0xFFE3E2E4),
    surface = Color(0xFF1B1E22),
    onSurface = Color(0xFFE3E2E4),
    surfaceVariant = Color(0xFF25292E),
    onSurfaceVariant = Color(0xFFC6C5C8),
    outline = Color(0xFF909092),
    outlineVariant = Color(0xFF3A3E44),
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

@Composable
fun ZeroFomoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ZeroFomoShapes,
        typography = ZeroFomoTypography,
        content = content,
    )
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
