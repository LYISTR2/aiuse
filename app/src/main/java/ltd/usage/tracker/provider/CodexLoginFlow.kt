package ltd.usage.tracker.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * ChatGPT（Codex）设备码登录流程：申请设备码 → 按 interval 轮询 → 换令牌。
 *
 * 结构与 [XaiLoginFlow] 相同：冷流，以 Authorized 或 Failed 结束；
 * 等待用调用方协程的 delay，调用方 cancel 即停止轮询（与 1.7.1 的 xAI 取消修复同一语义）。
 * 依赖可注入，便于测试；生产走 [CodexOAuth]。
 */
class CodexLoginFlow(
    private val requestDeviceCode: () -> CodexOAuth.DeviceCode = CodexOAuth::requestDeviceCode,
    private val pollOnce: (CodexOAuth.DeviceCode) -> CodexOAuth.PollResult = CodexOAuth::pollOnce,
    private val exchange: (String, String) -> CodexOAuth.Login = CodexOAuth::exchange,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        data class CodeReady(val userCode: String, val verificationUri: String) : Event
        data class Authorized(val login: CodexOAuth.Login) : Event
        data class Failed(val message: String) : Event
    }

    fun run(): Flow<Event> = flow {
        val device = withContext(Dispatchers.IO) { runCatching { requestDeviceCode() } }
            .getOrElse { e ->
                emit(Event.Failed(failure("获取设备码失败", e)))
                return@flow
            }
        emit(Event.CodeReady(device.userCode, device.verificationUri))

        val deadline = now() + device.expiresIn * 1000L
        val interval = device.interval * 1000L
        while (now() < deadline) {
            delay(interval)
            // 单次轮询的网络异常不算失败，下一轮再试
            when (val r = withContext(Dispatchers.IO) { runCatching { pollOnce(device) }.getOrNull() }) {
                is CodexOAuth.PollResult.Authorized -> {
                    val login = withContext(Dispatchers.IO) {
                        runCatching { exchange(r.authorizationCode, r.codeVerifier) }
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        emit(Event.Failed(failure("换取令牌失败", e)))
                        return@flow
                    }
                    emit(Event.Authorized(login))
                    return@flow
                }
                is CodexOAuth.PollResult.Pending, null -> Unit
                is CodexOAuth.PollResult.Failed -> {
                    emit(Event.Failed(r.message))
                    return@flow
                }
            }
        }
        emit(Event.Failed("登录超时，请重试"))
    }

    /** OAuthException 的消息已是中文人话，直接用；其他异常加前缀。 */
    private fun failure(prefix: String, e: Throwable): String =
        if (e is OAuthException) e.message.orEmpty() else "$prefix: ${e.message ?: e.javaClass.simpleName}"
}
