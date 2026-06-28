package com.p2p.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// consts
private const val TURNS = 4.0
private const val SPIN = 1.0
private const val PREC = 1.0
private const val TILT = 30.0
private const val TWO_PI = 2.0 * PI


@Composable
fun FullScreenLoader(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (animationsEnabled()) {
                TwistLoader()
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
            }
            if (label != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * twist circle loader
 */
@Composable
fun TwistLoader(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
    size: Dp = 150.dp,
    cycleMillis: Int = 8000
) {
    val transition = rememberInfiniteTransition(label = "twist")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycleMillis, easing = LinearEasing)),
        label = "phase"
    )
    TwistLoaderContent(phase = phase, color = color, modifier = modifier.size(size))
}

/**
 * отрисовка одного кадра по phase (0..1). Вынесено отдельно ради @Preview и тестируемости математики
 */
@Composable
fun TwistLoaderContent(
    phase: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val near = color
    val far = lerp(Color.Black, color, 0.5f)

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.72f

        // A: огибающая 0-1-0 за цикл
        val a = sin(PI * phase).let { it * it }
        val theta = a * TURNS * PI
        val n = (220 + 110 * (theta / PI)).toInt().coerceAtLeast(2)

        val spinAngle = phase * TWO_PI * SPIN * a
        val precAngle = phase * TWO_PI * PREC * a
        val beta = a * TILT * 0.09

        val px = FloatArray(n + 1)
        val py = FloatArray(n + 1)
        val pz = DoubleArray(n + 1)

        for (i in 0..n) {
            val s = i.toDouble() / n
            val ang = TWO_PI * s
            val x = cos(ang)
            val y = sin(ang)

            // ТВИСТ: alpha = theta * y
            val alpha = theta * y
            var vx = x * cos(alpha)
            var vy = y
            var vz = -x * sin(alpha)

            // ВОЛЧОК: rotZ(spin) -> rotX(beta) -> rotZ(prec)
            run { val c = cos(spinAngle); val sn = sin(spinAngle); val nx = vx * c - vy * sn; val ny = vx * sn + vy * c; vx = nx; vy = ny }
            run { val c = cos(beta); val sn = sin(beta); val ny = vy * c - vz * sn; val nz = vy * sn + vz * c; vy = ny; vz = nz }
            run { val c = cos(precAngle); val sn = sin(precAngle); val nx = vx * c - vy * sn; val ny = vx * sn + vy * c; vx = nx; vy = ny }

            px[i] = cx + (vx * r).toFloat()
            py[i] = cy - (vy * r).toFloat()
            pz[i] = vz
        }

        // Painter's algorithm: рисуем сегменты от дальних к ближним
        val order = (0 until n).sortedBy { (pz[it] + pz[it + 1]) / 2.0 }
        for (i in order) {
            val depth01 = (((pz[i] + pz[i + 1]) / 2.0) + 1.0) / 2.0
            val width = (1.2f + depth01.toFloat() * 1.8f).dp.toPx()
            val lineAlpha = ((115 + depth01 * 140) / 255.0).toFloat().coerceIn(0f, 1f)
            val lineColor = lerp(far, near, depth01.toFloat()).copy(alpha = lineAlpha)
            drawLine(
                color = lineColor,
                start = Offset(px[i], py[i]),
                end = Offset(px[i + 1], py[i + 1]),
                strokeWidth = width,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
}
