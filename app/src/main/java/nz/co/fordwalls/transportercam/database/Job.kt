package nz.co.fordwalls.transportercam.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus {
    NEW, ACCEPTED, PICKED_UP, DONE, ONSCAN
}

@Serializable
@Entity(tableName = "jobs")
data class Job(
    @PrimaryKey val id: String = "",
    val fleetNumber: String = "",
    val rego: String = "",
    val loadInfo: String = "",
    val pickupAddress: String = "",
    val deliveryAddress: String = "",
    val pickupContact: String = "",
    val deliveryContact: String = "",
    val notes: String? = null,
    val status: JobStatus = JobStatus.NEW,
    val driverName: String? = null,
    val driverSignatureUrl: String? = null,
    val customerName: String? = null,
    val customerSignatureUrl: String? = null,
    val pickupChecklistJson: String? = null,
    val dropoffChecklistJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dispatchedBy: String? = null,
    val photoUrls: String? = null
)
