package nz.co.fordwalls.transportercam

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.co.fordwalls.transportercam.database.Folder
import nz.co.fordwalls.transportercam.database.MediaAsset
import nz.co.fordwalls.transportercam.ui.ChecklistItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupMetadata(
    val folders: List<Folder>,
    val mediaAssets: List<MediaAsset>
)

class ExportManager(private val context: Context) {

    fun exportVehicle(folder: Folder, assets: List<MediaAsset>): File? {
        val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        val dateStr = dateFormat.format(Date(folder.createdAt))
        val folderName = "${folder.name} $dateStr"
        
        val tempDir = File(context.cacheDir, "export_${folder.id}")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val vehicleDir = File(tempDir, folderName)
        vehicleDir.mkdirs()

        exportVehicleToDirectory(folder, assets, vehicleDir)

        val zipFile = File(context.cacheDir, "$folderName.zip")
        if (zipFile.exists()) zipFile.delete()
        
        try {
            zipFolder(vehicleDir, zipFile)
            tempDir.deleteRecursively()
            return zipFile
        } catch (e: Exception) {
            Log.e("ExportManager", "Failed to zip folder", e)
            return null
        }
    }

    fun exportFullBackup(folders: List<Folder>, allAssets: List<MediaAsset>): File? {
        val tempDir = File(context.cacheDir, "full_backup_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        // 1. Save metadata
        val metadata = BackupMetadata(folders, allAssets)
        val metadataFile = File(tempDir, "backup_metadata.json")
        metadataFile.writeText(Json.encodeToString(metadata))

        // 2. Organize vehicles
        folders.forEach { folder ->
            val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            val dateStr = dateFormat.format(Date(folder.createdAt))
            val vehicleFolderName = "${folder.name} $dateStr"
            val vehicleDir = File(tempDir, vehicleFolderName)
            vehicleDir.mkdirs()

            val assets = allAssets.filter { it.folderId == folder.id }
            exportVehicleToDirectory(folder, assets, vehicleDir)
        }

        val zipFile = File(context.cacheDir, "FW_Driver_Backup_${System.currentTimeMillis()}.zip")
        try {
            zipFolder(tempDir, zipFile)
            tempDir.deleteRecursively()
            return zipFile
        } catch (e: Exception) {
            Log.e("ExportManager", "Full backup failed", e)
            return null
        }
    }

    fun exportVehicleToDirectory(folder: Folder, assets: List<MediaAsset>, targetDir: File) {
        val pdfFile = File(targetDir, "Checklist.pdf")
        generatePdf(folder, pdfFile)

        assets.forEach { asset ->
            val srcFile = File(asset.filePath)
            if (srcFile.exists()) {
                val destFile = File(targetDir, srcFile.name)
                srcFile.copyTo(destFile, overwrite = true)
            }
        }
    }

    private fun generatePdf(folder: Folder, outputFile: File) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()

        var y = 50f
        paint.textSize = 18f
        paint.isFakeBoldText = true
        canvas.drawText("Vehicle Documentation: ${folder.name}", 50f, y, paint)
        
        y += 30f
        paint.textSize = 12f
        paint.isFakeBoldText = false
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        canvas.drawText("Created At: ${dateFormat.format(Date(folder.createdAt))}", 50f, y, paint)
        
        y += 40f
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("Checklist Details:", 50f, y, paint)
        
        y += 25f
        paint.textSize = 11f
        paint.isFakeBoldText = false

        folder.checklistJson?.let { json ->
            try {
                val items = Json.decodeFromString<List<ChecklistItem>>(json)
                items.forEach { item ->
                    if (y > 800) return@forEach 
                    val statusText = if (item.isTextFieldOnly) item.otherDetails ?: "N/A" 
                                    else "${item.status}${if (!item.otherDetails.isNullOrBlank()) ": ${item.otherDetails}" else ""}"
                    
                    canvas.drawText("${item.part}: $statusText", 60f, y, paint)
                    y += 20f
                }
            } catch (e: Exception) {
                canvas.drawText("Error parsing checklist data.", 60f, y, paint)
            }
        } ?: run {
            canvas.drawText("No checklist data available.", 60f, y, paint)
        }

        document.finishPage(page)
        document.writeTo(FileOutputStream(outputFile))
        document.close()
    }

    fun zipFolder(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zipRecursive(sourceDir, sourceDir, zos)
        }
    }

    private fun zipRecursive(root: File, source: File, zos: ZipOutputStream) {
        if (source.isDirectory) {
            source.listFiles()?.forEach { zipRecursive(root, it, zos) }
        } else {
            val entryName = if (source == root) source.name else source.absolutePath.substring(root.absolutePath.length + 1)
            zos.putNextEntry(ZipEntry(entryName))
            source.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    fun unzipAndImport(zipFile: File, onImportMetadata: (BackupMetadata, File) -> Unit, onFail: (String) -> Unit) {
        val targetDir = File(context.cacheDir, "import_temp")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            onFail("Failed to unzip: ${e.message}")
            return
        }

        val metadataFile = File(targetDir, "backup_metadata.json")
        if (metadataFile.exists()) {
            try {
                val metadata = Json.decodeFromString<BackupMetadata>(metadataFile.readText())
                onImportMetadata(metadata, targetDir)
            } catch (e: Exception) {
                Log.e("ExportManager", "Metadata import failed", e)
                onFail("Corrupt backup metadata.")
            }
        } else {
            onFail("Invalid backup: Missing metadata.")
        }
    }

    fun findFileInDir(dir: File, name: String): File? {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val found = findFileInDir(file, name)
                if (found != null) return found
            } else if (file.name == name) {
                return file
            }
        }
        return null
    }
}
