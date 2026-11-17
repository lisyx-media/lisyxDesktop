package com.nuvio.app.features.updater

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LisyxUpdaterIsolationTest {
    @Test fun desktopUpdaterIsDisabledAndItsSourceBelongsToLisyx() {
        assertFalse(AppUpdaterPlatform.isSupported)
        assertFalse(AppUpdaterPlatform.canInstallDownloadedUpdate())
        assertEquals("lisyx-media", AppUpdaterPlatform.releaseSource.owner)
        assertEquals("lisyxDesktop", AppUpdaterPlatform.releaseSource.repo)
    }

    @Test fun directRepositoryDownloadAndInstallEntrypointsAreBlocked() = runBlocking {
        assertTrue(AppUpdaterRepository.getLatestChannelUpdate(UpdateChannel.ALL_RELEASES).isFailure)
        assertTrue(AppUpdaterPlatform.downloadUpdateAsset(
            "https://github.com/NuvioMedia/NuvioDesktop/releases/download/v99/Nuvio.dmg", "Nuvio.dmg",
        ) { _, _ -> error("A disabled updater must never report a download") }.isFailure)
        assertTrue(AppUpdaterPlatform.installDownloadedUpdate("/tmp/cached-nuvio-update.dmg").isFailure)
    }
}
