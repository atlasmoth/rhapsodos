package com.osuji.ereaderapp.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val Ink = Color(0xFF263B2C)
val Moss = Color(0xFF4B674B)
val Cream = Color(0xFFF6F5EE)
val Mist = Color(0xFFE8EDE2)
val Apricot = Color(0xFFEBC7A8)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFB1C9A6), onPrimary = Ink, background = Color(0xFF1B241D), surface = Color(0xFF1B241D), surfaceContainer = Color(0xFF27342A), secondaryContainer = Color(0xFF344635))
    else lightColorScheme(primary = Moss, onPrimary = Color.White, background = Cream, surface = Cream, surfaceContainer = Color(0xFFEDEFE6), secondaryContainer = Mist, onSecondaryContainer = Ink, onSurface = Ink, onBackground = Ink)
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 35.sp),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        ),
        content = content,
    )
}

fun haptic(view: View, confirm: Boolean = false) {
    if (!view.context.getSharedPreferences("presentation", android.content.Context.MODE_PRIVATE).getBoolean("touchFeedback", true)) return
    val feedback = if (confirm && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
    view.performHapticFeedback(feedback)
}

@Composable
fun ListeningAtmosphere(modifier: Modifier, playing: Boolean, reducedMotion: Boolean) {
    val context = LocalContext.current
    val accessibility = context.getSystemService(AccessibilityManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var thermal by remember(power) { mutableIntStateOf(if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(power) {
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { thermal = it }
            power.addThermalStatusListener(context.mainExecutor, listener)
            onDispose { power.removeThermalStatusListener(listener) }
        } else onDispose {}
    }
    val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0
    val overheating = Build.VERSION.SDK_INT >= 29 && thermal >= PowerManager.THERMAL_STATUS_SEVERE
    val lowCost = power.isPowerSaveMode || accessibility.isTouchExplorationEnabled || overheating
    val animate = playing && resumed && !reducedMotion && animations && !lowCost
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "Listening atmosphere")
        val value by transition.animateFloat(0f, 6.28318f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "Ambient gradient")
        value
    } else 0f
    if (Build.VERSION.SDK_INT >= 33 && !lowCost) {
        val shader = remember {
            RuntimeShader("""
                uniform float2 resolution;
                uniform float phase;
                half4 main(float2 position) {
                    float2 uv = position / resolution;
                    float glow = 0.5 + 0.5 * sin(uv.x * 4.0 + uv.y * 3.0 + phase);
                    half3 deep = half3(0.23, 0.34, 0.25);
                    half3 soft = half3(0.38, 0.49, 0.34);
                    return half4(mix(deep, soft, glow * 0.8), 1.0);
                }
            """.trimIndent())
        }
        val paint = remember { android.graphics.Paint() }
        Canvas(modifier) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("phase", phase)
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        }
    } else Canvas(modifier) { drawRect(Brush.linearGradient(listOf(Moss, Color(0xFF6A8060)))) }
}
