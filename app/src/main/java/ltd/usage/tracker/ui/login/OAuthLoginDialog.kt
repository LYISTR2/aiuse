package ltd.usage.tracker.ui.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.OAuthLogin
import ltd.usage.tracker.ui.theme.ProviderLogoImage
import ltd.usage.tracker.ui.theme.RobotoFlexFamily
import ltd.usage.tracker.ui.theme.Type

@Composable
private fun LoginTitle(title: String, titleProviderId: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (titleProviderId != null) {
            ProviderLogoImage(providerId = titleProviderId, size = 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun OAuthLoginDialog(
    title: String,
    onCancel: () -> Unit,
    dismissOnOutside: Boolean = true,
    titleProviderId: String? = null,
    confirm: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cancelButton: @Composable () -> Unit = {
        TextButton(onClick = onCancel) {
            Text("取消", color = scheme.onSurfaceVariant, fontSize = Type.BodySize.sp)
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(26.dp),
        properties = DialogProperties(dismissOnClickOutside = dismissOnOutside),
        title = { LoginTitle(title, titleProviderId) },
        text = body,
        confirmButton = confirm ?: cancelButton,
        dismissButton = if (confirm != null) cancelButton else null,
    )
}

@Composable
internal fun OAuthLoginCardPreview(
    title: String,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    titleProviderId: String? = null,
    body: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Column(Modifier.padding(22.dp)) {
                LoginTitle(title, titleProviderId)
                Spacer(Modifier.height(12.dp))
                body()
                if (buttons != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
                }
            }
        }
    }
}

@Composable
internal fun DeviceCodeBody(
    userCode: String,
    verificationUri: String,
    status: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    hint: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        if (userCode.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaitingSpinner()
                Spacer(Modifier.width(10.dp))
                Text(status, fontSize = Type.CaptionSize.sp, color = scheme.onSurfaceVariant)
            }
            return@Column
        }

        Text(
            "在浏览器打开下方地址，输入设备码完成授权：",
            fontSize = Type.CaptionSize.sp,
            color = scheme.onSurfaceVariant,
            lineHeight = Type.CaptionLineHeight.sp,
        )
        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = scheme.primaryContainer,
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    userCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = RobotoFlexFamily,
                        fontFeatureSettings = "tnum",
                        letterSpacing = 3.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = scheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            verificationUri,
            fontSize = Type.LabelSize.sp,
            color = scheme.outline,
            lineHeight = Type.LabelLineHeight.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(14.dp))
        OpenAndCopyButtons(onOpen = onOpen, onCopy = onCopy)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaitingSpinner()
            Spacer(Modifier.width(8.dp))
            Text(status, fontSize = Type.LabelSize.sp, color = scheme.onSurfaceVariant)
        }
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, fontSize = Type.LabelSize.sp, color = scheme.outline, lineHeight = Type.LabelLineHeight.sp)
        }
    }
}

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
    val scheme = MaterialTheme.colorScheme
    var showCode by remember { mutableStateOf(false) }

    Column {
        Text(
            instruction,
            fontSize = Type.CaptionSize.sp,
            color = scheme.onSurfaceVariant,
            lineHeight = Type.CaptionLineHeight.sp,
        )
        Spacer(Modifier.height(14.dp))
        OpenAndCopyButtons(onOpen = onOpen, onCopy = onCopy)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("授权码", fontSize = Type.CaptionSize.sp) },
            placeholder = { Text("粘贴页面上的 code#state", fontSize = Type.CaptionSize.sp) },
            singleLine = true,
            enabled = !submitting,
            visualTransformation = if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showCode = !showCode }) {
                    Icon(
                        if (showCode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showCode) "隐藏" else "显示",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(Modifier.height(10.dp))
        when {
            error != null -> Text(error, fontSize = Type.LabelSize.sp, color = scheme.error, lineHeight = Type.LabelLineHeight.sp)
            submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
                WaitingSpinner()
                Spacer(Modifier.width(8.dp))
                Text(status, fontSize = Type.LabelSize.sp, color = scheme.onSurfaceVariant)
            }
            else -> Text(status, fontSize = Type.LabelSize.sp, color = scheme.outline, lineHeight = Type.LabelLineHeight.sp)
        }
    }
}

@Composable
private fun OpenAndCopyButtons(onOpen: () -> Unit, onCopy: () -> Unit) {
    Button(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("打开授权页面", fontSize = Type.BodySize.sp, fontWeight = FontWeight.SemiBold)
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("复制链接", fontSize = Type.CaptionSize.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun SubmitCodeButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("完成登录", fontSize = Type.BodySize.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WaitingSpinner() {
    LoadingIndicator(modifier = Modifier.size(20.dp))
}

internal fun oauthTitle(login: OAuthLogin): String = if (login.pasteMode) "登录 Claude" else "登录 ChatGPT"

internal const val CLAUDE_INSTRUCTION = "在浏览器登录 Claude 并授权，页面会显示一串授权码，复制后粘贴到下面："
internal const val CHATGPT_HINT = "需要 Plus / Pro 订阅，并已在 ChatGPT 设置 → 安全 中开启 Codex 设备码授权。"

@Composable
internal fun SubscriptionLoginDialog(
    login: OAuthLogin,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    if (login.pasteMode) {
        var code by rememberSaveable { mutableStateOf("") }
        OAuthLoginDialog(
            title = oauthTitle(login),
            onCancel = onCancel,
            dismissOnOutside = false,
            titleProviderId = Provider.ANTHROPIC.id,
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
        OAuthLoginDialog(title = oauthTitle(login), onCancel = onCancel, titleProviderId = Provider.OPENAI.id) {
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

@Composable
internal fun SubscriptionLoginCardPreview(login: OAuthLogin, pastedCode: String = "") {
    if (login.pasteMode) {
        OAuthLoginCardPreview(
            title = oauthTitle(login),
            titleProviderId = Provider.ANTHROPIC.id,
            buttons = {
                TextButton(onClick = {}) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = Type.BodySize.sp)
                }
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
        OAuthLoginCardPreview(title = oauthTitle(login), titleProviderId = Provider.OPENAI.id) {
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
