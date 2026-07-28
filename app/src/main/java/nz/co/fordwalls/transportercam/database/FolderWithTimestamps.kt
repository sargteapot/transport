package nz.co.fordwalls.transportercam.database

data class FolderWithTimestamps(
    val folder: Folder,
    val earliestMedia: Long?,
    val latestMedia: Long?
)
