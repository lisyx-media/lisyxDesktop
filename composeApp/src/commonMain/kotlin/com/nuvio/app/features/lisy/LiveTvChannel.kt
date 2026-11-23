package com.nuvio.app.features.lisy

internal data class LiveTvVariant(
    val entry: LisyEntry,
    val logo: String?,
    val category: String?,
    val country: String?,
)

internal data class LiveTvChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val categories: Set<String>,
    val country: String?,
    val variants: List<LiveTvVariant>,
)

private val qualitySuffix = Regex("""[\s(\[|_-]+(SD|HD|FHD|UHD|4K|720P|1080P|2160P)[])]*$""", RegexOption.IGNORE_CASE)
private val marketPrefix = Regex("""^[\s|\[]*([A-Z]{2})\s*[]:|_-]\s*""", RegexOption.IGNORE_CASE)
private val markets = mapOf(
    "FR" to "France", "BE" to "Belgique", "CH" to "Suisse", "CA" to "Canada",
    "GB" to "Royaume-Uni", "UK" to "Royaume-Uni", "US" to "États-Unis",
    "DE" to "Allemagne", "ES" to "Espagne", "IT" to "Italie", "PT" to "Portugal",
    "NL" to "Pays-Bas", "MA" to "Maroc", "DZ" to "Algérie", "TN" to "Tunisie",
)

internal fun liveTvCountry(explicit: String?, name: String, category: String?): String? {
    val value = explicit?.trim()?.takeIf { it.isNotEmpty() }
    if (value != null) return markets[value.uppercase()] ?: value
    val prefix = marketPrefix.find(name)?.groupValues?.get(1)?.uppercase()
    return markets[prefix] ?: markets.entries.firstOrNull { (code, label) ->
        category.equals(code, true) || category.equals(label, true) ||
            marketPrefix.find(category.orEmpty())?.groupValues?.get(1)?.equals(code, true) == true
    }?.value
}

private fun LiveTvVariant.baseName(): String =
    entry.title.replace(qualitySuffix, "").trim().ifBlank { entry.title }

private fun LiveTvVariant.qualityRank(): Int =
    when (qualitySuffix.find(entry.title)?.groupValues?.get(1)?.uppercase()) {
        "4K", "UHD", "2160P" -> 4
        "FHD", "1080P" -> 3
        "HD", "720P" -> 2
        "SD" -> 1
        else -> 0
    }

internal fun groupLiveTvChannels(variants: List<LiveTvVariant>): List<LiveTvChannel> =
    variants.distinctBy { it.entry.id }.groupBy { variant ->
        // A missing market or category gives us too little evidence to merge homonyms.
        if (variant.country != null && variant.category != null && variant.qualityRank() > 0) {
            Triple(variant.country, variant.category, variant.baseName().lowercase())
        } else Triple(null, null, variant.entry.id)
    }.values.map { group ->
        val ordered = group.sortedByDescending { it.qualityRank() }
        LiveTvChannel(
            id = group.first().entry.id,
            name = group.first().baseName(),
            logo = ordered.firstNotNullOfOrNull { it.logo },
            categories = group.mapNotNull { it.category }.toSet(),
            country = group.first().country,
            variants = ordered,
        )
    }
