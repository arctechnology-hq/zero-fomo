package com.arctechnology.zerofomo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arctechnology.zerofomo.R

/**
 * "The Cancelled Zero" drawn live so the arms can take the selected
 * country's flag colours (docs/BRAND.md §2, §9). Geometry mirrors
 * `ic_brand_mark.xml` exactly (24-unit viewport): two 200-degree arcs of
 * radius 7.5 opening at the top and crossing at the bottom, and a bar from
 * top-left to bottom-right that never mirrors.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier.size(24.dp),
    armA: Color = LocalCountryAccents.current.armA,
    armB: Color = LocalCountryAccents.current.armB,
    bar: Color = Sand,
) {
    val wordmark = stringResource(R.string.app_name)
    Canvas(modifier.semantics { contentDescription = wordmark }) {
        val s = size.minDimension
        val k = s / 24f
        val stroke = Stroke(width = 2.6f * k, cap = StrokeCap.Round)
        val r = 7.5f * k
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(2 * r, 2 * r)
        // Left arm: starts top-left, sweeps counter-clockwise down the left side.
        drawArc(armA, startAngle = 250f, sweepAngle = -200f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke)
        // Right arm: starts top-right, sweeps clockwise down the right side.
        drawArc(armB, startAngle = 290f, sweepAngle = 200f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke)
        // The cancel bar, always a backslash.
        val o = center - Offset(r, r)
        drawLine(bar,
            start = o + Offset(8.82f * k - (12f * k - r), 8.82f * k - (12f * k - r)),
            end = o + Offset(15.18f * k - (12f * k - r), 15.18f * k - (12f * k - r)),
            strokeWidth = 2.6f * k, cap = StrokeCap.Round)
    }
}
