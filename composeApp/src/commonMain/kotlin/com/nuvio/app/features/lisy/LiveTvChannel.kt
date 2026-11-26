package com.nuvio.app.features.lisy

import kotlinx.serialization.Serializable

@Serializable
internal data class LiveTvVariant(
    val entry: LisyEntry,
    val logo: String?,
    val category: String?,
    val country: String?,
    val replayAvailable: Boolean = false,
    val guideStationId: String? = null,
    val language: String? = null,
    val rawCategory: String? = null,
)

@Serializable
internal data class LiveTvChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val categories: Set<String>,
    val country: String?,
    val variants: List<LiveTvVariant>,
)

private val spaces = Regex("""\s+""")
private val marketPrefix = Regex("""^\s*[|\[]?([A-Za-z]{2,6})\s*[\]:|_-]\s*[|:_-]*\s*""")
private val qualityPrefix = Regex("""^\s*[|\[](4K|8K|UHD|FHD|HD|HEVC)[|\]]\s*""", RegexOption.IGNORE_CASE)
private val qualitySuffix = Regex(
    """[\s(/\[|_·-]+(8K|4K|UHD|HDR10\+?|HDR|FULL[ _-]?HD|FHD|HD|SD|3840P|2160P|1080[PI]|720P|HEVC?|H[ .]?26[45]|X26[45]|RAW|[256]0\s?FPS|LOW BW)[\s)\]|]*$""",
    RegexOption.IGNORE_CASE,
)
private val marks = Regex("""[\s◉●○★☆✓✔•]+$""")
private val heading = Regex("""^\s*#{3,}.*#{3,}\s*$""")
private val markets = mapOf(
    "FR" to "France", "BE" to "Belgique", "CH" to "Suisse", "CA" to "Canada", "CAFR" to "Canada",
    "GB" to "Royaume-Uni", "UK" to "Royaume-Uni", "US" to "États-Unis", "USA" to "États-Unis",
    "DE" to "Allemagne", "ES" to "Espagne", "IT" to "Italie", "PT" to "Portugal", "IE" to "Irlande",
    "NL" to "Pays-Bas", "MA" to "Maroc", "DZ" to "Algérie", "TN" to "Tunisie", "HT" to "Haïti",
    "NO" to "Norvège", "SE" to "Suède", "DK" to "Danemark", "FI" to "Finlande", "IS" to "Islande",
    "PL" to "Pologne", "TR" to "Turquie", "GR" to "Grèce", "RU" to "Russie", "UA" to "Ukraine",
    "RO" to "Roumanie", "BG" to "Bulgarie", "CZ" to "Tchéquie", "SK" to "Slovaquie", "HU" to "Hongrie",
    "AT" to "Autriche", "AU" to "Australie", "NZ" to "Nouvelle-Zélande", "IN" to "Inde",
    "PK" to "Pakistan", "BD" to "Bangladesh", "CN" to "Chine", "JP" to "Japon", "KR" to "Corée du Sud",
    "AE" to "Émirats arabes unis", "SA" to "Arabie saoudite", "EG" to "Égypte", "MX" to "Mexique",
    "BR" to "Brésil", "AR" to "Argentine", "CL" to "Chili", "CO" to "Colombie", "ZA" to "Afrique du Sud",
    "NG" to "Nigeria", "GH" to "Ghana", "MG" to "Madagascar", "SN" to "Sénégal", "CI" to "Côte d’Ivoire",
    "CM" to "Cameroun", "LU" to "Luxembourg",
)
private val countryNames = mapOf(
    "france" to "France", "belgique" to "Belgique", "suisse" to "Suisse", "canada" to "Canada",
    "usa" to "États-Unis", "ireland" to "Irlande", "haiti" to "Haïti",
    "united states" to "États-Unis", "united kingdom" to "Royaume-Uni", "england" to "Royaume-Uni",
    "germany" to "Allemagne", "deutschland" to "Allemagne", "spain" to "Espagne", "italy" to "Italie",
    "netherlands" to "Pays-Bas", "norway" to "Norvège", "sweden" to "Suède", "denmark" to "Danemark",
    "poland" to "Pologne", "polska" to "Pologne", "turkey" to "Turquie", "greece" to "Grèce",
    "switzerland" to "Suisse", "belgium" to "Belgique", "morocco" to "Maroc", "algeria" to "Algérie",
)
private val countryAliases = countryNames + markets.values.associateBy { editorialKey(it) }
private val countryWord = Regex(countryAliases.keys.sortedByDescending(String::length)
    .joinToString("|", prefix = """\b(?:""", postfix = """)\b""", transform = Regex::escape))
private val languageNames = mapOf(
    "FR" to "fr", "FRA" to "fr", "FRE" to "fr", "FRENCH" to "fr", "FRANCAIS" to "fr",
    "EN" to "en", "ENG" to "en", "ENGLISH" to "en", "ANGLAIS" to "en",
    "DE" to "de", "DEU" to "de", "GER" to "de", "GERMAN" to "de", "ALLEMAND" to "de",
    "PL" to "pl", "POL" to "pl", "POLISH" to "pl", "POLONAIS" to "pl",
    "ES" to "es", "SPA" to "es", "SPANISH" to "es", "ESPAGNOL" to "es",
    "IT" to "it", "ITA" to "it", "ITALIAN" to "it", "ITALIEN" to "it",
    "PT" to "pt", "POR" to "pt", "PORTUGUESE" to "pt",
    "AR" to "ar", "ARA" to "ar", "ARABIC" to "ar", "ARABE" to "ar",
    "NL" to "nl", "NLD" to "nl", "DUT" to "nl", "DUTCH" to "nl",
    "TR" to "tr", "TUR" to "tr", "TURKISH" to "tr",
)

private fun String.prefixMarket(): String? = marketPrefix.find(this)?.groupValues?.get(1)?.uppercase()?.let(markets::get)

internal fun liveTvCountry(explicit: String?, name: String, category: String?): String? {
    val value = explicit?.trim()?.takeUnless { it in listOf("", "0", "-") }
    if (value != null) {
        val country = markets[value.uppercase()] ?: countryAliases[editorialKey(value)]
        if (country != null) return country
    }
    return name.prefixMarket() ?: category?.prefixMarket() ?: markets[category?.trim()?.uppercase()]
        ?: countryWord.find(editorialKey(category.orEmpty()))?.value?.let(countryAliases::get)
}

internal fun liveTvCountryFlag(country: String?): String? {
    val canonical = country?.let { markets[it.uppercase()] ?: countryAliases[editorialKey(it)] } ?: return null
    val code = markets.entries.firstOrNull { it.value == canonical && it.key.length == 2 && it.key != "UK" }?.key ?: return null
    return code.map { (0xD83C).toChar().toString() + (0xDDE6 + it.code - 'A'.code).toChar() }.joinToString("")
}

internal fun liveTvLanguage(explicit: String?, name: String, category: String?): String? {
    liveTvLanguages(explicit).takeIf { it.isNotEmpty() }?.let { return it.sorted().joinToString(",") }
    val prefix = marketPrefix.find(name)?.groupValues?.get(1)?.uppercase()
    if (prefix == "EN" || prefix == "ENG") return "en"
    // Category names identify a language only when the provider actually labels the language.
    return Regex("""\b(english|anglais|french|francais|polish|polonais|german|allemand|arabic|arabe)\b""")
        .find(editorialKey(category.orEmpty()))?.value?.uppercase()?.let(languageNames::get)
}

internal fun liveTvLanguages(value: String?): Set<String> = value.orEmpty()
    .split(',', ';', '|', '/', '+')
    .mapNotNull { languageNames[it.trim().substringBefore('-').substringBefore('_').uppercase()] }
    .toSet()

private data class Theme(val name: String, val matches: Regex)
private fun theme(name: String, words: String) = Theme(name, Regex("""\b(?:$words)\b"""))

private val themes = listOf(
    theme("Adulte", "adult[es]*|xxx|erotique|charme"),
    theme("Sport", "sports?|[0-9]+sports?|supersports?|football|foot|soccer|rugby|basketball|golf|tennis|hockey|baseball|boxing|formul[ae] 1|moto ?gp|liga|ligue ?1|league|uefa|dazn|bein|eurosports?|espn[1-9]?|nesn|setanta|tsn[1-5]?|rds[12]?|sportsnet|nba|wnba|nhl|nfl|mlb|milb|mls|ncaa|epl|nbcs|flo|l.equipe live"),
    theme("Jeunesse", "kids?|enfants?|jeunesse|junior|cartoons?|nickelodeon|nicktoons|gulli|tiji|canal j|toons?|teletoon|telemagino|baby tv|boomerang|disney channel"),
    theme("Musique", "musique|music|mtv|mezzo|mcm|concerts?|clips?|classica|trace (?:urban|naija|mziki|toca|tropical|latina|caribbean|carribean|ayiti|vanilla|gospel)|stingray|zouk tv|tvm3|lfm tv|rouge tv|bblack"),
    theme("Actualités", "news|actualites?|infos?|lci|cnews|bfm(?:tv)?|cnn|cnbc|msnbc|fox business|hln|france ?24|al jazeera|euronews"),
    theme("Découverte", "documentaires?|documentar(?:y|ies)|decouverte|discovery|nature|histoire|history|national geographic|nat geo|ushuaia|animaux|science|animal planet|crime investigation|planete|seasons|odisea"),
    theme("Cinéma", "cine(?:ma|mas)?|films?|movies?|turner classic|tcm|sky store|sky primafila|ocs|horror channel|film4"),
    theme("Séries", "series|sitcoms?|box ?sets?|tv shows?"),
    theme("Divertissement", "entertainment|divertissements?|comedie|comedy|star academy|reality|food network|cooking channel|hgtv|gourmand tv|myzen fit|luxe tv"),
    theme("Régionales", "(?:martinique|guadeloupe|guyane|reunion) (?:1ere|premiere)|atv martinique|tele peyi guyane"),
    theme("Généralistes", "general(?:es?|istes?)?|nationales?|tf1|france [2345]|m6|arte|tfx|tmc|w9|itv ?[1234]|bbc [12]|nbc|cbs|abc|pbs|telemundo|univision|tv5 ?monde|rte (?:one|two)|virgin media (?:one|two|three|four)|tg ?4|utv"),
    theme("Événements", "events?|evenements?|ppv"),
)
private fun editorialKey(text: String): String = text.lowercase()
    .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('à', 'a').replace('ô', 'o')
    .replace('ï', 'i').replace('î', 'i').replace('ç', 'c').replace('’', '\'').replace('_', ' ').replace(spaces, " ")

internal fun liveTvCategory(raw: String?): String? {
    val normalized = editorialKey(raw ?: return null)
    if ("discovery+ ppv" in normalized) return "Événements"
    if (themedMovieFeed.containsMatchIn(normalized)) return "Cinéma"
    return themes.firstOrNull { it.matches.containsMatchIn(normalized) }?.name
}

private val themedMovieFeed = Regex(
    """\b(?:netflix|prime|cinema|movies?)\s+(?:action|adventure|aventure|drama|drame|fantasy|fantastique|horror|horreur|romance|sci[ -]?fi|science fiction|thriller|comedy|comedie)\b""",
)

// Match full station names inside their market. A provider's general bouquet often mixes unrelated channels.
private val frenchStations = listOf(
    Regex("""tf1|france [2345]|arte|m6|6ter|t18|novo19|tmc|tfx|w9|rmc story|canal\+|tv5 ?monde(?: fbs)?""") to setOf("Généralistes"),
    Regex("""canal\+(?:box office|grand ecran|cinema)|rtl9|action|eurochannel|nollywood tv""") to setOf("Cinéma"),
    Regex("""tf1 (?:series films|films & series)|syfy""") to setOf("Cinéma", "Séries"),
    Regex("""serie ?club|13eme rue|tv breizh|warner tv(?: next)?""") to setOf("Séries"),
    Regex("""canal\+(?:live \d+|360|sport ?360)|infosport\+|l'equipe(?: 21)?|equidia(?: live)?|ol play|automoto|cheval tv|eurosport(?:s)?(?: \d+)?""") to setOf("Sport"),
    Regex("""canal\+docs|planete(?:\+(?:crime|aventure)?| (?:ae|ci))|ushuaia(?: tv)?|histoire tv|seasons|maison & travaux|museum tv""") to setOf("Découverte"),
    Regex("""lci|lcp|public senat|africa 24|france ?info|bfm ?(?:business|tv)""") to setOf("Actualités"),
    Regex("""piwi\+|teletoon(?:\+(?:1)?)?|cartoonito|ludikids|dreamworks|canal\+kids""") to setOf("Jeunesse"),
    Regex("""cstar(?: hits)?|clubbing tv|europe 2 pop tv|trace (?:caribbean|carribean|ayiti|vanilla)""") to setOf("Musique"),
    Regex("""(?:martinique|guadeloupe|guyane|reunion) (?:1ere|premiere)|tvpi|tv8 mont blanc|tele angers|weo picardie|antenne reunion""") to setOf("Régionales"),
    Regex("""paris premiere|teva|olympia tv|e!""") to setOf("Divertissement"),
)
private val usAffiliate = Regex("""^(?:abc|cbs|cw|fox|my|nbc|pbs) (?:\d{1,3}[+ ]|\([kw][a-z\d-]+\)|[^()]+ \([kw][a-z\d-]+\))""")
private val canadaAffiliates = Regex("""^(?:cbc|ctv|global|radio-canada|citytv) (?:montreal|toronto|ottawa|quebec|calgary|vancouver|edmonton|halifax|winnipeg|fredericton|charlottetown|st\.? john's)$""")

private val plusSpacing = Regex("""\s*\+\s*""")
private val canal360Alias = Regex("""canal\+(?:sport\s*)?360""")
private val stationAliases = mapOf(
    "tf1" to "tf1",
    "tf1series" to "tf1 series films",
    "tf1seriesfilms" to "tf1 series films",
    "tf1films&series" to "tf1 series films",
    "6ter" to "6ter",
    "rtl9" to "rtl9",
    "cnews" to "cnews",
    "france24" to "france 24",
)
private val canonicalStationNames = mapOf(
    "tf1" to "TF1",
    "tf1 series films" to "TF1 SÉRIES FILMS",
    "6ter" to "6TER",
    "rtl9" to "RTL9",
    "cnews" to "CNEWS",
    "france 24" to "FRANCE 24",
    "canal+360" to "CANAL+ 360",
)
private fun stationKey(name: String): String = editorialKey(name).replace(plusSpacing, "+")
    .let { key ->
        if (canal360Alias.matches(key)) "canal+360" else stationAliases[key.replace(spaces, "")] ?: key
    }

private data class NamedFeed(val variant: LiveTvVariant, val name: String, val qualityTag: Boolean, val rank: Int)

private fun normalizeFeed(variant: LiveTvVariant): NamedFeed {
    val original = variant.entry.title.trim()
    val prefix = marketPrefix.find(original)?.groupValues?.get(1)?.uppercase()
    var name = if (original.prefixMarket() != null || prefix in setOf("EN", "ENG")) {
        original.replaceFirst(marketPrefix, "")
    } else {
        original
    }
    val quality = qualityPrefix.find(name)
    name = name.replaceFirst(qualityPrefix, "").replace(marks, "")
        .replace("ᵁᴴᴰ", "UHD").replace("ᶠᴴᴰ", "FHD").replace("ᴴᴰ", "HD").replace("ᴿᴬᵂ", "RAW")
    var rank = qualityRank(quality?.groupValues?.get(1).orEmpty())
    var qualityTag = quality != null
    while (true) {
        val suffix = qualitySuffix.find(name) ?: break
        rank = maxOf(rank, qualityRank(suffix.groupValues[1]))
        qualityTag = true
        name = name.substring(0, suffix.range.first).trimEnd()
    }
    name = name.trim().replace(spaces, " ").ifBlank { original }
    name = canonicalStationNames[stationKey(name)] ?: name
    val country = liveTvCountry(variant.country, original, variant.rawCategory)
        ?: variant.guideStationId?.substringAfterLast('.', "")?.uppercase()?.let(markets::get)
    val language = liveTvLanguage(variant.language, original, variant.rawCategory)
    return NamedFeed(
        variant.copy(country = country, language = language, category = variant.category ?: liveTvCategory(variant.rawCategory)),
        name, qualityTag, rank,
    )
}

private fun qualityRank(value: String): Int = when (value.uppercase()) {
    "8K" -> 5
    "4K", "UHD", "2160P", "3840P" -> 4
    "FHD", "FULLHD", "FULL HD", "FULL-HD", "FULL_HD", "1080P", "1080I" -> 3
    "HD", "720P" -> 2
    "SD" -> 1
    else -> 0
}

internal fun groupLiveTvChannels(variants: List<LiveTvVariant>): List<LiveTvChannel> {
    val feeds = variants.distinctBy { it.entry.id }.filterNot { heading.matches(it.entry.title) }.map(::normalizeFeed)
    val byName = feeds.groupBy { stationKey(it.name) }
    // Bouquets often supply one logo for every UHD channel. Count stations, not quality variants.
    val logoStationCounts = byName.values.flatMap { members ->
        members.mapNotNull { it.variant.logo }.distinct()
    }.groupingBy { it }.eachCount()

    return byName.flatMap { (key, namesakes) ->
        // Attach an unmarked UHD feed only when the lineup identifies a single market for that name.
        val onlyCountry = namesakes.mapNotNull { it.variant.country }.distinct().singleOrNull()
        namesakes.groupBy { it.variant.country ?: onlyCountry.takeIf { _ -> it.qualityTag } }.flatMap { (country, group) ->
            val knownStation = when {
                country == "France" -> frenchStations.firstOrNull { it.first.matches(key) }?.second
                country == "États-Unis" && usAffiliate.containsMatchIn(key) -> setOf("Régionales")
                country == "Canada" && canadaAffiliates.matches(key) -> setOf("Régionales")
                else -> null
            }
            val editions = stationEditions(group, knownStation != null, key, country)
            val multipleLanguages = editions.mapNotNull { edition ->
                edition.map { liveTvLanguages(it.variant.language) }.filter { it.size == 1 }.flatten().distinct().singleOrNull()
            }.distinct().size > 1
            editions.map { members ->
                val editionLanguage = members.map { liveTvLanguages(it.variant.language) }
                    .filter { it.size == 1 }.flatten().distinct().singleOrNull()
                val name = members.first().name + if (multipleLanguages && editionLanguage != null) {
                    " · ${editionLanguage.uppercase()}"
                } else {
                    ""
                }
                val ordered = members.sortedByDescending { it.rank }.map { it.variant }
                val categories = members.mapNotNull { it.variant.category }.toSet()
                val nameTheme = liveTvCategory(name)
                val topics = when {
                    "Adulte" in categories -> setOf("Adulte")
                    knownStation != null -> knownStation
                    nameTheme != null && nameTheme != "Généralistes" -> setOf(nameTheme)
                    categories.isNotEmpty() -> categories
                    nameTheme != null -> setOf(nameTheme)
                    else -> emptySet()
                }
                LiveTvChannel(
                    id = members.first().variant.entry.id,
                    name = name,
                    logo = channelLogo(members, logoStationCounts),
                    categories = topics,
                    country = country,
                    variants = ordered,
                )
            }
        }
    }
}

internal fun LiveTvChannel.scheduleFrom(guide: Map<String, List<LiveTvProgram>>): List<LiveTvProgram> =
    variants.flatMap { guide[it.entry.id].orEmpty() }
        .distinctBy { it.start }
        .sortedBy { it.start }

private fun stationEditions(
    group: List<NamedFeed>,
    knownStation: Boolean,
    station: String,
    country: String?,
): List<List<NamedFeed>> {
    val monolingual = group.mapNotNull { liveTvLanguages(it.variant.language).singleOrNull() }
    if (monolingual.distinct().size > 1) {
        val marketLanguage = when (country) {
            "France" -> "fr"
            "Royaume-Uni", "États-Unis", "Irlande", "Australie", "Nouvelle-Zélande" -> "en"
            "Pologne" -> "pl"
            "Allemagne", "Autriche" -> "de"
            "Espagne" -> "es"
            "Italie" -> "it"
            "Portugal", "Brésil" -> "pt"
            else -> null
        }
        val primaryLanguage = marketLanguage?.takeIf { it in monolingual }
            ?: monolingual.groupingBy { it }.eachCount().maxBy { it.value }.key
        return group.groupBy { feed ->
            liveTvLanguages(feed.variant.language).singleOrNull() ?: primaryLanguage
        }.values.flatMap { edition -> stationEditions(edition, knownStation, station, country) }
    }
    val byGuideId = group.groupBy {
        val guide = it.variant.guideStationId?.lowercase()
        // The broadcaster renamed Décalé and Sport 360 to Canal+ 360; providers still use either old guide ID.
        if (country == "France" && station == "canal+360" && guide in setOf("canalplusdecale.fr", "canalplussport360.fr")) {
            "canalplus360.fr"
        } else {
            guide
        }
    }
    val scheduleCount = byGuideId.keys.count { it != null }
    val hasQualityVariants = group.any { it.qualityTag }
    val sameEdition = knownStation || hasQualityVariants && (scheduleCount == 1 || group.count { !it.qualityTag } <= 1)
    if (scheduleCount < 2 && sameEdition) return listOf(group)
    // Distinct schedules and unidentified namesakes stay separate even when their labels match.
    return byGuideId.flatMap { (id, feeds) -> if (id == null) feeds.map(::listOf) else listOf(feeds) }
}

private fun channelLogo(members: List<NamedFeed>, stationCounts: Map<String, Int>): String? {
    val candidates = members.filter { it.variant.logo != null }.groupBy { it.variant.logo!! }
    val selected = candidates.minWithOrNull(
        compareBy<Map.Entry<String, List<NamedFeed>>> { stationCounts.getValue(it.key) }
            .thenByDescending { candidate -> candidate.value.any { it.variant.country != null && it.variant.category != null } }
            .thenByDescending { it.value.size },
    ) ?: return null
    val technicalOnly = selected.value.all { qualityPrefix.containsMatchIn(it.variant.entry.title) }
    return selected.key.takeUnless { technicalOnly && stationCounts.getValue(it) > 1 }
}
