package com.nuvio.app.features.lisy
import kotlin.test.*
class LisyServerStorageTest {
    @Test fun storesProfilesSeparatelyAndRemovesOneWithoutChangingAnother() {
        val a = 987651
        val b = 987652
        val oldA = LisyServerStorage.load(a)
        val oldB = LisyServerStorage.load(b)
        try {
            LisyServerStorage.save(a, "profile-a")
            LisyServerStorage.save(b, "profile-b")
            assertEquals("profile-a", LisyServerStorage.load(a))
            assertEquals("profile-b", LisyServerStorage.load(b))
            LisyServerStorage.save(a, null)
            assertNull(LisyServerStorage.load(a))
            assertEquals("profile-b", LisyServerStorage.load(b))
        } finally {
            LisyServerStorage.save(a, oldA)
            LisyServerStorage.save(b, oldB)
        }
    }
}
