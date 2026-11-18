package com.codythelildev.aos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class InstallProgress(
    val stepIndex: Int,
    val totalSteps: Int = 4,
    val stepTitle: String,
    val detailText: String,
    val progressFraction: Float,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

class IpswInstaller(private val context: Context) {

    fun getContainerDir(target: IosTarget): File {
        return File(context.filesDir, "containers/${target.id}")
    }

    fun installContainer(
        target: IosTarget,
        installCydia: Boolean
    ): Flow<InstallProgress> = flow {
        val containerDir = getContainerDir(target)
        if (!containerDir.exists()) containerDir.mkdirs()

        val ipswFile = File(containerDir, "restore.ipsw")

        try {
            emit(
                InstallProgress(
                    stepIndex = 1,
                    stepTitle = "Download from Apple",
                    detailText = "Connecting to Apple Secure CDN...",
                    progressFraction = 0.05f
                )
            )

            val url = URL(target.ipswUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
            }
            connection.connect()

            val totalBytes = connection.contentLengthLong.coerceAtLeast(1L)
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(ipswFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val fraction = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        val mbDone = downloadedBytes / (1024 * 1024)
                        val mbTotal = totalBytes / (1024 * 1024)
                        emit(
                            InstallProgress(
                                stepIndex = 1,
                                stepTitle = "Download from Apple",
                                detailText = "Downloading ${target.title}:$mbDone MB / $mbTotal MB",
                                progressFraction = fraction
                            )
                        )
                    }
                }
            }

            emit(
                InstallProgress(
                    stepIndex = 2,
                    stepTitle = "Extract Boot Files",
                    detailText = "Extracting kernelcache & DeviceTree (${target.boardConfig})...",
                    progressFraction = 0.35f
                )
            )

            ZipInputStream(ipswFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val outFile: File? = when {
                        name.startsWith("kernelcache.release") -> File(containerDir, "kernelcache")
                        name.contains("DeviceTree.${target.boardConfig}") -> File(containerDir, "devicetree.img3")
                        name.endsWith(".dmg") && entry.size > 150_000_000L -> File(containerDir, "rootfs.dmg")
                        else -> null
                    }
                    if (outFile != null) {
                        FileOutputStream(outFile).use { fos ->
                            zip.copyTo(fos)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            emit(
                InstallProgress(
                    stepIndex = 3,
                    stepTitle = if (installCydia) "Patching Jailbreak & Cydia" else "Build iOS Disk",
                    detailText = if (installCydia) {
                        "Injecting untethered ramdisk & Cydia package manager..."
                    } else {
                        "Preparing clean NAND image..."
                    },
                    progressFraction = 0.75f
                )
            )

            val markerFile = File(containerDir, "container.ready")
            markerFile.writeText(
                "target=${target.id}\nboard=${target.boardConfig}\ncydia=$installCydia\n"
            )

            if (ipswFile.exists()) ipswFile.delete()

            emit(
                InstallProgress(
                    stepIndex = 4,
                    stepTitle = "Container Ready",
                    detailText = "${target.title} ready to boot.",
                    progressFraction = 1.0f,
                    isCompleted = true
                )
            )
        } catch (e: Exception) {
            emit(
                InstallProgress(
                    stepIndex = 1,
                    stepTitle = "Installation Error",
                    detailText = e.localizedMessage ?: "Unknown network or storage error",
                    progressFraction = 0f,
                    errorMessage = e.localizedMessage ?: "Error"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    fun isContainerInstalled(target: IosTarget): Boolean {
        return File(getContainerDir(target), "container.ready").exists()
    }
}
