package ltd.usage.tracker.ui.add

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        containerColor = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        AddAccountForm(
            onDismiss, onConfirm, onXaiLogin, onSubscriptionLogin,
            initialProvider = Provider.from(relogin?.providerId ?: "") ?: Provider.DEEPSEEK,
            relogin = relogin,
        )
    }
}

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
    var showToken by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(initialMethod) }

    val reloginMode = relogin != null
    val subscription = subscriptionLoginOf(provider)
    val loginMode = subscription != null && method == AddMethod.SUBSCRIPTION
    val scheme = MaterialTheme.colorScheme
    val pickable = remember { Provider.entries.filter { it.pickable } }
    val filtered = remember(pickable, query) {
        val q = query.trim()
        if (q.isEmpty()) pickable
        else pickable.filter { it.displayName.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            if (reloginMode) "重新填写 API Key" else "添加服务",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (reloginMode) "更新「${relogin.name}」的访问凭据" else "选择要追踪余额或订阅额度的 AI 服务",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (!reloginMode) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索服务商…", fontSize = Type.CaptionSize.sp) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))

            filtered.chunked(2).forEach { row ->
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
        }

        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("备注名（可选）", fontSize = Type.CaptionSize.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )

        if (!reloginMode && subscription != null) {
            Spacer(Modifier.height(12.dp))
            MethodChoice(method = method, onChange = { method = it })
        }

        Spacer(Modifier.height(12.dp))
        if (loginMode) {
            Button(
                onClick = { onSubscriptionLogin(provider) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(subscription.button, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                subscription.hint,
                fontSize = Type.LabelSize.sp,
                color = scheme.onSurfaceVariant,
                lineHeight = Type.LabelLineHeight.sp,
            )
        } else if (provider == Provider.XAI) {
            Button(
                onClick = onXaiLogin,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("用浏览器登录 Grok", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "会打开 accounts.x.ai，输入设备码授权。无需 API Key。",
                fontSize = Type.LabelSize.sp,
                color = scheme.onSurfaceVariant,
                lineHeight = Type.LabelLineHeight.sp,
            )
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API Key", fontSize = Type.CaptionSize.sp) },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showToken) "隐藏" else "显示",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainerHigh.copy(alpha = 0.7f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (loginMode) "登录令牌用 AndroidKeyStore 加密后存在本机，不上传。"
                    else "Key 用 AndroidKeyStore 加密后存在本机，不上传。",
                    fontSize = Type.LabelSize.sp,
                    color = scheme.onSurfaceVariant,
                    lineHeight = Type.LabelLineHeight.sp,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("取消")
            }
            if (provider != Provider.XAI && !loginMode) {
                Button(
                    onClick = { onConfirm(provider, name, token) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (reloginMode) "保存" else "添加", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProviderTile(
    provider: Provider,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMarkBadge(
                providerId = provider.id,
                accountName = provider.displayName,
                providerName = provider.displayName,
                size = 34.dp,
                logoSize = 18.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                provider.displayName,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

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

@Composable
private fun MethodChoice(method: AddMethod, onChange: (AddMethod) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(AddMethod.SUBSCRIPTION to "登录订阅", AddMethod.API_KEY to "API Key")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (m, label) ->
                val isSelected = m == method
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) scheme.primary else Color.Transparent)
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable { onChange(m) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
