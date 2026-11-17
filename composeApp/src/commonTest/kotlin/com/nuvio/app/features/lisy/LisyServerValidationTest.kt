package com.nuvio.app.features.lisy
import kotlin.test.*
class LisyServerValidationTest {
    @Test fun acceptsHttpAndHttpsPortalAddresses() {
        assertNull(validateLisyServer("Salon", "https://example.org:8443/c/", "00:1A:79:00:00:00"))
        assertNull(validateLisyServer("Salon", "http://192.168.1.10/portal.php", "00-1a-79-00-00-00"))
    }
    @Test fun rejectsIncompleteOrUnsafeInput() {
        assertEquals(LisyServerValidation.Name, validateLisyServer(" ", "https://example.org", "00:1A:79:00:00:00"))
        for (url in listOf("example.org", "ftp://example.org", "https://", "https://exa mple.org"))
            assertEquals(LisyServerValidation.Url, validateLisyServer("Test", url, "00:1A:79:00:00:00"))
        for (mac in listOf("", "00:1A:79:00:00", "XX:1A:79:00:00:00"))
            assertEquals(LisyServerValidation.Mac, validateLisyServer("Test", "https://example.org", mac))
    }
}
