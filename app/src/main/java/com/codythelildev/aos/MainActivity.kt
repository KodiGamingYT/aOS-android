package com.codythelildev.aos

import android.os.Bundle
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneIphone
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
import kotlinx.coroutines.launch

enum class AosRoute {
    WELCOME,
    CHOOSE_CONTAINER,
    INSTALLING,
    RUNNING_VM,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AosAppRoot()
            }
        }
    }
}

@Composable
fun AosAppRoot() {
    val context = LocalContext.current
    val installer = remember { IpswInstaller(context) }
    val scope = rememberCoroutineScope()

    var currentRoute by remember { mutableStateOf(AosRoute.WELCOME) }
    var previousRoute by remember { mutableStateOf(AosRoute.CHOOSE_CONTAINER) }

    var selectedTarget by remember { mutableStateOf(IosTarget.IOS_7_1_2) }
    var installCydia by remember { mutableStateOf(true) }
    var vmSettings by remember { mutableStateOf(VmSettings()) }
    var installProgress by remember {
        mutableStateOf(
            InstallProgress(1, 4, "Preparing...", "Initializing...", 0f)
        )
    }

    fun bootSelectedContainer() {
        val containerDir = installer.getContainerDir(selectedTarget)
        QemuNativeBridge.startVm(
            containerPath = containerDir.absolutePath,
            boardConfig = selectedTarget.boardConfig,
            cpuCores = vmSettings.cpuCores,
            ramMb = vmSettings.ramMb,
            resolutionScale = vmSettings.resolutionScale,
            rendererId = vmSettings.renderer.nativeId,
            networkEnabled = vmSettings.networkBridgeEnabled
        )
        currentRoute = AosRoute.RUNNING_VM
    }

    when (currentRoute) {
        AosRoute.WELCOME -> {
            WelcomeScreen(
                onGetStarted = { currentRoute = AosRoute.CHOOSE_CONTAINER },
                onOpenSettings = {
                    previousRoute = AosRoute.WELCOME
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
                onOpenSettings = {
                    previousRoute = AosRoute.CHOOSE_CONTAINER
                    currentRoute = AosRoute.SETTINGS
                },
                onProceed = {
                    if (installer.isContainerInstalled(selectedTarget)) {
                        bootSelectedContainer()
                    } else {
                        currentRoute = AosRoute.INSTALLING
                        scope.launch {
                            installer.installContainer(selectedTarget, installCydia).collect { state ->
                                installProgress = state
                                if (state.isCompleted) {
                                    bootSelectedContainer()
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
                onCancel = { currentRoute = AosRoute.CHOOSE_CONTAINER }
            )
        }

        AosRoute.RUNNING_VM -> {
            VmScreen(
                target = selectedTarget,
                cydiaInstalled = installCydia,
                settings = vmSettings,
                onSendHardwareButton = { button, isPressed ->
                    QemuNativeBridge.sendGpioKey(button.qemuKeyCode, isPressed)
                },
                onSendTouchEvent = { x, y, action ->
                    QemuNativeBridge.sendTouchEvent(x, y, action)
                },
                onAttachSurface = { holder: SurfaceHolder ->
                    QemuNativeBridge.attachSurface(holder.surface)
                },
                onDetachSurface = {
                    QemuNativeBridge.detachSurface()
                },
                onStopVm = {
                    QemuNativeBridge.stopVm()
                    currentRoute = AosRoute.CHOOSE_CONTAINER
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
fun ChooseContainerScreen(
    selectedTarget: IosTarget,
    onSelectTarget: (IosTarget) -> Unit,
    installCydia: Boolean,
    onToggleCydia: (Boolean) -> Unit,
    isAlreadyInstalled: Boolean,
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
                Text(
                    text = "Choose your first container",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
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
                                    text = "${target.deviceModel} •${target.defaultRamMb} MB RAM",
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
    onCancel: () -> Unit
) {
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
                text = target.title,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Setting up: step ${progress.stepIndex} of${progress.totalSteps}",
                color = Color(0xFFFF9F0A),
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

            LinearProgressIndicator(
                progress = { progress.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Color(0xFF0A84FF),
                trackColor = Color(0xFF2C2C2E)
            )
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
