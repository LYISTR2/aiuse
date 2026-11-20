package ltd.usage.tracker.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 设备码轮询流程（从 ViewModel 挪到 XaiLoginFlow 后的行为护栏）。 */
class XaiLoginFlowTest {

    private val device = XaiOAuth.DeviceCode(
        deviceCode = "dc", userCode = "ABCD-EFGH",
        verificationUri = "https://x/device", verificationUriComplete = "https://x/device?c=ABCD",
        expiresIn = 600, interval = 0,
    )
    private val tokens = XaiOAuth.Tokens("at", "rt", 3600)

    @Test
    fun pendingThenAuthorized() = runBlocking {
        val polls = ArrayDeque(listOf<XaiOAuth.PollResult?>(XaiOAuth.PollResult.Pending, null, XaiOAuth.PollResult.Authorized(tokens)))
        val events = XaiLoginFlow(
            requestDeviceCode = { device },
            pollOnce = { polls.removeFirst() ?: error("网络异常") },
        ).run().toList()
        assertEquals(
            listOf(
                XaiLoginFlow.Event.CodeReady("ABCD-EFGH", "https://x/device?c=ABCD"),
                XaiLoginFlow.Event.Authorized(tokens),
            ),
            events,
        )
    }

    @Test
    fun deviceCodeFailure() = runBlocking {
        val events = XaiLoginFlow(requestDeviceCode = { error("boom") }).run().toList()
        assertEquals(listOf(XaiLoginFlow.Event.Failed("获取设备码失败: boom")), events)
    }

    @Test
    fun deniedAndTimeout() = runBlocking {
        val denied = XaiLoginFlow(
            requestDeviceCode = { device },
            pollOnce = { XaiOAuth.PollResult.Failed("授权被拒绝") },
        ).run().toList()
        assertEquals(XaiLoginFlow.Event.Failed("授权被拒绝"), denied.last())

        var t = 0L
        val timeout = XaiLoginFlow(
            requestDeviceCode = { device.copy(expiresIn = 1) },
            pollOnce = { t += 600; XaiOAuth.PollResult.Pending },
            now = { t },
        ).run().toList()
        assertEquals(XaiLoginFlow.Event.Failed("登录超时，请重试"), timeout.last())
    }

    @Test
    fun cancellationDuringDeviceCodeIsNotReportedAsFailure() = runBlocking {
        try {
            XaiLoginFlow(requestDeviceCode = { throw CancellationException("cancelled") }).run().toList()
            fail("取消应向上抛出，而不是变成 Failed")
        } catch (_: CancellationException) {
        }
    }
}
