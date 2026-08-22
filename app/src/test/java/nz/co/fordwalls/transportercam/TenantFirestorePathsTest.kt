package nz.co.fordwalls.transportercam

import org.junit.Assert.assertEquals
import org.junit.Test

class TenantFirestorePathsTest {
    @Test fun fordWallsUsesLegacyRootCollections() {
        assertEquals("jobs", TenantFirestorePaths.collectionPath("fordwalls", "jobs"))
        assertEquals("fleets", TenantFirestorePaths.collectionPath("fordwalls", "fleets"))
        assertEquals("drivers", TenantFirestorePaths.collectionPath("fordwalls", "drivers"))
    }

    @Test fun otherCompaniesUseNestedCollections() {
        assertEquals("companies/acme/jobs", TenantFirestorePaths.collectionPath("acme", "jobs"))
        assertEquals("companies/acme/fleets", TenantFirestorePaths.collectionPath("acme", "fleets"))
        assertEquals("companies/acme/drivers", TenantFirestorePaths.collectionPath("acme", "drivers"))
    }

    @Test fun loginRequiresCompleteSession() {
        assertEquals(true, DriverSession("acme", "Acme", "driver-1", "241").isValid())
        assertEquals(false, DriverSession("acme", "Acme", "", "241").isValid())
    }

    @Test fun sessionExpiresAtFifteenHours() {
        val start = 1_000L
        assertEquals(false, SessionPolicy.isExpired(start, start + SessionPolicy.MAX_SESSION_MILLIS - 1))
        assertEquals(true, SessionPolicy.isExpired(start, start + SessionPolicy.MAX_SESSION_MILLIS))
        assertEquals(1L, SessionPolicy.remainingMillis(start, start + SessionPolicy.MAX_SESSION_MILLIS - 1))
    }
}
