package com.arctechnology.zerofomo.model

/** One ISO 3166-1 country from the bundled gazetteer, with the two flag
 *  colours that drive the country-tinted mark and accents (docs/BRAND.md §9).
 *  Colours are ARGB longs so this model stays free of Android/Compose types. */
data class Country(
    val code: String,          // ISO 3166-1 alpha-2, upper-case
    val name: String,
    val capital: String,
    val continent: String,     // GeoNames continent code: NA, SA, EU, AF, AS, OC, AN
    val currency: String,
    val colorA: Long,          // dominant flag colour -> left arm
    val colorB: Long,          // second flag colour -> right arm
) {
    /** Regional-indicator pair; renders as the flag on every modern keyboard font. */
    val flagEmoji: String
        get() = code.uppercase().map { c ->
            String(Character.toChars(0x1F1E6 + (c - 'A')))
        }.joinToString("")

    companion object {
        /** Launch market and the offline fallback when nothing else is known. */
        val BAHAMAS = Country("BS", "Bahamas", "Nassau", "NA", "BSD", 0xFF00778B, 0xFFFFC72C)
    }
}
