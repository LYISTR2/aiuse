package ltd.usage.tracker.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * 金额逐位滚动。只在单个字符变化时动画；减弱动效时直接换字。
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = Palette.TextPrimary,
    reduceMotion: Boolean = false,
) {
    if (reduceMotion) {
        Text(text, modifier = modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier) {
        text.forEachIndexed { index, c ->
            if (c.isDigit()) {
                AnimatedContent(
                    targetState = c,
                    transitionSpec = {
                        (slideInVertically { it } + fadeIn()) togetherWith
                            (slideOutVertically { -it } + fadeOut())
                    },
                    label = "digit-$index",
                ) { d ->
                    Text(d.toString(), style = style, color = color)
                }
            } else {
                Text(c.toString(), style = style, color = color)
            }
        }
    }
}
