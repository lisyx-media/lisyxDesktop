package com.nuvio.app.features.lisy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TvParentalControlsTest {
    private val server = LisyServer("Fixture", "https://portal.example/c", "00:11:22:33:44:55")

    @Test
    fun missingCodeIsLockedAndSetupValidatesDigitsAndConfirmation() = runBlocking {
        var stored: String? = null
        val gate = TvParentalGate({ stored }, { stored = it })
        assertFalse(gate.state.value.configured)
        assertFalse(gate.isUnlocked(1, server))
        assertEquals(TvParentalError.NotConfigured, gate.unlock("2468", 1, server).error)
        assertEquals(TvParentalError.InvalidPin, gate.setPin("24", "24").error)
        assertEquals(TvParentalError.InvalidPin, gate.setPin("abcd", "abcd").error)
        assertEquals(TvParentalError.Confirmation, gate.setPin("2468", "1357").error)
        assertTrue(gate.setPin("2468", "2468").success)
        assertTrue(gate.state.value.configured)
        assertFalse(gate.isUnlocked(1, server))
        assertNotNull(stored)
        assertFalse(Json.parseToJsonElement(stored!!).jsonObject.values.any { it.jsonPrimitive.content == "2468" })
        assertTrue(gate.unlock("2468", 1, server).success)
        gate.lock()
    }

    @Test
    fun changingConfiguredCodeRequiresTheCurrentCodeAndUsesFreshSalt() = runBlocking {
        var stored: String? = null
        val gate = TvParentalGate({ stored }, { stored = it })
        assertTrue(gate.setPin("2468", "2468").success)
        val salt = Json.parseToJsonElement(stored!!).jsonObject.getValue("salt")
        assertEquals(TvParentalError.Incorrect, gate.setPin("1357", "1357").error)
        assertEquals(TvParentalError.Incorrect, gate.setPin("1357", "1357", "0000").error)
        assertTrue(gate.setPin("1357", "1357", "2468").success)
        assertNotEquals(salt, Json.parseToJsonElement(stored!!).jsonObject.getValue("salt"))
        assertFalse(gate.unlock("2468", 1, server).success)
        assertTrue(gate.unlock("1357", 1, server).success)
        gate.lock()
    }

    @Test
    fun fiveFailuresPersistACooldownAcrossRestartsAndAlsoProtectCodeChanges() = runBlocking {
        var stored: String? = null
        var clock = 1_000L
        val gate = TvParentalGate({ stored }, { stored = it }, { clock })
        assertTrue(gate.setPin("2468", "2468").success)
        repeat(4) { assertEquals(TvParentalError.Incorrect, gate.unlock("0000", 1, server).error) }
        val blocked = gate.unlock("0000", 1, server)
        assertEquals(TvParentalError.Blocked, blocked.error)
        assertEquals(60, blocked.retryAfterSeconds)
        val restarted = TvParentalGate({ stored }, { stored = it }, { clock })
        assertEquals(TvParentalError.Blocked, restarted.unlock("2468", 1, server).error)
        assertEquals(TvParentalError.Blocked, restarted.setPin("1357", "1357", "2468").error)
        clock += 61
        assertTrue(restarted.unlock("2468", 1, server).success)
        assertFalse(TvParentalGate({ stored }, { stored = it }, { clock }).state.value.unlocked)
        restarted.lock()
    }

    @Test
    fun unlockIsBoundToProfileAndServerAndExpires() = runBlocking {
        var stored: String? = null
        var clock = 1_000L
        val gate = TvParentalGate({ stored }, { stored = it }, { clock })
        assertTrue(gate.setPin("2468", "2468").success)
        assertTrue(gate.unlock("2468", 1, server).success)
        assertTrue(gate.isUnlocked(1, server.copy(name = "Renamed")))
        assertFalse(gate.isUnlocked(2, server))
        assertFalse(gate.isUnlocked(1, server))
        assertTrue(gate.unlock("2468", 1, server).success)
        assertFalse(gate.isUnlocked(1, server.copy(url = "https://different.example/c")))
        assertTrue(gate.unlock("2468", 1, server).success)
        clock += 15 * 60
        assertFalse(gate.isUnlocked(1, server))
    }

    @Test
    fun corruptOrUnavailableStorageFailsClosedWithoutOverwritingTheCredential() = runBlocking {
        var stored = "corrupt"
        val gate = TvParentalGate({ stored }, { stored = it })
        assertTrue(gate.state.value.configured)
        assertEquals(TvParentalError.Storage, gate.unlock("2468", 1, server).error)
        assertEquals(TvParentalError.Storage, gate.setPin("2468", "2468").error)
        assertEquals("corrupt", stored)
        val unavailable = TvParentalGate({ error("unavailable") }, { error("unavailable") })
        assertTrue(unavailable.state.value.configured)
        assertEquals(TvParentalError.Storage, unavailable.unlock("2468", 1, server).error)
        assertFalse(unavailable.isUnlocked(1, server))
    }
}
