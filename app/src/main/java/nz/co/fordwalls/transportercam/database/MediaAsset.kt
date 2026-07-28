package nz.co.fordwalls.transportercam.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class MediaAsset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val filePath: String,
    val isVideo: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null
)
