package nz.co.fordwalls.transportercam

data class CompanySummary(
    val id: String,
    val name: String,
    val shortName: String? = null,
    val accentColor: String? = null
)

data class DriverSession(
    val companyId: String,
    val companyName: String,
    val driverDocumentId: String,
    val fleetNumber: String
) {
    fun isValid(): Boolean = companyId.isNotBlank() && driverDocumentId.isNotBlank() && fleetNumber.isNotBlank()
}

object SessionPolicy {
    const val MAX_SESSION_MILLIS = 15L * 60L * 60L * 1000L

    fun remainingMillis(startedAt: Long, now: Long = System.currentTimeMillis()): Long =
        (startedAt + MAX_SESSION_MILLIS - now).coerceAtLeast(0L)

    fun isExpired(startedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        remainingMillis(startedAt, now) == 0L
}
