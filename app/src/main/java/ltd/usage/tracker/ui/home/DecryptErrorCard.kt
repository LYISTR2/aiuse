package ltd.usage.tracker.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ltd.usage.tracker.ui.theme.Type
import androidx.compose.ui.unit.sp

internal const val DECRYPT_ERROR_MESSAGE =
    "本机保存的登录信息无法解密，可能是系统重置了密钥。账号列表还在，请重新登录或填写 API Key。"

/** 放在汇总卡同一个 LazyColumn item 里，避免打乱账号行的 ±1 索引。 */
@Composable
internal fun DecryptErrorCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .testTag("decryptErrorCard"),
        // 与分组列表首项一致：顶部 24dp、底部 6dp
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = scheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                DECRYPT_ERROR_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onErrorContainer,
                fontSize = Type.BodySize.sp,
            )
        }
    }
}
