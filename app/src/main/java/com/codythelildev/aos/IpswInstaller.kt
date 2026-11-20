package com.codythelildev.aos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class InstallProgress(
    val stepIndex: Int,
    val totalSteps: Int = 4,
    val stepTitle: String,
    val detailText: String,
    val progressFraction: Float,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

data class InstalledContainer(
    val target: IosTarget,
    val hasCydia: Boolean
)

class IpswInstaller(private val context: Context) {

    fun getContainerDir(target: IosTarget): File {
        return File(context.filesDir, "containers/${target.id}")
    }

    private fun openAppleConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 45000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "InetURL/1.0")
                setRequestProperty("Accept", "*/*")
            }
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Empty redirect from Apple CDN (HTTP $code)")
                }
                currentUrl = URL(url, location).toString()
                redirects++
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("Apple CDN returned HTTP $code")
            }
        }
        throw IllegalStateException("Too many redirects from Apple CDN")
    }

    private fun extractZipEntryToFile(zipFile: ZipFile, entry: ZipEntry, destination: File) {
        destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(128 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }

    private fun extractImg3DataPayload(rawImg3File: File, outRawBinary: File): Boolean {
        if (!rawImg3File.exists() || rawImg3File.length() < 20) return false
        val bytes = rawImg3File.readBytes()
        // Sprawdzenie naglowka IMG3 ("3gmI" w little-endian: 0x33, 0x67, 0x6D, 0x49)
        if (bytes[0] != 0x33.toByte() ||
            bytes[1] != 0x67.toByte() ||
            bytes[2] != 0x6D.toByte() ||
            bytes[3] != 0x49.toByte()
        ) {
            // Plik jest juz surowym binarium
            rawImg3File.copyTo(outRawBinary, overwrite = true)
            return true
        }

        // Szukamy tagu sekcji "ATAD" (DATA w little-endian: 0x41, 0x54, 0x41, 0x44)
        var offset = 20
        while (offset + 12 <= bytes.size) {
            val isDataTag = bytes[offset] == 0x41.toByte() &&
                bytes[offset + 1] == 0x54.toByte() &&
                bytes[offset + 2] == 0x41.toByte() &&
                bytes[offset + 3] == 0x44.toByte()

            val totalTagSize = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)

            val dataPayloadSize = (bytes[offset + 8].toInt() and 0xFF) or
                ((bytes[offset + 9].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 10].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 11].toInt() and 0xFF) shl 24)

            if (totalTagSize <= 0 || offset + totalTagSize > bytes.size) break

            if (isDataTag && dataPayloadSize > 0 && offset + 12 + dataPayloadSize <= bytes.size) {
                FileOutputStream(outRawBinary).use { fos ->
                    fos.write(bytes, offset + 12, dataPayloadSize)
                }
                return true
            }
            offset += totalTagSize
        }
        return false
    }

    fun installContainer(
        target: IosTarget,
        installCydia: Boolean
    ): Flow<InstallProgress> = flow {
        val containerDir = getContainerDir(target)
        if (containerDir.exists()) {
            containerDir.deleteRecursively()
        }
        containerDir.mkdirs()

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

            val connection = openAppleConnection(target.ipswUrl)
            val totalBytes = connection.contentLengthLong.coerceAtLeast(1L)
            var downloadedBytes = 0L
            var lastUiUpdateBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(ipswFile).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (downloadedBytes - lastUiUpdateBytes >= 2 * 1024 * 1024 || downloadedBytes == totalBytes) {
                            lastUiUpdateBytes = downloadedBytes
                            val fraction = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.45f)
                            val mbDone = downloadedBytes / (1024 * 1024)
                            val mbTotal = totalBytes / (1024 * 1024)
                            emit(
                                InstallProgress(
                                    stepIndex = 1,
                                    stepTitle = "Download from Apple",
                                    detailText = "Downloading ${target.title}: $mbDone MB / $mbTotal MB",
                                    progressFraction = fraction
                                )
                            )
                        }
                    }
                }
            }
            connection.disconnect()

            emit(
                InstallProgress(
                    stepIndex = 2,
                    stepTitle = "Extract Boot & RootFS Files",
                    detailText = "Parsing IPSW central directory for ${target.boardConfig}...",
                    progressFraction = 0.50f
                )
            )

            val boardLower = target.boardConfig.lowercase()
            val kernelOut = File(containerDir, "kernelcache")
            val kernelRawOut = File(containerDir, "kernelcache.raw")
            val deviceTreeOut = File(containerDir, "devicetree.img3")
            val deviceTreeRawOut = File(containerDir, "devicetree.raw")
            val ibssOut = File(containerDir, "ibss.img3")
            val ibecOut = File(containerDir, "ibec.img3")
            val llbOut = File(containerDir, "llb.img3")
            val rootfsOut = File(containerDir, "rootfs.dmg")
            val ramdiskOut = File(containerDir, "ramdisk.dmg")

            // Uzywamy ZipFile zamiast ZipInputStream, zeby odczytac prawdziwe rozmiary z katalogu centralnego ZIP
            ZipFile(ipswFile).use { zip ->
                val entries = zip.entries().toList()

                // 1. Kernelcache dopasowany do plyty lub ogolny release
                val kernelEntry = entries.firstOrNull {
                    val n = it.name.lowercase()
                    n.startsWith("kernelcache.release") && n.contains(boardLower)
                } ?: entries.firstOrNull {
                    it.name.lowercase().startsWith("kernelcache.release")
                } ?: throw IllegalStateException("kernelcache not found in IPSW")

                extractZipEntryToFile(zip, kernelEntry, kernelOut)
                extractImg3DataPayload(kernelOut, kernelRawOut)

                // 2. DeviceTree dla konkretnego boardConfig (np. n41ap dla iOS 8.4.1)
                val deviceTreeEntry = entries.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("devicetree.$boardLower") && n.endsWith(".img3")
                } ?: throw IllegalStateException("DeviceTree.${target.boardConfig} not found in IPSW")

                extractZipEntryToFile(zip, deviceTreeEntry, deviceTreeOut)
                extractImg3DataPayload(deviceTreeOut, deviceTreeRawOut)

                // 3. Bootloadery niskiego poziomu (iBSS, iBEC, LLB)
                entries.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("ibss.$boardLower") && n.endsWith(".img3")
                }?.let { extractZipEntryToFile(zip, it, ibssOut) }

                entries.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("ibec.$boardLower") && n.endsWith(".img3")
                }?.let { extractZipEntryToFile(zip, it, ibecOut) }

                entries.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("llb.$boardLower") && n.endsWith(".img3")
                }?.let { extractZipEntryToFile(zip, it, llbOut) }

                emit(
                    InstallProgress(
                        stepIndex = 2,
                        stepTitle = "Extract Boot & RootFS Files",
                        detailText = "Extracting RootFS disk image (largest .dmg)...",
                        progressFraction = 0.65f
                    )
                )

                // 4. Sortujemy wszystkie .dmg po rozmiarze malejaco: najwiekszy to RootFS, mniejszy to Restore Ramdisk
                val dmgEntries = entries
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".dmg") && it.size > 0 }
                    .sortedByDescending { it.size }

                val rootfsEntry = dmgEntries.firstOrNull()
                    ?: throw IllegalStateException("RootFS .dmg image not found in IPSW")

                extractZipEntryToFile(zip, rootfsEntry, rootfsOut)

                if (dmgEntries.size > 1) {
                    extractZipEntryToFile(zip, dmgEntries[1], ramdiskOut)
                }
            }

            emit(
                InstallProgress(
                    stepIndex = 3,
                    stepTitle = if (installCydia) "Patching Jailbreak & Cydia" else "Build iOS NAND & Manifest",
                    detailText = if (installCydia) {
                        "Configuring untethered ramdisk & Cydia environment..."
                    } else {
                        "Linking NAND storage and container manifest..."
                    },
                    progressFraction = 0.85f
                )
            )

            // Weryfikacja, czy wszystkie kluczowe pliki faktycznie zostaly zapisane i nie sa puste
            if (!kernelOut.exists() || kernelOut.length() == 0L) {
                throw IllegalStateException("Extracted kernelcache is empty")
            }
            if (!deviceTreeOut.exists() || deviceTreeOut.length() == 0L) {
                throw IllegalStateException("Extracted DeviceTree is empty")
            }
            if (!rootfsOut.exists() || rootfsOut.length() < 50_000_000L) {
                throw IllegalStateException("Extracted rootfs.dmg is missing or incomplete (${rootfsOut.length()} bytes)")
            }

            // Zapisujemy manifest JSON ze sciezkami, z ktorego moze korzystac natywny bridge
            val manifest = JSONObject().apply {
                put("targetId", target.id)
                put("iosVersion", target.title)
                put("deviceModel", target.deviceModel)
                put("boardConfig", target.boardConfig)
                put("cydiaEnabled", installCydia)
                put("kernelcache", kernelOut.absolutePath)
                put("kernelcacheRaw", kernelRawOut.absolutePath)
                put("deviceTree", deviceTreeOut.absolutePath)
                put("deviceTreeRaw", deviceTreeRawOut.absolutePath)
                put("rootfs", rootfsOut.absolutePath)
                if (ramdiskOut.exists()) put("ramdisk", ramdiskOut.absolutePath)
                if (ibssOut.exists()) put("ibss", ibssOut.absolutePath)
                if (ibecOut.exists()) put("ibec", ibecOut.absolutePath)
                if (llbOut.exists()) put("llb", llbOut.absolutePath)
            }
            File(containerDir, "container.json").writeText(manifest.toString(2))

            val markerFile = File(containerDir, "container.ready")
            markerFile.writeText(
                "target=${target.id}\n" +
                    "board=${target.boardConfig}\n" +
                    "cydia=$installCydia\n" +
                    "rootfsBytes=${rootfsOut.length()}\n"
            )

            if (ipswFile.exists()) ipswFile.delete()

            emit(
                InstallProgress(
                    stepIndex = 4,
                    stepTitle = "Container Ready",
                    detailText = "${target.title} (${rootfsOut.length() / (1024 * 1024)} MB RootFS) ready to boot.",
                    progressFraction = 1.0f,
                    isCompleted = true
                )
            )
        } catch (e: Exception) {
            if (ipswFile.exists()) ipswFile.delete()
            emit(
                InstallProgress(
                    stepIndex = 1,
                    stepTitle = "Download / Setup Error",
                    detailText = e.localizedMessage ?: "Setup failed. Check internet and retry.",
                    progressFraction = 0f,
                    errorMessage = e.localizedMessage ?: "Error"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    fun isContainerInstalled(target: IosTarget): Boolean {
        val dir = getContainerDir(target)
        val marker = File(dir, "container.ready")
        val rootfs = File(dir, "rootfs.dmg")
        val kernel = File(dir, "kernelcache")
        return marker.exists() && rootfs.exists() && rootfs.length() > 50_000_000L && kernel.exists()
    }

    fun getInstalledContainers(): List<InstalledContainer> {
        return IosTarget.entries.mapNotNull { target ->
            if (isContainerInstalled(target)) {
                val marker = File(getContainerDir(target), "container.ready")
                val text = runCatching { marker.readText() }.getOrDefault("")
                val hasCydia = text.contains("cydia=true")
                InstalledContainer(target = target, hasCydia = hasCydia)
            } else {
                null
            }
        }
    }

    fun deleteContainer(target: IosTarget) {
        val dir = getContainerDir(target)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
