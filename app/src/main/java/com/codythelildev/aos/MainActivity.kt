package com.codythelildev.aos

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AosRoute {
    WELCOME,
    CONTAINER_LIBRARY,
    CHOOSE_CONTAINER,
    INSTALLING,
    RUNNING_VM,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        QemuNativeBridge.initLogger(this)
        QemuNativeBridge.logToDocuments("MainActivity", "onCreate() started")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            QemuNativeBridge.logToDocuments(
                "UNCAUGHT_CRASH",
                "Thread '${thread.name}' crashed:\n${Log.getStackTraceString(throwable)}"
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AosAppRoot()
            }
        }
    }

    override fun onDestroy() {
        QemuNativeBridge.logToDocuments("MainActivity", "onDestroy() called -> stopping VM")
        QemuNativeBridge.stopVm()
        super.onDestroy()
    }
}

@Composable
fun AosAppRoot() {
    val context = LocalContext.current
    val installer = remember { IpswInstaller(context) }
    val scope = rememberCoroutineScope()

    var installedContainers by remember { mutableStateOf(installer.getInstalledContainers()) }
    var currentRoute by remember {
        mutableStateOf(
            if (installer.getInstalledContainers().isNotEmpty()) {
                AosRoute.CONTAINER_LIBRARY
            } else {
                AosRoute.WELCOME
            }
        )
    }
    var previousRoute by remember { mutableStateOf(currentRoute) }

    var selectedTarget by remember { mutableStateOf(IosTarget.IOS_7_1_2) }
    var installCydia by remember { mutableStateOf(false) }
    var vmSettings by remember { mutableStateOf(VmSettings()) }
    var isVmBootTriggered by remember { mutableStateOf(false) }
    var installProgress by remember {
        mutableStateOf(
            InstallProgress(1, 4, "Preparing...", "Initializing...", 0f)
        )
    }

    fun refreshContainers() {
        installedContainers = installer.getInstalledContainers()
        QemuNativeBridge.logToDocuments("MainActivity", "Refreshed containers count=${installedContainers.size}")
    }

    fun prepareBootContainer(target: IosTarget) {
        QemuNativeBridge.logToDocuments(
            "MainActivity",
            "prepareBootContainer() -> target=${target.id}, board=${target.boardConfig}, ram=${vmSettings.ramMb}MB"
        )
        selectedTarget = target
        isVmBootTriggered = false
        currentRoute = AosRoute.RUNNING_VM
    }

    fun stopAndExitVm() {
        QemuNativeBridge.logToDocuments("MainActivity", "stopAndExitVm() triggered by user")
        isVmBootTriggered = false
        QemuNativeBridge.detachSurface()
        QemuNativeBridge.stopVm()
        refreshContainers()
        currentRoute = if (installedContainers.isNotEmpty()) {
            AosRoute.CONTAINER_LIBRARY
        } else {
            AosRoute.CHOOSE_CONTAINER
        }
    }

    when (currentRoute) {
        AosRoute.WELCOME -> {
            WelcomeScreen(
                onGetStarted = {
                    QemuNativeBridge.logToDocuments("MainActivity", "Route -> CHOOSE_CONTAINER")
                    currentRoute = AosRoute.CHOOSE_CONTAINER
                },
                onOpenSettings = {
                    previousRoute = AosRoute.WELCOME
                    currentRoute = AosRoute.SETTINGS
                }
            )
        }

        AosRoute.CONTAINER_LIBRARY -> {
            ContainerLibraryScreen(
                containers = installedContainers,
                onBootContainer = { container ->
                    QemuNativeBridge.logToDocuments("MainActivity", "Clicked boot on ${container.target.title}")
                    vmSettings = vmSettings.copy(ramMb = container.target.defaultRamMb)
                    prepareBootContainer(container.target)
                },
                onDeleteContainer = { container ->
                    QemuNativeBridge.logToDocuments("MainActivity", "Deleting container ${container.target.id}")
                    installer.deleteContainer(container.target)
                    refreshContainers()
                    if (installer.getInstalledContainers().isEmpty()) {
                        currentRoute = AosRoute.CHOOSE_CONTAINER
                    }
                },
                onAddNewContainer = {
                    installCydia = false
                    currentRoute = AosRoute.CHOOSE_CONTAINER
                },
                onOpenSettings = {
                    previousRoute = AosRoute.CONTAINER_LIBRARY
                    currentRoute = AosRoute.SETTINGS
                }
            )
        }

        AosRoute.CHOOSE_CONTAINER -> {
            ChooseContainerScreen(
                selectedTarget = selectedTarget,
                onSelectTarget = {
                    selectedTarget = it
                    vmSettings = vmSettings.copy(ramMb = it.defaultRamMb)
                },
                installCydia = installCydia,
                onToggleCydia = { installCydia = it },
                isAlreadyInstalled = installer.isContainerInstalled(selectedTarget),
                hasExistingContainers = installedContainers.isNotEmpty(),
                onBackToLibrary = {
                    refreshContainers()
                    currentRoute = AosRoute.CONTAINER_LIBRARY
                },
                onOpenSettings = {
                    previousRoute = AosRoute.CHOOSE_CONTAINER
                    currentRoute = AosRoute.SETTINGS
                },
                onProceed = {
                    if (installer.isContainerInstalled(selectedTarget)) {
                        QemuNativeBridge.logToDocuments("MainActivity", "Container already installed, booting ${selectedTarget.id}")
                        prepareBootContainer(selectedTarget)
                    } else {
                        QemuNativeBridge.logToDocuments("MainActivity", "Starting IPSW install for ${selectedTarget.id} (cydia=$installCydia)")
                        currentRoute = AosRoute.INSTALLING
                        scope.launch {
                            installer.installContainer(selectedTarget, installCydia).collect { state ->
                                installProgress = state
                                if (state.errorMessage != null) {
                                    QemuNativeBridge.logToDocuments("IpswInstaller", "ERROR: ${state.errorMessage}")
                                }
                                if (state.isCompleted) {
                                    QemuNativeBridge.logToDocuments("IpswInstaller", "Install completed for ${selectedTarget.id}")
                                    refreshContainers()
                                    prepareBootContainer(selectedTarget)
                                }
                            }
                        }
                    }
                }
            )
        }

        AosRoute.INSTALLING -> {
            InstallingScreen(
                target = selectedTarget,
                progress = installProgress,
                onRetry = {
                    QemuNativeBridge.logToDocuments("MainActivity", "Retrying install for ${selectedTarget.id}")
                    scope.launch {
                        installer.installContainer(selectedTarget, installCydia).collect { state ->
                            installProgress = state
                            if (state.isCompleted) {
                                refreshContainers()
                                prepareBootContainer(selectedTarget)
                            }
                        }
                    }
                },
                onCancel = {
                    QemuNativeBridge.logToDocuments("MainActivity", "Install cancelled")
                    refreshContainers()
                    currentRoute = if (installedContainers.isNotEmpty()) {
                        AosRoute.CONTAINER_LIBRARY
                    } else {
                        AosRoute.CHOOSE_CONTAINER
                    }
                }
            )
        }

        AosRoute.RUNNING_VM -> {
            BackHandler {
                stopAndExitVm()
            }

            VmScreen(
                target = selectedTarget,
                settings = vmSettings,
                onSendHardwareButton = { button, isPressed ->
                    QemuNativeBridge.sendGpioKey(button.qemuKeyCode, isPressed)
                },
                onSendTouchEvent = { x, y, action ->
                    QemuNativeBridge.sendTouchEvent(x, y, action)
                },
                onAttachSurface = { holder: SurfaceHolder ->
                    val surface = holder.surface
                    QemuNativeBridge.logToDocuments(
                        "MainActivity",
                        "VmScreen onAttachSurface -> surface=$surface, isValid=${surface?.isValid}, isVmBootTriggered=$isVmBootTriggered"
                    )
                    if (surface != null && surface.isValid) {
                        QemuNativeBridge.attachSurface(surface)
                        if (!isVmBootTriggered) {
                            isVmBootTriggered = true
                            val containerDir = installer.getContainerDir(selectedTarget)
                            val targetSnapshot = selectedTarget
                            val settingsSnapshot = vmSettings
                            scope.launch(Dispatchers.IO) {
                                QemuNativeBridge.logToDocuments(
                                    "MainActivity",
                                    "Launching QemuNativeBridge.startVm on Dispatchers.IO for ${targetSnapshot.id}"
                                )
                                QemuNativeBridge.startVm(
                                    containerPath = containerDir.absolutePath,
                                    boardConfig = targetSnapshot.boardConfig,
                                    cpuCores = settingsSnapshot.cpuCores,
                                    ramMb = settingsSnapshot.ramMb,
                                    resolutionScale = settingsSnapshot.resolutionScale,
                                    rendererId = settingsSnapshot.renderer.nativeId,
                                    networkEnabled = settingsSnapshot.networkBridgeEnabled
                                )
                            }
                        }
                    }
                },
                onDetachSurface = {
                    QemuNativeBridge.logToDocuments("MainActivity", "VmScreen onDetachSurface")
                    QemuNativeBridge.detachSurface()
                },
                onStopVm = {
                    stopAndExitVm()
                }
            )
        }

        AosRoute.SETTINGS -> {
            SettingsScreen(
                settings = vmSettings,
                onSettingsChange = { vmSettings = it },
                onBack = { currentRoute = previousRoute }
            )
        }
    }
}

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "aOS",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2).sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "iOS in app on your android!",
                color = Color(0xFF8E8E93),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0A84FF),
                contentColor = Color.White
            )
        ) {
            Text("Let's get started", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ContainerLibraryScreen(
    containers: List<InstalledContainer>,
    onBootContainer: (InstalledContainer) -> Unit,
    onDeleteContainer: (InstalledContainer) -> Unit,
    onAddNewContainer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Containers",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select an iOS container to boot",
                        color = Color(0xFF8E8E93),
                        fontSize = 14.sp
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(containers, key = { it.target.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1C1C1E),
                        border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBootContainer(item) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneIphone,
                                    contentDescription = null,
                                    tint = Color(0xFF0A84FF),
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "${item.target.shortDeviceName} • ${item.target.title}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        text = if (item.hasCydia) "Jailbroken (Cydia)" else "Stock iOS • ${item.target.defaultRamMb} MB RAM",
                                        color = Color(0xFF8E8E93),
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onDeleteContainer(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFFF453A)
                                    )
                                }
                                IconButton(onClick = { onBootContainer(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Boot",
                                        tint = Color(0xFF30D158),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onAddNewContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0A84FF),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Container", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ChooseContainerScreen(
    selectedTarget: IosTarget,
    onSelectTarget: (IosTarget) -> Unit,
    installCydia: Boolean,
    onToggleCydia: (Boolean) -> Unit,
    isAlreadyInstalled: Boolean,
    hasExistingContainers: Boolean,
    onBackToLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onProceed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasExistingContainers) {
                        IconButton(onClick = onBackToLibrary) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    }
                    Text(
                        text = if (hasExistingContainers) "New container" else "Choose your first container",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Official IPSW will be fetched securely from Apple servers.",
                color = Color(0xFF8E8E93),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            IosTarget.entries.forEach { target ->
                val isSelected = target == selectedTarget
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1C1C1E),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onSelectTarget(target) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhoneIphone,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFF0A84FF) else Color.Gray,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = target.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "${target.deviceModel} • ${target.defaultRamMb} MB RAM",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 13.sp
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color(0xFF0A84FF)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Install Cydia (Jailbreak)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Pre-inject untethered jailbreak & Cydia package manager",
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp
                        )
                    }
                    Switch(checked = installCydia, onCheckedChange = onToggleCydia)
                }
            }
        }

        Button(
            onClick = onProceed,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF30D158),
                contentColor = Color.Black
            )
        ) {
            Text(
                text = if (isAlreadyInstalled) "Boot ${selectedTarget.title}" else "Download & Build Container",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InstallingScreen(
    target: IosTarget,
    progress: InstallProgress,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val hasError = progress.errorMessage != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "${target.shortDeviceName} • ${target.title}",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hasError) "Setup failed" else "Setting up: step ${progress.stepIndex} of ${progress.totalSteps}",
                color = if (hasError) Color(0xFFFF453A) else Color(0xFFFF9F0A),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = progress.stepTitle,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = progress.detailText,
                color = Color(0xFF8E8E93),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (!hasError) {
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFF0A84FF),
                    trackColor = Color(0xFF2C2C2E)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasError) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A84FF),
                        contentColor = Color.White
                    )
                ) {
                    Text("Retry Download", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFFF453A))
            ) {
                Text("Cancel", color = Color(0xFFFF453A), fontSize = 16.sp)
            }
        }
    }
}
