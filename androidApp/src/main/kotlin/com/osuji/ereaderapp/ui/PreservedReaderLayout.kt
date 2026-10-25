package com.osuji.ereaderapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

private class ReaderConstraints(var value: Constraints? = null)

@Composable
internal fun PreservedReaderLayout(hidden: Boolean, content: @Composable () -> Unit) {
    val retained = remember { ReaderConstraints() }
    Layout(content = { Box { content() } }, modifier = Modifier.fillMaxSize()) { children, constraints ->
        val readerConstraints = if (hidden) retained.value ?: constraints else constraints.also { retained.value = it }
        val reader = children.single().measure(readerConstraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (!hidden) reader.place(0, 0)
        }
    }
}
