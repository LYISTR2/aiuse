package ltd.usage.tracker.ui.login

import androidx.compose.runtime.Composable
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.XaiLogin

// ---------------------------------------------------------------- xAI 登录
//
// 1.8.0 起对话框外壳与设备码正文挪到 OAuthLoginDialog.kt，Claude / ChatGPT 共用；
// xAI 这里只是一层薄封装，文案与布局与 1.7.1 完全相同（06 / 09 截图逐像素比对过）。

@Composable
internal fun XaiLoginDialog(login: XaiLogin, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OAuthLoginDialog(title = "登录 Grok", onCancel = onCancel, titleProviderId = Provider.XAI.id) {
        DeviceCodeBody(
            userCode = login.userCode,
            verificationUri = login.verificationUri,
            status = login.status,
            onOpen = { openInBrowser(context, login.verificationUri) },
            onCopy = { copyToClipboard(context, login.verificationUri) },
        )
    }
}

/** 截图验证用：与对话框共用 [DeviceCodeBody]。 */
@Composable
internal fun XaiLoginCardPreview(login: XaiLogin) {
    OAuthLoginCardPreview(title = "登录 Grok", titleProviderId = Provider.XAI.id) {
        DeviceCodeBody(
            userCode = login.userCode,
            verificationUri = login.verificationUri,
            status = login.status,
            onOpen = {},
            onCopy = {},
        )
    }
}

// ---------------------------------------------------------------- 工具

/**
 * 打开浏览器。
 *
 * 修复点：
 *  1. 先 resolveActivity 判断有没有可用浏览器，避免 ActivityNotFoundException
 *  2. 加 FLAG_ACTIVITY_NEW_TASK —— context 可能是非 Activity（如 Dialog 包装），
 *     缺这个 flag 会抛 AndroidRuntimeException，而之前被 runCatching 吞掉了
 *  3. 失败时降级为「复制链接」，而不是静默无反应
 */
internal fun openInBrowser(
    context: android.content.Context,
    url: String,
    // 可注入，便于测试模拟「无浏览器」；生产调用走默认实现
    launch: (android.content.Intent) -> Unit = { context.startActivity(it) },
): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    // 直接尝试启动，不用 resolveActivity 当门槛：
    // Android 11+ 的包可见性限制会让 resolveActivity 在未声明 <queries> 时误报 null，
    // 明明有浏览器却打不开。以实际启动结果为准。
    val error = runCatching { launch(intent) }.exceptionOrNull()
    if (error == null) return true

    // 没有浏览器或启动失败：降级为复制，用户仍可手动粘贴到浏览器
    copyToClipboard(context, url)
    return false
}

internal fun copyToClipboard(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("授权链接", text))
    }
}
