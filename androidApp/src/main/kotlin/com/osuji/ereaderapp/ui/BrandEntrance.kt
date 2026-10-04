package com.osuji.ereaderapp.ui

import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sin

@Composable
fun BrandEntrance() {
    var visible by rememberSaveable { mutableStateOf(true) }
    if (!visible) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = context.getSystemService(PowerManager::class.java)
    val accessibility = context.getSystemService(AccessibilityManager::class.java)
    val reduced = context.getSharedPreferences("presentation", android.content.Context.MODE_PRIVATE).getBoolean("reducedMotion", false) ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f || power.isPowerSaveMode || accessibility.isTouchExplorationEnabled ||
        (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { visible = false; return@LaunchedEffect }
        phase.animateTo(1f, tween(if (reduced) 150 else 800, easing = FastOutSlowInEasing))
        visible = false
    }
    Canvas(Modifier.fillMaxSize()) {
        val progress = phase.value
        val fade = if (reduced) 1f - progress else (1f - ((progress - 0.58f) / 0.42f).coerceIn(0f, 1f))
        drawRect(Color(0xFF161412).copy(alpha = fade))
        val side = minOf(size.width, size.height) * 0.55f
        val unit = side / 256f
        val origin = center - Offset(side / 2, side / 2)
        if (!reduced) drawCircle(Brush.radialGradient(listOf(Color(0xFFF47A36).copy(alpha = 0.23f * fade), Color.Transparent), center = center, radius = side * 0.72f), radius = side * 0.72f, center = center)
        val tops = listOf(108f, 88f, 65f, 49f, 65f, 88f, 108f)
        val heights = listOf(40f, 80f, 126f, 158f, 126f, 80f, 40f)
        tops.forEachIndexed { index, top ->
            val reveal = if (reduced) 1f else ((progress * 2.2f - index * 0.085f) / 0.72f).coerceIn(0f, 1f)
            val wave = if (reduced) 1f else 1f + 0.07f * sin(progress * 15f - index * 0.7f)
            val height = heights[index] * reveal * wave
            val x = 56f + index * 21f
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFC29A).copy(alpha = fade), Color(0xFFF47A36).copy(alpha = fade)), startY = origin.y, endY = origin.y + side), topLeft = origin + Offset(x * unit, (top + (heights[index] - height) / 2) * unit), size = Size(14f * unit, height * unit), cornerRadius = CornerRadius(7f * unit))
        }
    }
}
