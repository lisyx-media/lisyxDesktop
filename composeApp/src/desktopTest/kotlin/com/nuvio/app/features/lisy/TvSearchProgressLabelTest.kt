package com.nuvio.app.features.lisy

import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.tv_search_building_history
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSearchProgressLabelTest {
    @Test
    fun rendersOnePercentSignAfterTheProgressValue() = runTest {
        for (value in listOf(0, 8, 10, 100)) {
            val label = getString(Res.string.tv_search_building_history, value)
            assertEquals("$value%", label.substringAfterLast('·').filterNot(Char::isWhitespace))
        }
    }
}
