package com.arctechnology.zerofomo.model

/**
 * The definitive offline gazetteer for Type A (island-based) location
 * resolution. Bahamian islands are not US political boundaries — they are
 * first-class citizens of the location model, matched by name or alias with
 * zero network dependency.
 *
 * Bounding boxes are deliberately generous (they include surrounding cays);
 * they exist so island filters and future geo queries share one vocabulary.
 */
enum class BahamianIsland(
    val displayName: String,
    val aliases: Set<String>,
    val bounds: BoundingBox,
) {
    NEW_PROVIDENCE(
        "New Providence",
        setOf("nassau", "paradise island", "cable beach", "baha mar", "over the hill"),
        BoundingBox(24.95, -77.60, 25.12, -77.20)),
    GRAND_BAHAMA(
        "Grand Bahama",
        setOf("freeport", "port lucaya", "lucaya", "west end"),
        BoundingBox(26.45, -79.05, 26.80, -77.80)),
    ABACO(
        "Abaco",
        setOf("marsh harbour", "hope town", "treasure cay", "green turtle cay"),
        BoundingBox(25.70, -77.60, 27.10, -76.85)),
    ELEUTHERA(
        "Eleuthera",
        setOf("harbour island", "governor's harbour", "governors harbour",
            "spanish wells", "rock sound", "gregory town"),
        BoundingBox(24.55, -76.95, 25.60, -76.05)),
    EXUMA(
        "Exuma",
        setOf("george town", "great exuma", "little exuma", "staniel cay", "exuma cays"),
        BoundingBox(23.35, -76.25, 24.75, -75.15)),
    ANDROS(
        "Andros",
        setOf("fresh creek", "nicholls town", "mangrove cay", "congo town"),
        BoundingBox(23.65, -78.55, 25.25, -77.55)),
    BIMINI(
        "Bimini",
        setOf("alice town", "north bimini", "south bimini"),
        BoundingBox(25.60, -79.35, 25.80, -79.20)),
    BERRY_ISLANDS(
        "Berry Islands",
        setOf("great harbour cay", "chub cay"),
        BoundingBox(25.40, -77.95, 25.85, -77.60)),
    CAT_ISLAND(
        "Cat Island",
        setOf("arthur's town", "arthurs town", "new bight"),
        BoundingBox(24.10, -75.80, 24.75, -75.20)),
    LONG_ISLAND(
        "Long Island",
        setOf("clarence town", "deadman's cay", "deadmans cay", "salt pond"),
        BoundingBox(22.80, -75.40, 23.70, -74.75)),
    SAN_SALVADOR(
        "San Salvador",
        setOf("cockburn town"),
        BoundingBox(23.90, -74.60, 24.15, -74.40)),
    RUM_CAY("Rum Cay", setOf("port nelson"), BoundingBox(23.60, -74.90, 23.72, -74.75)),
    ACKLINS_CROOKED(
        "Acklins & Crooked Island",
        setOf("acklins", "crooked island", "colonel hill"),
        BoundingBox(22.10, -74.60, 22.90, -73.80)),
    INAGUA(
        "Inagua",
        setOf("matthew town", "great inagua"),
        BoundingBox(20.85, -73.75, 21.35, -73.20)),
    MAYAGUANA("Mayaguana", setOf("abraham's bay", "abrahams bay"),
        BoundingBox(22.30, -73.10, 22.50, -72.70)),
    RAGGED_ISLAND("Ragged Island", setOf("duncan town"),
        BoundingBox(22.00, -75.80, 22.30, -75.60)),
    ;

    companion object {
        /** Case-insensitive match on enum name, display name, or alias.
         *  Alias containment lets "Fish Fry, Arawak Cay, Nassau" resolve. */
        fun match(text: String): BahamianIsland? {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return null
            return entries.firstOrNull { island ->
                t == island.displayName.lowercase() ||
                    t == island.name.lowercase().replace('_', ' ') ||
                    t == island.name.lowercase() ||
                    island.aliases.any { alias -> t == alias || t.contains(alias) }
            }
        }

        fun fromTag(tag: String?): BahamianIsland? =
            tag?.let { t -> entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
    }
}
