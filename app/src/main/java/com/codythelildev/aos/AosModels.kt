package com.codythelildev.aos

enum class IosTarget(
    val id: String,
    val title: String,
    val shortDeviceName: String,
    val deviceModel: String,
    val boardConfig: String,
    val ipswUrl: String,
    val defaultRamMb: Int,
    val supportsCydia: Boolean
) {
    IOS_6_1_4(
        id = "ios_6_1_4",
        title = "iOS 6.1.4",
        shortDeviceName = "iPhone 5",
        deviceModel = "iPhone 5 (iPhone5,2)",
        boardConfig = "n42ap",
        ipswUrl = "https://secure-appldnld.apple.com/iOS6.1/091-2611.20130502.3232A/iPhone5,2_6.1.4_10B350_Restore.ipsw",
        defaultRamMb = 1024,
        supportsCydia = true
    ),
    IOS_7_1_2(
        id = "ios_7_1_2",
        title = "iOS 7.1.2",
        shortDeviceName = "iPhone 4",
        deviceModel = "iPhone 4 (iPhone3,1)",
        boardConfig = "n90ap",
        ipswUrl = "https://secure-appldnld.apple.com/iOS7.1/031-4812.20140627.cq6y8/iPhone3,1_7.1.2_11D257_Restore.ipsw",
        defaultRamMb = 512,
        supportsCydia = true
    ),
    IOS_8_4_1(
        id = "ios_8_4_1",
        title = "iOS 8.4.1",
        shortDeviceName = "iPhone 5",
        deviceModel = "iPhone 5 (iPhone5,1)",
        boardConfig = "n41ap",
        ipswUrl = "https://secure-appldnld.apple.com/ios8.4.1/031-31186-20150812-751D243C-3C8F-11E5-8E4F-B51A3A53DB92/iPhone5,1_8.4.1_12H321_Restore.ipsw",
        defaultRamMb = 1024,
        supportsCydia = true
    )
}

enum class RendererBackend(val label: String, val nativeId: Int) {
    GLES_NATIVE("OpenGL ES (Recommended for Exynos)", 0),
    SOFTWARE_CPU("Software Framebuffer (CPU)", 1)
}

data class VmSettings(
    val cpuCores: Int = 3,
    val ramMb: Int = 2048,
    val resolutionScale: Float = 0.75f,
    val renderer: RendererBackend = RendererBackend.GLES_NATIVE,
    val networkBridgeEnabled: Boolean = true,
    val interceptVolumeButtons: Boolean = true
)

enum class HardwareButton(val qemuKeyCode: Int, val label: String) {
    HOME(102, "Home"),
    LOCK_POWER(116, "Lock"),
    VOLUME_UP(115, "Vol +"),
    VOLUME_DOWN(114, "Vol -")
}
