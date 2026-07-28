package nz.co.fordwalls.transportercam

sealed class Screen {
    object Login : Screen()
    object Dashboard : Screen()
    object FolderList : Screen()
    object Settings : Screen()
    data class Camera(val folderId: Long) : Screen()
    data class Gallery(val folderId: Long) : Screen()
    data class MediaDetail(val folderId: Long, val initialIndex: Int) : Screen()
    data class Checklist(val folderId: Long, val isDelivery: Boolean) : Screen()
    data class JobDetail(val jobId: String) : Screen()
    data class Signature(val jobId: String, val isDriver: Boolean) : Screen()
    data class Verification(val jobId: String) : Screen()
    data class Prestart(val fleetNumber: String) : Screen()
    object WharfScan : Screen()
    object GlobalPhotos : Screen()
}
