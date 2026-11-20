package com.codythelildev.aos

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object QemuNativeBridge {

    private const val TAG = "QemuNativeBridge"
    private var isLibraryLoaded = false
    var lastLoadError: String? = null
        private set

    private var appContext: Context? = null
    private var nativeStderrPfd: ParcelFileDescriptor? = null
    private var activeLogFileName: String = "aos_session.txt"
    private var activeNativeLogName: String = "aos_qemu_stderr.txt"

    init {
        try {
            System.loadLibrary("qemu-ios")
            isLibraryLoaded = true
            lastLoadError = null
        } catch (e: UnsatisfiedLinkError) {
            lastLoadError = e.message ?: "Unknown UnsatisfiedLinkError"
            Log.e(TAG, "Failed to load libqemu-ios.so: $lastLoadError")
            isLibraryLoaded = false
        }
    }

    fun initLogger(context: Context) {
        appContext = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        activeLogFileName = "aos_log_$stamp.txt"
        activeNativeLogName = "aos_qemu_native_$stamp.txt"

        logToDocuments("QemuNativeBridge", "Logger initialized. isLibraryLoaded=$isLibraryLoaded, loadError=$lastLoadError")
        redirectNativeStdioToDocuments()
    }

    fun logToDocuments(source: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$timestamp] [$source] $message\n"
        Log.d(TAG, line.trim())

        val ctx = appContext ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                val relPath = "${Environment.DIRECTORY_DOCUMENTS}/aOS_logs/"
                val selectionArgs = arrayOf(activeLogFileName, relPath)

                var existingUri = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id)
                    } else null
                }

                if (existingUri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, activeLogFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    }
                    existingUri = resolver.insert(collection, values)
                }

                existingUri?.let { uri ->
                    resolver.openOutputStream(uri, "wa")?.use { out ->
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            } else {
                val docsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "aOS_logs"
                )
                if (!docsDir.exists()) docsDir.mkdirs()
                val logFile = File(docsDir, activeLogFileName)
                FileOutputStream(logFile, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing log to Documents: ${e.message}")
        }
    }

    private fun redirectNativeStdioToDocuments() {
        val ctx = appContext ?: return
        try {
            nativeStderrPfd?.close()
            nativeStderrPfd = null

            val pfd: ParcelFileDescriptor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relPath = "${Environment.DIRECTORY_DOCUMENTS}/aOS_logs/"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, activeNativeLogName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = resolver.insert(collection, values)
                uri?.let { resolver.openFileDescriptor(it, "w") }
            } else {
                val docsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "aOS_logs"
                )
                if (!docsDir.exists()) docsDir.mkdirs()
                val nativeFile = File(docsDir, activeNativeLogName)
                ParcelFileDescriptor.open(
                    nativeFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_APPEND
                )
            }

            if (pfd != null) {
                nativeStderrPfd = pfd
                val fd = pfd.fileDescriptor
                Os.dup2(fd, 1) // stdout -> Documents/aOS_logs/aos_qemu_native_*.txt
                Os.dup2(fd, 2) // stderr -> Documents/aOS_logs/aos_qemu_native_*.txt
                logToDocuments("QemuNativeBridge", "Redirected native stdout/stderr to $activeNativeLogName")
            }
        } catch (e: Exception) {
            logToDocuments("QemuNativeBridge", "Failed to redirect native stdio: ${e.message}")
        }
    }

    fun isEngineAvailable(): Boolean = isLibraryLoaded

    fun startVm(
        containerPath: String,
        boardConfig: String,
        cpuCores: Int,
        ramMb: Int,
        resolutionScale: Float,
        rendererId: Int,
        networkEnabled: Boolean
    ): Boolean {
        logToDocuments(
            "QemuNativeBridge",
            "startVm() called -> path=$containerPath, board=$boardConfig, cores=$cpuCores, ram=${ramMb}MB, scale=$resolutionScale, renderer=$rendererId, net=$networkEnabled"
        )

        val dir = File(containerPath)
        if (dir.exists()) {
            val filesList = dir.listFiles()?.joinToString(", ") { "${it.name} (${it.length()}B)" } ?: "empty"
            logToDocuments("QemuNativeBridge", "Container files on disk: [$filesList]")
        } else {
            logToDocuments("QemuNativeBridge", "WARNING: containerPath does NOT exist!")
        }

        if (!isLibraryLoaded) {
            logToDocuments("QemuNativeBridge", "Aborting startVm: library not loaded ($lastLoadError)")
            return false
        }

        return try {
            val result = nativeStartVm(
                containerPath,
                boardConfig,
                cpuCores,
                ramMb,
                resolutionScale,
                rendererId,
                networkEnabled
            )
            logToDocuments("QemuNativeBridge", "nativeStartVm() returned: $result")
            result
        } catch (t: Throwable) {
            logToDocuments("QemuNativeBridge", "EXCEPTION in nativeStartVm: ${Log.getStackTraceString(t)}")
            false
        }
    }

    fun stopVm() {
        logToDocuments("QemuNativeBridge", "stopVm() called")
        if (isLibraryLoaded) {
            try {
                nativeStopVm()
                logToDocuments("QemuNativeBridge", "nativeStopVm() finished")
            } catch (t: Throwable) {
                logToDocuments("QemuNativeBridge", "EXCEPTION in nativeStopVm: ${Log.getStackTraceString(t)}")
            }
        }
    }

    fun attachSurface(surface: Surface) {
        logToDocuments("QemuNativeBridge", "attachSurface() called (isValid=${surface.isValid})")
        if (isLibraryLoaded) {
            try {
                nativeAttachSurface(surface)
                logToDocuments("QemuNativeBridge", "nativeAttachSurface() finished")
            } catch (t: Throwable) {
                logToDocuments("QemuNativeBridge", "EXCEPTION in nativeAttachSurface: ${Log.getStackTraceString(t)}")
            }
        }
    }

    fun detachSurface() {
        logToDocuments("QemuNativeBridge", "detachSurface() called")
        if (isLibraryLoaded) {
            try {
                nativeDetachSurface()
            } catch (t: Throwable) {
                logToDocuments("QemuNativeBridge", "EXCEPTION in nativeDetachSurface: ${Log.getStackTraceString(t)}")
            }
        }
    }

    fun sendGpioKey(keyCode: Int, isPressed: Boolean) {
        logToDocuments("QemuNativeBridge", "sendGpioKey(keyCode=$keyCode, isPressed=$isPressed)")
        if (isLibraryLoaded) {
            try {
                nativeSendGpioKey(keyCode, isPressed)
            } catch (t: Throwable) {
                logToDocuments("QemuNativeBridge", "EXCEPTION in nativeSendGpioKey: ${Log.getStackTraceString(t)}")
            }
        }
    }

    fun sendTouchEvent(normalizedX: Float, normalizedY: Float, action: Int) {
        if (isLibraryLoaded) {
            try {
                nativeSendTouchEvent(normalizedX, normalizedY, action)
            } catch (t: Throwable) {
                logToDocuments("QemuNativeBridge", "EXCEPTION in nativeSendTouchEvent: ${Log.getStackTraceString(t)}")
            }
        }
    }

    private external fun nativeStartVm(
        containerPath: String,
        boardConfig: String,
        cpuCores: Int,
        ramMb: Int,
        resolutionScale: Float,
        rendererId: Int,
        networkEnabled: Boolean
    ): Boolean

    private external fun nativeStopVm()
    private external fun nativeAttachSurface(surface: Surface)
    private external fun nativeDetachSurface()
    private external fun nativeSendGpioKey(keyCode: Int, isPressed: Boolean)
    private external fun nativeSendTouchEvent(normalizedX: Float, normalizedY: Float, action: Int)
}
