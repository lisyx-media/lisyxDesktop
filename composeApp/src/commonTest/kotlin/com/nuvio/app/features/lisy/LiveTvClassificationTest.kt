package com.nuvio.app.features.lisy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvClassificationTest {
    private fun feed(id: String, name: String, group: String) = LiveTvVariant(
        entry = LisyEntry(id, name, null, emptyList()),
        logo = null,
        category = liveTvCategory(group),
        country = liveTvCountry(null, name, group),
        rawCategory = group,
    )

    @Test
    fun groupsDecoratedQualityVariantsAcrossBouquetsButKeepsTimeShiftAndThemedFeeds() {
        val channels = groupLiveTvChannels(listOf(
            feed("1", "|4K| TF1 HDR/UHD/4K", "4K UHD"),
            feed("2", "FR - TF1 FHD ◉", "|EU| FRANCE GÉNÉRAL"),
            feed("3", "FR - TF1 HEV", "|EU| FRANCE HEVC"),
            feed("4", "FR - TF1 HD", "|EU| FRANCE CARRIBEAN"),
            feed("5", "FR - TF1 +1 FHD", "|EU| FRANCE GÉNÉRAL"),
            feed("6", "FR - TF1+ STAR ACADEMY HEVC", "|EU| FRANCE HEVC"),
            feed("7", "##### GENERAL #####", "|EU| FRANCE HEVC"),
        ))
        assertEquals(listOf("TF1", "TF1 +1", "TF1+ STAR ACADEMY"), channels.map { it.name })
        assertEquals(listOf("1", "2", "4", "3"), channels.first().variants.map { it.entry.id })
        assertEquals("France", channels.first().country)
        assertEquals(setOf("Généralistes"), channels.first().categories)
        assertEquals(setOf("Divertissement"), channels.last().categories)
    }

    @Test
    fun ambiguousCountryAndRegionalFeedsStaySeparate() {
        val channels = groupLiveTvChannels(listOf(
            feed("1", "FR - Test HD", "Sports"), feed("2", "BE - Test SD", "Sports"),
            feed("3", "|4K| Test 4K", "4K UHD"), feed("4", "FR - Test Lyon HD", "Sports"),
        ))
        assertEquals(4, channels.size)
        assertEquals(setOf("France", "Belgique", null), channels.map { it.country }.toSet())
    }

    @Test
    fun frenchStationsUseTheirOwnThemeAcrossMixedBouquetsAndSpellings() {
        val samples = listOf(
            "RTL9" to setOf("Cinéma"), "CANAL+BOX OFFICE" to setOf("Cinéma"),
            "CANAL+ GRAND ÉCRAN" to setOf("Cinéma"), "CSTAR HITS" to setOf("Musique"),
            "BFM BUSINESS" to setOf("Actualités"), "LCP" to setOf("Actualités"),
            "L’ÉQUIPE 21" to setOf("Sport"), "CANAL+LIVE 12" to setOf("Sport"),
            "EUROSPORT 2" to setOf("Sport"), "PLANETE+CRIME" to setOf("Découverte"),
            "PIWI+" to setOf("Jeunesse"), "CANAL+DOCS" to setOf("Découverte"),
            "13ÈME RUE" to setOf("Séries"), "TV BREIZH" to setOf("Séries"),
            "PARIS PREMIÈRE" to setOf("Divertissement"), "TF1 SÉRIES FILMS" to setOf("Cinéma", "Séries"),
        )
        val channels = groupLiveTvChannels(samples.mapIndexed { i, (name, _) ->
            feed(i.toString(), "FR - $name HEVC", "FR | Généralistes")
        })
        assertEquals(samples.toMap(), channels.associate { it.name to it.categories })

        val variants = listOf(
            feed("1", "FR - CSTAR HEVC", "4K UHD"), feed("2", "FR - CSTAR", "Musique"),
            feed("3", "FR - CSTAR", "France"), feed("4", "FR - CSTAR HITS", "France"),
            feed("5", "FR - CANAL+ BOX OFFICE FHD •", "France"), feed("6", "FR - CANAL+BOX OFFICE", "France"),
            feed("7", "FR - Autre Club HEVC", "France"), feed("8", "US - Action HD", "USA"),
        )
        val grouped = groupLiveTvChannels(variants)
        assertEquals(listOf(3, 1, 2, 1, 1), grouped.map { it.variants.size })
        assertEquals(setOf("Musique"), grouped.first().categories)
        assertEquals(emptySet(), grouped[3].categories)
        assertEquals(emptySet(), grouped[4].categories)
    }

    @Test
    fun stationLogoIsIndependentOfTheBestStreamAndSharedBouquetArtwork() {
        val channels = groupLiveTvChannels(listOf(
            feed("1", "|4K| TF1 HDR/UHD/4K", "4K UHD").copy(logo = "generic"),
            feed("2", "|4K| M6 UHD/4K", "4K UHD").copy(logo = "generic"),
            feed("3", "|4K| UHD Demo 4K", "4K UHD").copy(logo = "generic"),
            feed("4", "FR - TF1 HEVC", "FR HEVC").copy(logo = "codec"),
            feed("5", "FR - TF1 FHD", "France Généralistes").copy(logo = "tf1"),
            feed("6", "FR - TF1 HD", "France Généralistes").copy(logo = "tf1"),
            feed("7", "FR - M6", "France Généralistes").copy(logo = "m6"),
        ))
        assertEquals(listOf("1", "5", "6", "4"), channels.first().variants.map { it.entry.id })
        assertEquals(mapOf("TF1" to "tf1", "M6" to "m6", "UHD Demo" to null), channels.associate { it.name to it.logo })
    }

    @Test
    fun sameGuideStationGroupsAllQualitiesButConflictingOrRegionalEditionsStaySeparate() {
        val variants = listOf(
            feed("1", "FR - LCI HEVC", "France HEVC").copy(guideStationId = "LCI.fr"),
            feed("2", "FR - LCI FHD", "France Info").copy(guideStationId = "LCI.fr"),
            feed("3", "FR - LCI", "France Info").copy(guideStationId = "LCI.fr"),
            feed("4", "FR - LCI •", "France Info").copy(guideStationId = "LCI.fr"),
            feed("5", "FR - LCI", "France"),
            feed("6", "FR - Station HD", "France").copy(guideStationId = "Nord.fr"),
            feed("7", "FR - Station HEVC", "France").copy(guideStationId = "Nord.fr"),
            feed("8", "FR - Station UHD", "France").copy(guideStationId = "Sud.fr"),
            feed("9", "FR - Station", "France"),
            feed("10", "FR - Station +1", "France").copy(guideStationId = "Nord.fr"),
            feed("11", "FR - Station Lyon", "France").copy(guideStationId = "Nord.fr"),
        )
        val channels = groupLiveTvChannels(variants)
        assertEquals(listOf(5, 2, 1, 1, 1, 1), channels.map { it.variants.size })
        assertEquals(setOf("Actualités"), channels.first().categories)
        assertEquals(variants.map { it.entry.id }.toSet(), channels.flatMap { it.variants }.map { it.entry.id }.toSet())
    }

    @Test
    fun regionalStationsKeepTheirLocationAndSpecialistNetworksKeepTheirTopic() {
        val channels = groupLiveTvChannels(listOf(
            feed("1", "USA - FOX 2 DETROIT MI (WJBK)", "USA FOX"),
            feed("2", "USA - FOX 2 HONOLULU HI (KHON)", "USA FOX"),
            feed("3", "USA - FOX NEWS", "USA FOX"),
            feed("4", "FR - MARTINIQUE 1ERE", "France"),
            feed("5", "FR - GUADELOUPE 1ERE", "France"),
            feed("6", "CA - TSN2", "Canada"),
            feed("7", "FR - RMC STORY HEVC", "France Documentaire"),
        ))
        assertEquals(listOf("Régionales", "Régionales", "Actualités", "Régionales", "Régionales", "Sport", "Généralistes"),
            channels.map { it.categories.single() })
        assertEquals("Événements", liveTvCategory("|UK| DISCOVERY+ PPV"))
    }

    @Test
    fun qualityAndVendorNamesAreNotEditorialCategories() {
        assertNull(liveTvCategory("|UK| 24/7 FLEX"))
        assertNull(liveTvCategory("4K UHD"))
        assertEquals("Jeunesse", liveTvCategory("|UK| 24/7 KIDS"))
        assertEquals("Sport", liveTvCategory("|NA| USA DAZN PPV"))
        assertEquals("Cinéma", liveTvCategory("UK - WILL FERRELL MOVIES"))
        assertEquals("États-Unis", liveTvCountry(null, "ESPN 1 FHD", "|NA| USA ESPN PLAY"))
    }

    @Test
    fun countryNamesAndLanguagePrefixesAreDistinctAndFlagsUseCanonicalCountries() {
        assertEquals("Pologne", liveTvCountry("PL", "CANAL+ SPORT UHD", null))
        assertEquals("Allemagne", liveTvCountry("Germany", "RTL", null))
        assertEquals("Norvège", liveTvCountry(null, "NO - TV2 HD", "4K UHD"))
        assertEquals("Pologne", liveTvCountry(null, "CANAL+ SPORT", "EU | Polska Sports"))
        assertNull(liveTvCountry("English", "EN - NETFLIX 4K", "English Movies"))
        assertEquals("en", liveTvLanguage(null, "ENG - NETFLIX 4K", null))
        assertEquals("pl", liveTvLanguage("pol", "FR - CANAL+ SPORT UHD", "France Sport"))
        assertNull(liveTvLanguage(null, "FR - CANAL+ SPORT UHD", "France Sport"))
        assertEquals("🇫🇷", liveTvCountryFlag("FR"))
        assertEquals("🇬🇧", liveTvCountryFlag("Royaume-Uni"))
        assertEquals("🇺🇸", liveTvCountryFlag("États-Unis"))
        assertEquals("🇵🇱", liveTvCountryFlag("Poland"))
        assertNull(liveTvCountryFlag("English"))
        assertNull(liveTvCountryFlag(null))
    }

    @Test
    fun canal360AliasesMergeOldGuideNamesButPreserveEverySourceAndForeignEdition() {
        val variants = listOf(
            feed("1", "FR - CANAL+ SPORT 360 UHD", "France Sport"),
            feed("2", "FR - CANAL+ SPORT 360 HEVC", "France HEVC").copy(guideStationId = "CanalPlusSport360.fr"),
            feed("3", "FR - CANAL+ 360 HD", "France Sport").copy(guideStationId = "CanalPlusSport360.fr"),
            feed("4", "FR - CANAL+ 360 4K", "France 4K").copy(guideStationId = "CanalPlusDecale.fr"),
            feed("5", "FR - CANAL+SPORT360 HD", "France"),
            feed("6", "PL - CANAL+ SPORT 360 UHD", "Poland Sport"),
        )
        val channels = groupLiveTvChannels(variants)
        assertEquals(2, channels.size)
        assertEquals("CANAL+ 360", channels.first().name)
        assertEquals("France", channels.first().country)
        assertEquals(setOf("Sport"), channels.first().categories)
        assertEquals(listOf("1", "4", "3", "5", "2"), channels.first().variants.map { it.entry.id })
        assertEquals("Pologne", channels.last().country)
        assertEquals(variants.map { it.entry.id }.toSet(), channels.flatMap { it.variants }.map { it.entry.id }.toSet())
        assertEquals(setOf("CanalPlusSport360.fr", "CanalPlusDecale.fr"), channels.first().variants.mapNotNull { it.guideStationId }.toSet())
    }

    @Test
    fun themedMovieChannelsAreEditoriallyClassifiedWithoutTurningAllVendorFeedsIntoCinema() {
        val cinema = listOf("ACTION", "HORROR", "THRILLER", "ROMANCE", "SCI-FI", "FANTASY", "DRAMA", "ADVENTURE", "COMEDY")
        val channels = groupLiveTvChannels(cinema.mapIndexed { index, genre ->
            feed(index.toString(), "FR - NETFLIX $genre", "France")
        } + listOf(
            feed("sport", "FR - NETFLIX SPORTS", "France"),
            feed("kids", "FR - NETFLIX KIDS", "France"),
            feed("doc", "FR - NETFLIX DOCUMENTARY", "France"),
            feed("ppv", "UK - NETFLIX PPV 1", "UK"),
        ))
        assertTrue(channels.take(cinema.size).all { it.categories == setOf("Cinéma") })
        assertEquals(listOf("Sport", "Jeunesse", "Découverte", "Événements"),
            channels.drop(cinema.size).map { it.categories.single() })
        assertNull(liveTvCategory("Netflix"))
        assertNull(liveTvCategory("24/7 FLEX"))
    }

    @Test
    fun guideMarketOnlyFillsMissingCountryAndEnglishPrefixDoesNotBecomeACountry() {
        val channels = groupLiveTvChannels(listOf(
            feed("1", "EN - Test HD", "English").copy(guideStationId = "Test.gb"),
            feed("2", "FR - Test HD", "France").copy(guideStationId = "Test.gb"),
            feed("3", "ENG - NETFLIX 4K", "English"),
        ))
        assertEquals(listOf("Royaume-Uni", "France", null), channels.map { it.country })
        assertEquals(listOf("Test", "Test", "NETFLIX"), channels.map { it.name })
        assertEquals("en", channels.first().variants.single().language)
    }

    @Test
    fun explicitDivergentAudioLanguagesSeparateEditionsButMissingAndBilingualTagsDoNotFragmentThem() {
        val variants = listOf(
            feed("fr", "FR - CANAL+ SPORT FHD", "France Sport").copy(language = "fra", guideStationId = "CanalPlusSport.fr"),
            feed("pl", "FR - CANAL+ SPORT UHD", "France Sport").copy(language = "pol", guideStationId = "CanalPlusSport.fr"),
            feed("unknown", "FR - CANAL+ SPORT HEVC", "France Sport").copy(guideStationId = "CanalPlusSport.fr"),
            feed("bilingual", "FR - CANAL+ SPORT HD", "France Sport").copy(language = "fra,pol", guideStationId = "CanalPlusSport.fr"),
        )
        val channels = groupLiveTvChannels(variants)
        assertEquals(listOf("CANAL+ SPORT · FR", "CANAL+ SPORT · PL"), channels.map { it.name })
        assertEquals(setOf("fr", "unknown", "bilingual"), channels.first().variants.map { it.entry.id }.toSet())
        assertEquals("pl", channels.last().variants.single().entry.id)
        assertTrue(channels.all { it.country == "France" })
        assertEquals(setOf("fr", "pl"), liveTvLanguages(channels.first().variants.single { it.entry.id == "bilingual" }.language))
        assertEquals(1, groupLiveTvChannels(variants.filter { it.entry.id != "pl" }).size)
    }

    @Test
    fun specialistNamesKeepTheirEditorialThemeAcrossCountriesAndTechnicalBouquets() {
        val samples = mapOf(
            "NESN UHD/4K+" to "Sport", "ASTRO SUPERSPORT" to "Sport", "5SPORT" to "Sport", "ESPN2" to "Sport",
            "SKY STORE PREMIERE 1" to "Cinéma", "SKY PRIMAFILA 2" to "Cinéma", "HORROR CHANNEL" to "Cinéma",
            "TRACE URBAN" to "Musique", "TRACE LATINA" to "Musique", "STINGRAY CLASSICA" to "Musique",
            "CNBC" to "Actualités", "MSNBC" to "Actualités", "FOX BUSINESS" to "Actualités",
            "ANIMAL PLANET" to "Découverte", "CRIME INVESTIGATION" to "Découverte", "PLANETE+" to "Découverte",
            "TELETOON" to "Jeunesse", "TELEMAGINO" to "Jeunesse", "FOOD NETWORK" to "Divertissement",
            "MARTINIQUE 1ERE" to "Régionales", "TV5 MONDE" to "Généralistes",
        )
        samples.forEach { (name, category) ->
            assertEquals(category, liveTvCategory(name), name)
        }
        assertNull(liveTvCategory("Digi"))
        assertNull(liveTvCategory("RTL"))
        assertNull(liveTvCategory("24/7 English"))
    }

    @Test
    fun verifiedStationSpellingAliasesMergeWithoutMixingTimeshiftMarketsOrRegionalEditions() {
        val variants = listOf(
            feed("1", "FR - TF1 FHD", "France").copy(guideStationId = "TF1.fr"),
            feed("2", "FR - TF 1 UHD", "France").copy(guideStationId = "TF1.fr"),
            feed("3", "FR - TF 1", "France").copy(guideStationId = "TF1.fr"),
            feed("4", "FR - TF1 FILMS & SÉRIES HEVC", "France").copy(guideStationId = "TF1SeriesFilms.fr"),
            feed("5", "FR - TF1 SERIES UHD", "France").copy(guideStationId = "TF1SeriesFilms.fr"),
            feed("6", "FR - TF1 SERIES FILMS", "France"),
            feed("7", "FR - TF1 +1 FHD", "France"),
            feed("8", "FR - TF1 Réunion HD", "France").copy(guideStationId = "TF1Reunion.fr"),
            feed("9", "BE - TF1 FHD", "Belgique").copy(guideStationId = "TF1.be"),
        )
        val channels = groupLiveTvChannels(variants)
        assertEquals(listOf("TF1", "TF1", "TF1 SÉRIES FILMS", "TF1 +1", "TF1 Réunion"), channels.map { it.name })
        assertEquals(listOf(3, 1, 3, 1, 1), channels.map { it.variants.size })
        assertEquals(setOf("Cinéma", "Séries"), channels[2].categories)
        assertEquals(variants.map { it.entry.id }.toSet(), channels.flatMap { it.variants }.map { it.entry.id }.toSet())
        for ((first, second) in listOf("6 TER" to "6TER", "RTL 9" to "RTL9", "C NEWS" to "CNEWS", "FRANCE24" to "FRANCE 24")) {
            assertEquals(1, groupLiveTvChannels(listOf(
                feed("a", "FR - $first HD", "France"), feed("b", "FR - $second FHD", "France"),
            )).size, first)
        }
    }
}
