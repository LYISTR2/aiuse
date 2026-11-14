package ltd.usage.tracker.ui.home

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 空状态

@Composable
internal fun EmptyState(onAdd: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 上下留白 2:3，内容中心落在约 40% 高度：正中会显得往下坠
        Spacer(Modifier.weight(2f))
        Text(
            "还没有服务",
            fontSize = Type.TitleSize.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "添加 API Key 或登录账号，余额和额度会显示在这里",
            fontSize = Type.CaptionSize.sp,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = Type.CaptionLineHeight.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onAdd,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Palette.TextTertiary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.TextPrimary),
        ) {
            Text("添加服务", fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(3f))
    }
}
