package com.coke.nestspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * Indeterminate arc driven by the frame clock rather than the animator scale.
 * CircularProgressIndicator freezes when the system animator scale is 0, which
 * is common on emulators and in "remove animations" accessibility mode.
 */
@Composable
fun FrameSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp,
    sweepAngle: Float = 280f
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    angle = (angle + (now - previous) / 1_000_000f * 360f / 750f) % 360f
                }
                previous = now
            }
        }
    }
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
