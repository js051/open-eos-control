package dev.openeos.control.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
internal fun CameraHudText(
    value: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 7.sp,
    maxLines: Int = 1,
    softWrap: Boolean = false,
) {
    val text = remember(value) { AnnotatedString(value) }
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current.copy(
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign ?: TextAlign.Center,
        letterSpacing = 0.sp,
    )
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val textConstraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        // Fit before drawing, with the same style and constraints as Text, not across UI frames.
        var fontSize = maxFontSize
        while (fontSize > minFontSize) {
            val layout = measurer.measure(
                text = text,
                style = baseStyle.copy(fontSize = fontSize, lineHeight = fontSize * 1.15f),
                constraints = textConstraints,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = TextOverflow.Ellipsis,
            )
            if (!layout.hasVisualOverflow && (0 until layout.lineCount).none(layout::isLineEllipsized)) break
            fontSize = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
        }
        Text(
            text = text,
            style = baseStyle.copy(fontSize = fontSize, lineHeight = fontSize * 1.15f),
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
