package ltd.usage.tracker.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * xAI 设备码登录的完整流程：申请设备码 → 按 interval 轮询 → 授权 / 失败 / 超时。
 *
 * 从 ViewModel 挪出来，ViewModel 只收集 [Event] 更新界面、落库。
 * 网络调用在 IO 线程，等待用调用方协程的 delay（取消即停止轮询）。
 * 三个依赖可注入，便于测试；生产走 [XaiOAuth]。
 */
class XaiLoginFlow(
    private val requestDeviceCode: () -> XaiOAuth.DeviceCode = XaiOAuth::requestDeviceCode,
    private val pollOnce: (String) -> XaiOAuth.PollResult = XaiOAuth::pollOnce,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        /** 拿到设备码，界面显示 [userCode] 与授权地址。 */
        data class CodeReady(val userCode: String, val verificationUri: String) : Event
        data class Authorized(val tokens: XaiOAuth.Tokens) : Event
        /** 任何失败（含设备码申请失败、授权被拒、超时），[message] 直接给用户看。 */
        data class Failed(val message: String) : Event
    }

    /** 冷流：每次 collect 走一遍完整登录；以 Authorized 或 Failed 结束。 */
    fun run(): Flow<Event> = flow {
        val device = withContext(Dispatchers.IO) { runCatching { requestDeviceCode() } }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                emit(Event.Failed("获取设备码失败: ${e.message ?: e.javaClass.simpleName}"))
                return@flow
            }
        emit(Event.CodeReady(device.userCode, device.verificationUriComplete ?: device.verificationUri))

        val deadline = now() + device.expiresIn * 1000L
        var interval = device.interval * 1000L
        while (now() < deadline) {
            delay(interval)
            // 单次轮询的网络异常不算失败，下一轮再试
            when (val r = withContext(Dispatchers.IO) {
                runCatching { pollOnce(device.deviceCode) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }) {
                is XaiOAuth.PollResult.Authorized -> {
                    emit(Event.Authorized(r.tokens))
                    return@flow
                }
                is XaiOAuth.PollResult.Pending -> Unit
                is XaiOAuth.PollResult.SlowDown -> interval += 5000
                is XaiOAuth.PollResult.Failed -> {
                    emit(Event.Failed(r.message))
                    return@flow
                }
                null -> Unit
            }
        }
        emit(Event.Failed("登录超时，请重试"))
    }
}
