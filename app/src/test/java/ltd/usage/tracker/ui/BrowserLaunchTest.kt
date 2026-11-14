package ltd.usage.tracker.ui

import ltd.usage.tracker.ui.login.copyToClipboard
import ltd.usage.tracker.ui.login.openInBrowser
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「打开授权页面」的失败兜底。
 *
 * 原始 bug：onClick 里 `runCatching {}` 静默吞异常，无浏览器时点击完全无反应。
 * 这里用注入的 launch 模拟启动失败，锁住降级行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserLaunchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://accounts.x.ai/oauth2/device?user_code=TEST-CODE"

    private fun clipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private fun clearClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }

    @Test
    fun `launch failure falls back to clipboard and reports false`() {
        clearClipboard()
        val opened = openInBrowser(context, url) {
            throw ActivityNotFoundException("no browser")
        }
        assertFalse("should report not opened", opened)
        assertEquals(url, clipboardText())
    }

    @Test
    fun `successful launch does not touch clipboard`() {
        clearClipboard()
        val opened = openInBrowser(context, url) { /* 模拟成功唤起浏览器 */ }
        assertTrue(opened)
        // 不能把链接写进剪贴板（成功唤起时无需兜底）
        assertNotEquals("clipboard must stay untouched", url, clipboardText())
    }

    @Test
    fun `failure never propagates exception to caller`() {
        // 关键回归点：异常绝不能被抛到 UI 层（否则点击会崩溃或静默失败）
        openInBrowser(context, url) { throw IllegalStateException("boom") }
        // 走到这里没抛即通过
    }

    @Test
    fun `copyToClipboard writes the exact url`() {
        clearClipboard()
        copyToClipboard(context, url)
        assertEquals(url, clipboardText())
    }
}
