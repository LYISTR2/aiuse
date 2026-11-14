package ltd.usage.tracker.ui.add

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 添加

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAccountSheet(
    onDismiss: () -> Unit,
    onConfirm: (Provider, String, String) -> Unit,
    onXaiLogin: () -> Unit,
    /** 1.8.0：Anthropic / OpenAI 选「登录订阅」后点登录；参数是 ANTHROPIC 或 OPENAI。 */
    onSubscriptionLogin: (Provider) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(Palette.Divider)
            )
        },
    ) {
        AddAccountForm(onDismiss, onConfirm, onXaiLogin, onSubscriptionLogin)
    }
}

/**
 * 表单正文。1.8.0 从 [AddAccountSheet] 里原样拆出：底部弹层在独立 Window 上，截图抓不到，
 * 截图时直接画这一段；[initialProvider] / [initialMethod] 只给截图用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAccountForm(
    onDismiss: () -> Unit,
    onConfirm: (Provider, String, String) -> Unit,
    onXaiLogin: () -> Unit,
    onSubscriptionLogin: (Provider) -> Unit,
    initialProvider: Provider = Provider.DEEPSEEK,
    initialMethod: AddMethod = AddMethod.API_KEY,
) {
    var provider by remember { mutableStateOf(initialProvider) }
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf(initialMethod) }
    val subscription = subscriptionLoginOf(provider)
    // 只有支持订阅登录的服务商才看 method；切到其他服务商一律是 API Key
    val loginMode = subscription != null && method == AddMethod.SUBSCRIPTION

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            "添加服务",
            fontSize = Type.SheetTitleSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.TextPrimary,
        )
        Spacer(Modifier.height(18.dp))

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("服务商", fontSize = Type.CaptionSize.sp) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // 订阅账号（CLAUDE_SUB / CHATGPT_SUB）不单独列出，从 Anthropic / OpenAI 的「登录订阅」进入
                Provider.entries.filter { it.pickable }.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.displayName, fontSize = Type.BodySize.sp) },
                        onClick = { provider = p; expanded = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("备注名（可选）", fontSize = Type.CaptionSize.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = fieldColors(),
        )

        if (subscription != null) {
            Spacer(Modifier.height(10.dp))
            MethodChoice(method = method, onChange = { method = it })
        }

        Spacer(Modifier.height(10.dp))
        if (loginMode) {
            Button(
                onClick = { onSubscriptionLogin(provider) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.TextPrimary,
                    contentColor = Palette.Bg,
                ),
            ) {
                Text(subscription!!.button, fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                subscription.hint,
                fontSize = Type.LabelSize.sp,
                color = Palette.TextTertiary,
                lineHeight = Type.LabelLineHeight.sp,
            )
        } else if (provider == Provider.XAI) {
            Button(
                onClick = onXaiLogin,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.TextPrimary,
                    contentColor = Palette.Bg,
                ),
            ) {
                Text("用浏览器登录 Grok", fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "会打开 accounts.x.ai，输入设备码授权。无需 API Key。",
                fontSize = Type.LabelSize.sp,
                color = Palette.TextTertiary,
                lineHeight = Type.LabelLineHeight.sp,
            )
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API Key", fontSize = Type.CaptionSize.sp) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors(),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (loginMode) "登录令牌用 AndroidKeyStore 加密后存在本机，不上传。"
            else "Key 用 AndroidKeyStore 加密后存在本机，不上传。",
            fontSize = Type.LabelSize.sp,
            color = Palette.TextTertiary,
            lineHeight = Type.LabelLineHeight.sp,
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("取消", color = Palette.TextSecondary, fontSize = Type.BodySize.sp) }
            if (provider != Provider.XAI && !loginMode) {
                Button(
                    onClick = { onConfirm(provider, name, token) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.TextPrimary,
                        contentColor = Palette.Bg,
                    ),
                ) { Text("添加", fontSize = Type.BodySize.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.TextSecondary,
    unfocusedBorderColor = Palette.Divider,
    focusedTextColor = Palette.TextPrimary,
    unfocusedTextColor = Palette.TextPrimary,
    focusedLabelColor = Palette.TextSecondary,
    unfocusedLabelColor = Palette.TextTertiary,
    cursorColor = Palette.TextPrimary,
)

// ---------------------------------------------------------------- 添加方式（1.8.0）

/** 添加方式：订阅登录或 API Key。 */
internal enum class AddMethod { SUBSCRIPTION, API_KEY }

/** 支持订阅登录的服务商的入口文案。 */
internal data class SubscriptionEntry(val button: String, val hint: String)

/** Anthropic → Claude 订阅（粘贴授权码），OpenAI → ChatGPT 订阅（设备码）；其他服务商没有订阅登录。 */
internal fun subscriptionLoginOf(provider: Provider): SubscriptionEntry? = when (provider) {
    Provider.ANTHROPIC -> SubscriptionEntry(
        button = "用浏览器登录 Claude",
        hint = "会打开 claude.ai 授权，授权后把页面上的授权码粘贴回来。适用于 Pro / Max 订阅，显示 5 小时与每周额度。",
    )
    Provider.OPENAI -> SubscriptionEntry(
        button = "用浏览器登录 ChatGPT",
        hint = "会打开 auth.openai.com，输入设备码授权。适用于 Plus / Pro 订阅，显示 Codex 的 5 小时与每周额度。",
    )
    else -> null
}

/** 「登录订阅 / API Key」二选一；样式跟随现有黑白按钮，不引入新组件库。 */
@Composable
private fun MethodChoice(method: AddMethod, onChange: (AddMethod) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHover)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(AddMethod.SUBSCRIPTION to "登录订阅", AddMethod.API_KEY to "API Key").forEach { (m, label) ->
            val selected = m == method
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Palette.Surface else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onChange(m) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = Type.CaptionSize.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) Palette.TextPrimary else Palette.TextTertiary,
                )
            }
        }
    }
}
