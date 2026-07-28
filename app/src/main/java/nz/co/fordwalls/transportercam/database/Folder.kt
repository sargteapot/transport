package nz.co.fordwalls.transportercam.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val checklistJson: String? = null,
    val jobId: String? = null
)
