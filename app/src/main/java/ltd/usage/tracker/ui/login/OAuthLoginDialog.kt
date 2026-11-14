package ltd.usage.tracker.ui.login

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ltd.usage.tracker.ui.OAuthLogin
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 通用 OAuth 登录对话框
//
// 1.8.0 从 XaiLoginDialog 抽出：外壳（标题 + 正文 + 按钮）与两种正文：
//  - [DeviceCodeBody]：设备码（xAI、ChatGPT）
//  - [PasteCodeBody]：打开授权页 → 粘贴授权码（Claude）

/**
 * 对话框外壳。[confirm] 为 null 时只有一个「取消」按钮（xAI / 设备码，1.7.1 原样）；
 * 否则 [confirm] 放主按钮位，「取消」放次按钮位。
 * [dismissOnOutside] 为 false 时点外面不关闭：粘贴模式下误触外侧会丢掉这次登录。
 */
@Composable
internal fun OAuthLoginDialog(
    title: String,
    onCancel: () -> Unit,
    dismissOnOutside: Boolean = true,
    confirm: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val cancelButton: @Composable () -> Unit = {
        TextButton(onClick = onCancel) {
            Text("取消", color = Palette.TextSecondary, fontSize = Type.BodySize.sp)
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(14.dp),
        properties = DialogProperties(dismissOnClickOutside = dismissOnOutside),
        title = {
            Text(title, fontSize = Type.SheetTitleSize.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
        },
        text = body,
        confirmButton = confirm ?: cancelButton,
        dismissButton = if (confirm != null) cancelButton else null,
    )
}

/**
 * 截图验证用的内联卡片：AlertDialog 在独立 Window 上，截图抓不到，
 * 这里用同一份正文画在页面里。[buttons] 给粘贴模式画底部按钮行。
 */
@Composable
internal fun OAuthLoginCardPreview(
    title: String,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .padding(20.dp),
        ) {
            Text(title, fontSize = Type.SheetTitleSize.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            Spacer(Modifier.height(10.dp))
            body()
            if (buttons != null) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
            }
        }
    }
}

/**
 * 设备码正文：码框 + 授权地址 + 打开 / 复制 + 等待状态。
 * xAI 与 ChatGPT 共用；[hint] 为 null 时与 1.7.1 的 xAI 正文完全一致。
 */
@Composable
internal fun DeviceCodeBody(
    userCode: String,
    verificationUri: String,
    status: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    hint: String? = null,
) {
    Column {
        if (userCode.isBlank()) {
            // 尚未拿到设备码：只显示进度，不显示空的码框
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaitingSpinner()
                Spacer(Modifier.width(8.dp))
                Text(status, fontSize = Type.CaptionSize.sp, color = Palette.TextTertiary)
            }
            return@Column
        }

        Text(
            "在浏览器打开下面的地址，输入设备码：",
            fontSize = Type.CaptionSize.sp,
            color = Palette.TextSecondary,
            lineHeight = Type.CaptionLineHeight.sp,
        )
        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.SurfaceHover)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                userCode,
                fontSize = Type.HeadlineSize.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.TextPrimary,
                letterSpacing = 3.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        // 地址始终可见：即使无法唤起浏览器也能手动输入
        Text(
            verificationUri,
            fontSize = Type.LabelSize.sp,
            color = Palette.TextSecondary,
            lineHeight = Type.LabelLineHeight.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))
        OpenAndCopyButtons(onOpen = onOpen, onCopy = onCopy)

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaitingSpinner()
            Spacer(Modifier.width(8.dp))
            Text(status, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary)
        }
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary, lineHeight = Type.LabelLineHeight.sp)
        }
    }
}

/**
 * 粘贴授权码正文（Claude）：说明 + 打开 / 复制授权地址 + 授权码输入框 + 状态 / 错误。
 * 输入框内容由调用方持有，主按钮「完成登录」在对话框按钮位。
 */
@Composable
internal fun PasteCodeBody(
    instruction: String,
    code: String,
    onCodeChange: (String) -> Unit,
    status: String,
    error: String?,
    submitting: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    Column {
        Text(
            instruction,
            fontSize = Type.CaptionSize.sp,
            color = Palette.TextSecondary,
            lineHeight = Type.CaptionLineHeight.sp,
        )
        Spacer(Modifier.height(12.dp))
        OpenAndCopyButtons(onOpen = onOpen, onCopy = onCopy)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("授权码", fontSize = Type.CaptionSize.sp) },
            placeholder = { Text("粘贴页面上的 code#state", fontSize = Type.CaptionSize.sp, color = Palette.TextTertiary) },
            singleLine = true,
            enabled = !submitting,
            // 授权码一次性、几分钟内有效，但仍按密钥对待：不明文显示
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.TextSecondary,
                unfocusedBorderColor = Palette.Divider,
                disabledBorderColor = Palette.Divider,
                focusedTextColor = Palette.TextPrimary,
                unfocusedTextColor = Palette.TextPrimary,
                disabledTextColor = Palette.TextTertiary,
                focusedLabelColor = Palette.TextSecondary,
                unfocusedLabelColor = Palette.TextTertiary,
                cursorColor = Palette.TextPrimary,
            ),
        )

        Spacer(Modifier.height(10.dp))
        when {
            error != null -> Text(error, fontSize = Type.LabelSize.sp, color = Palette.Negative, lineHeight = Type.LabelLineHeight.sp)
            submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
                WaitingSpinner()
                Spacer(Modifier.width(8.dp))
                Text(status, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary)
            }
            else -> Text(status, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary, lineHeight = Type.LabelLineHeight.sp)
        }
    }
}

/** 「打开授权页面」主按钮 + 「复制链接」文字按钮。 */
@Composable
private fun OpenAndCopyButtons(onOpen: () -> Unit, onCopy: () -> Unit) {
    Button(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.TextPrimary,
            contentColor = Palette.Bg,
        ),
    ) { Text("打开授权页面", fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium) }

    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Text("复制链接", fontSize = Type.CaptionSize.sp, color = Palette.TextSecondary)
    }
}

/** 「完成登录」主按钮（粘贴模式）。 */
@Composable
internal fun SubmitCodeButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.TextPrimary,
            contentColor = Palette.Bg,
            disabledContainerColor = Palette.SurfaceHover,
            disabledContentColor = Palette.TextTertiary,
        ),
    ) { Text("完成登录", fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium) }
}

/**
 * 等待中的小转圈。
 *
 * 12dp 时不确定进度的弧段很短，又没有底环，看上去像被裁掉的半个括号；
 * 改为 14dp 并画一圈分割线色的底环，任何时刻都能看出是完整的圆。
 */
@Composable
internal fun WaitingSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 1.5.dp,
        color = Palette.TextTertiary,
        trackColor = Palette.Divider,
    )
}

// ---------------------------------------------------------------- 订阅登录（Claude / ChatGPT）

/** 订阅登录的对话框标题。 */
internal fun oauthTitle(login: OAuthLogin): String = if (login.pasteMode) "登录 Claude" else "登录 ChatGPT"

internal const val CLAUDE_INSTRUCTION = "在浏览器登录 Claude 并授权，页面会显示一串授权码，复制后粘贴到下面："
internal const val CHATGPT_HINT = "需要 Plus / Pro 订阅，并已在 ChatGPT 设置 → 安全 中开启 Codex 设备码授权。"

/** Claude / ChatGPT 订阅登录对话框。 */
@Composable
internal fun SubscriptionLoginDialog(
    login: OAuthLogin,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (login.pasteMode) {
        // 切到浏览器再回来时 Activity 可能重建，输入内容用 rememberSaveable 保住
        var code by rememberSaveable { mutableStateOf("") }
        OAuthLoginDialog(
            title = oauthTitle(login),
            onCancel = onCancel,
            dismissOnOutside = false,
            confirm = { SubmitCodeButton(enabled = code.isNotBlank() && !login.submitting) { onSubmitCode(code) } },
        ) {
            PasteCodeBody(
                instruction = CLAUDE_INSTRUCTION,
                code = code,
                onCodeChange = { code = it },
                status = login.status,
                error = login.error,
                submitting = login.submitting,
                onOpen = { openInBrowser(context, login.authorizeUrl) },
                onCopy = { copyToClipboard(context, login.authorizeUrl) },
            )
        }
    } else {
        OAuthLoginDialog(title = oauthTitle(login), onCancel = onCancel) {
            DeviceCodeBody(
                userCode = login.userCode,
                verificationUri = login.verificationUri,
                status = login.status,
                onOpen = { openInBrowser(context, login.verificationUri) },
                onCopy = { copyToClipboard(context, login.verificationUri) },
                hint = CHATGPT_HINT,
            )
        }
    }
}

/** 截图验证用：与 [SubscriptionLoginDialog] 共用正文。 */
@Composable
internal fun SubscriptionLoginCardPreview(login: OAuthLogin, pastedCode: String = "") {
    if (login.pasteMode) {
        OAuthLoginCardPreview(
            title = oauthTitle(login),
            buttons = {
                TextButton(onClick = {}) { Text("取消", color = Palette.TextSecondary, fontSize = Type.BodySize.sp) }
                Spacer(Modifier.width(8.dp))
                SubmitCodeButton(enabled = pastedCode.isNotBlank() && !login.submitting) {}
            },
        ) {
            PasteCodeBody(
                instruction = CLAUDE_INSTRUCTION,
                code = pastedCode,
                onCodeChange = {},
                status = login.status,
                error = login.error,
                submitting = login.submitting,
                onOpen = {},
                onCopy = {},
            )
        }
    } else {
        OAuthLoginCardPreview(title = oauthTitle(login)) {
            DeviceCodeBody(
                userCode = login.userCode,
                verificationUri = login.verificationUri,
                status = login.status,
                onOpen = {},
                onCopy = {},
                hint = CHATGPT_HINT,
            )
        }
    }
}
