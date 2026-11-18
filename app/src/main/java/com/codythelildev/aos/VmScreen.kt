package com.codythelildev.aos

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VmScreen(
    target: IosTarget,
    settings: VmSettings,
    onSendHardwareButton: (button: HardwareButton, isPressed: Boolean) -> Unit,
    onSendTouchEvent: (x: Float, y: Float, action: Int) -> Unit,
    onAttachSurface: (SurfaceHolder) -> Unit,
    onDetachSurface: () -> Unit,
    onStopVm: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val defaultStatus = if (QemuNativeBridge.isEngineAvailable()) {
        "QEMU Engine Active"
    } else {
        "Waiting for libqemu-ios.so"
    }
    var statusText by remember { mutableStateOf(defaultStatus) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (!settings.interceptVolumeButtons) return@onPreviewKeyEvent false
                val nativeEvent = keyEvent.nativeKeyEvent
                val isDown = nativeEvent.action == KeyEvent.ACTION_DOWN
                val isUp = nativeEvent.action == KeyEvent.ACTION_UP
                if (!isDown && !isUp) return@onPreviewKeyEvent false

                when (nativeEvent.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        onSendHardwareButton(HardwareButton.VOLUME_UP, isDown)
                        statusText = if (isDown) "GPIO: Vol+ Pressed" else defaultStatus
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        onSendHardwareButton(HardwareButton.VOLUME_DOWN, isDown)
                        statusText = if (isDown) "GPIO: Vol- Pressed" else defaultStatus
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onStopVm) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop VM",
                    tint = Color(0xFFFF453A)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${target.shortDeviceName} • ${target.title}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = statusText,
                    color = if (QemuNativeBridge.isEngineAvailable()) Color(0xFF30D158) else Color(0xFFFF9F0A),
                    fontSize = 11.sp
                )
            }

            FilledTonalButton(
                onClick = {
                    onSendHardwareButton(HardwareButton.LOCK_POWER, true)
                    onSendHardwareButton(HardwareButton.LOCK_POWER, false)
                    statusText = "GPIO: Lock / Power"
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF2C2C2E),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock Button",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lock", fontSize = 12.sp)
            }
        }

        val aspectRatio = if (target == IosTarget.IOS_7_1_2) 2f / 3f else 9f / 16f

        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                .background(Color(0xFF0A0A0C)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onAttachSurface(holder)
                            }
                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                onAttachSurface(holder)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onDetachSurface()
                            }
                        })
                        setOnTouchListener { v, event ->
                            if (v.width > 0 && v.height > 0) {
                                val normX = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
                                val normY = (event.y / v.height.toFloat()).coerceIn(0f, 1f)
                                if (event.action == MotionEvent.ACTION_DOWN ||
                                    event.action == MotionEvent.ACTION_MOVE ||
                                    event.action == MotionEvent.ACTION_UP
                                ) {
                                    onSendTouchEvent(normX, normY, event.action)
                                }
                            }
                            true
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    onSendHardwareButton(HardwareButton.HOME, true)
                    onSendHardwareButton(HardwareButton.HOME, false)
                    statusText = "GPIO: Home Button"
                },
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1E))
                    .border(2.dp, Color(0xFF48484A), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = "Home Button",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
