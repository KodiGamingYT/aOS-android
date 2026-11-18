package com.codythelildev.aos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: VmSettings,
    onSettingsChange: (VmSettings) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = Color.Black,
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "aOS Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "CPU & RAM Allocation") {
                Text(
                    text = "QEMU CPU Cores: ${settings.cpuCores}",
                    color = Color.White,
                    fontSize = 15.sp
                )
                Slider(
                    value = settings.cpuCores.toFloat(),
                    onValueChange = {
                        onSettingsChange(settings.copy(cpuCores = it.roundToInt()))
                    },
                    valueRange = 2f..6f,
                    steps = 3
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Guest RAM: ${settings.ramMb} MB",
                    color = Color.White,
                    fontSize = 15.sp
                )
                Slider(
                    value = settings.ramMb.toFloat(),
                    onValueChange = {
                        val snapped = ((it / 256f).roundToInt() * 256).coerceIn(512, 2048)
                        onSettingsChange(settings.copy(ramMb = snapped))
                    },
                    valueRange = 512f..2048f,
                    steps = 5
                )
            }

            SettingsCard(title = "Graphics & Resolution") {
                Text(
                    text = "Resolution Scale: ${(settings.resolutionScale * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 15.sp
                )
                Slider(
                    value = settings.resolutionScale,
                    onValueChange = {
                        val snapped = (it * 4f).roundToInt() / 4f
                        onSettingsChange(settings.copy(resolutionScale = snapped.coerceIn(0.5f, 1.0f)))
                    },
                    valueRange = 0.5f..1.0f,
                    steps = 1
                )

                Spacer(modifier = Modifier.height(8.dp))

                RendererBackend.entries.forEach { backend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsChange(settings.copy(renderer = backend)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.renderer == backend,
                            onClick = { onSettingsChange(settings.copy(renderer = backend)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = backend.label, color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            SettingsCard(title = "Hardware & Network") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Intercept Hardware Volume Keys", color = Color.White, fontSize = 15.sp)
                        Text(
                            "Forward physical Vol+ / Vol- presses to iOS",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.interceptVolumeButtons,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(interceptVolumeButtons = it))
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = Color(0xFF2C2C2E)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Guest Network Bridge", color = Color.White, fontSize = 15.sp)
                        Text(
                            "Enable SLIRP / usbmuxd networking for Safari and Cydia",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.networkBridgeEnabled,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(networkBridgeEnabled = it))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1E),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color(0xFF0A84FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
