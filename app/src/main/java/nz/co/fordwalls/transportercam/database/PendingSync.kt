package nz.co.fordwalls.transportercam.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_sync")
data class PendingSync(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: String,
    val type: String,
    val targetId: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null
)
