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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import com.osuji.ereaderapp.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val Ink = Color(0xFF241C15)
val Ember = Color(0xFF9F3800)
val Cream = Color(0xFFFFF8EE)
private fun variableFamily(resource: Int) = FontFamily(listOf(400, 500, 600, 700).map { weight ->
    Font(resource, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})
val ReaderFont = variableFamily(R.font.inter)
val DisplayFont = variableFamily(R.font.space_grotesk)
val MonoFont = FontFamily(Font(R.font.space_mono))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFFFB58B), onPrimary = Color(0xFF4F2008), primaryContainer = Color(0xFF693116), onPrimaryContainer = Color(0xFFFFE3CC),
        secondary = Color(0xFFE3BCA0), onSecondary = Color(0xFF402B1B), secondaryContainer = Color(0xFF4D3323), onSecondaryContainer = Color(0xFFFFE3CC),
        background = Color(0xFF161412), onBackground = Color(0xFFFFF5E8), surface = Color(0xFF211D19), onSurface = Color(0xFFFFF5E8),
        surfaceContainer = Color(0xFF2B241E), surfaceContainerHigh = Color(0xFF362D25), surfaceContainerHighest = Color(0xFF42362B),
        onSurfaceVariant = Color(0xFFC6B5A3), outline = Color(0xFFA08C78), outlineVariant = Color(0xFF594839),
    ) else lightColorScheme(
        primary = Ember, onPrimary = Color.White, primaryContainer = Color(0xFFFFE3CC), onPrimaryContainer = Color(0xFF562208),
        secondary = Color(0xFF795238), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE3CC), onSecondaryContainer = Ink,
        background = Cream, onBackground = Ink, surface = Color(0xFFFFFDF8), onSurface = Ink,
        surfaceContainer = Color(0xFFF4EBDD), surfaceContainerHigh = Color(0xFFF0E4D4), surfaceContainerHighest = Color(0xFFEADDCC),
        onSurfaceVariant = Color(0xFF756657), outline = Color(0xFF95816C), outlineVariant = Color(0xFFD8C8B6),
    )
    val typography = Typography().run { copy(
        displayLarge = displayLarge.copy(fontFamily = DisplayFont), displayMedium = displayMedium.copy(fontFamily = DisplayFont),
        displaySmall = TextStyle(fontFamily = DisplayFont, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
        headlineLarge = headlineLarge.copy(fontFamily = DisplayFont), headlineMedium = headlineMedium.copy(fontFamily = DisplayFont), headlineSmall = headlineSmall.copy(fontFamily = DisplayFont),
        titleLarge = titleLarge.copy(fontFamily = ReaderFont, fontWeight = FontWeight.SemiBold), titleMedium = titleMedium.copy(fontFamily = ReaderFont), titleSmall = titleSmall.copy(fontFamily = ReaderFont),
        bodyLarge = bodyLarge.copy(fontFamily = ReaderFont, fontSize = 17.sp, lineHeight = 28.sp), bodyMedium = bodyMedium.copy(fontFamily = ReaderFont), bodySmall = bodySmall.copy(fontFamily = ReaderFont),
        labelLarge = labelLarge.copy(fontFamily = ReaderFont), labelMedium = labelMedium.copy(fontFamily = ReaderFont), labelSmall = labelSmall.copy(fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
    ) }
    MaterialExpressiveTheme(colorScheme = colors, typography = typography, content = content)
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
                    half3 deep = half3(0.10, 0.08, 0.06);
                    half3 soft = half3(0.62, 0.24, 0.08);
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
    } else Canvas(modifier) { drawRect(Brush.linearGradient(listOf(Color(0xFF211710), Color(0xFF8C3510)))) }
}
