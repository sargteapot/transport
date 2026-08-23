package nz.co.fordwalls.transportercam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    private fun manifest(
        versionCode: Int = 7,
        minimumVersionCode: Int? = 6,
        apkUrl: String = "https://transportercam-2ec51107.web.app/downloads/fw-driver.apk",
        sha256: String = "a".repeat(64)
    ) = UpdateManifest(versionCode, "1.4.3", minimumVersionCode, apkUrl, sha256)

    @Test
    fun requiredUpdateUsesMinimumVersionCode() {
        assertTrue(isUpdateRequired(5, manifest()))
        assertFalse(isUpdateRequired(6, manifest()))
    }

    @Test
    fun validManifestPassesValidation() {
        validateUpdateManifest(6, manifest())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsApk() {
        validateUpdateManifest(6, manifest(apkUrl = "http://example.test/fw-driver.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChecksum() {
        validateUpdateManifest(6, manifest(sha256 = "not-a-sha"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOlderVersion() {
        validateUpdateManifest(6, manifest(versionCode = 6))
    }
}
