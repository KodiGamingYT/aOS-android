package com.codythelildev.aos

import android.util.Log
import android.view.Surface

object QemuNativeBridge {

    private const val TAG = "QemuNativeBridge"
    private var isLibraryLoaded = false
    var lastLoadError: String? = null
        private set

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
        if (!isLibraryLoaded) return false
        return nativeStartVm(
            containerPath,
            boardConfig,
            cpuCores,
            ramMb,
            resolutionScale,
            rendererId,
            networkEnabled
        )
    }

    fun stopVm() {
        if (isLibraryLoaded) {
            nativeStopVm()
        }
    }

    fun attachSurface(surface: Surface) {
        if (isLibraryLoaded) {
            nativeAttachSurface(surface)
        }
    }

    fun detachSurface() {
        if (isLibraryLoaded) {
            nativeDetachSurface()
        }
    }

    fun sendGpioKey(keyCode: Int, isPressed: Boolean) {
        if (isLibraryLoaded) {
            nativeSendGpioKey(keyCode, isPressed)
        }
    }

    fun sendTouchEvent(normalizedX: Float, normalizedY: Float, action: Int) {
        if (isLibraryLoaded) {
            nativeSendTouchEvent(normalizedX, normalizedY, action)
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
