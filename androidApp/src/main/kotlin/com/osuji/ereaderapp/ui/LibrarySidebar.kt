package com.osuji.ereaderapp.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun BoxScope.LibraryRevealEdge(enabled: Boolean, reveal: () -> Unit) {
    if (!enabled) return
    val revealAction by rememberUpdatedState(reveal)
    Box(
        Modifier.align(AbsoluteAlignment.CenterLeft).width(24.dp).height(200.dp)
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var accepted = false
                    var multiplePointers = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) multiplePointers = true
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer != null) {
                            val delta = pointer.position - down.position
                            if (!multiplePointers && delta.x >= 48.dp.toPx() && delta.x > abs(delta.y) * 1.4f) accepted = true
                            pressed = pointer.pressed
                        } else pressed = false
                        event.changes.forEach { it.consume() }
                    }
                    if (accepted && !multiplePointers) revealAction()
                }
            },
    )
}
