package ltd.usage.tracker.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.ui.theme.ProviderMarkBadge
import ltd.usage.tracker.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAccountSheet(
    onDismiss: () -> Unit,
    onConfirm: (Provider, String, String) -> Unit,
    onXaiLogin: () -> Unit,
    onSubscriptionLogin: (Provider) -> Unit = {},
    relogin: StoredAccount? = null,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        AddAccountForm(
            onDismiss, onConfirm, onXaiLogin, onSubscriptionLogin,
            initialProvider = Provider.from(relogin?.providerId ?: "") ?: Provider.DEEPSEEK,
            relogin = relogin,
        )
    }
}

/**
 * 表单正文。底部弹层在独立 Window 上，截图抓不到，截图时直接画这一段；
 * [initialProvider] / [initialMethod] 只给截图用。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AddAccountForm(
    onDismiss: () -> Unit,
    onConfirm: (Provider, String, String) -> Unit,
    onXaiLogin: () -> Unit,
    onSubscriptionLogin: (Provider) -> Unit,
    initialProvider: Provider = Provider.DEEPSEEK,
    initialMethod: AddMethod = AddMethod.API_KEY,
    relogin: StoredAccount? = null,
) {
    var provider by remember { mutableStateOf(initialProvider) }
    var name by remember { mutableStateOf(relogin?.name ?: "") }
    var token by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(initialMethod) }
    val reloginMode = relogin != null
    val subscription = subscriptionLoginOf(provider)
    val loginMode = subscription != null && method == AddMethod.SUBSCRIPTION
    val scheme = MaterialTheme.colorScheme
    val pickable = remember { Provider.entries.filter { it.pickable } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            if (reloginMode) "重新填写 API Key" else "添加服务",
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        if (!reloginMode) pickable.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { p ->
                    ProviderTile(
                        provider = p,
                        selected = p == provider,
                        onClick = { provider = p },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("备注名（可选）", fontSize = Type.CaptionSize.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors(),
        )

        if (!reloginMode && subscription != null) {
            Spacer(Modifier.height(12.dp))
            MethodChoice(method = method, onChange = { method = it })
        }

        Spacer(Modifier.height(10.dp))
        if (loginMode) {
            Button(
                onClick = { onSubscriptionLogin(provider) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(subscription!!.button, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(subscription.hint, fontSize = Type.LabelSize.sp, color = scheme.outline, lineHeight = Type.LabelLineHeight.sp)
        } else if (provider == Provider.XAI) {
            Button(
                onClick = onXaiLogin,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("用浏览器登录 Grok", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "会打开 accounts.x.ai，输入设备码授权。无需 API Key。",
                fontSize = Type.LabelSize.sp,
                color = scheme.outline,
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
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (loginMode) "登录令牌用 AndroidKeyStore 加密后存在本机，不上传。"
            else "Key 用 AndroidKeyStore 加密后存在本机，不上传。",
            fontSize = Type.LabelSize.sp,
            color = scheme.outline,
            lineHeight = Type.LabelLineHeight.sp,
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            if (provider != Provider.XAI && !loginMode) {
                Button(
                    onClick = { onConfirm(provider, name, token) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (reloginMode) "保存" else "添加", fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderTile(
    provider: Provider,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMarkBadge(
            providerId = provider.id,
            accountName = provider.displayName,
            providerName = provider.displayName,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            provider.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors()

internal enum class AddMethod { SUBSCRIPTION, API_KEY }

internal data class SubscriptionEntry(val button: String, val hint: String)

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MethodChoice(method: AddMethod, onChange: (AddMethod) -> Unit) {
    val options = listOf(AddMethod.SUBSCRIPTION to "登录订阅", AddMethod.API_KEY to "API Key")
    ButtonGroup(overflowIndicator = {}) {
        options.forEachIndexed { index, (m, label) ->
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    val shapes = if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    }
                    ToggleButton(
                        checked = m == method,
                        onCheckedChange = { onChange(m) },
                        shapes = shapes,
                        interactionSource = interaction,
                        modifier = Modifier
                            .animateWidth(interaction)
                            .weight(1f)
                            .semantics { role = Role.Tab },
                    ) { Text(label) }
                },
                menuContent = {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(m)
                        },
                    )
                },
            )
        }
    }
}
