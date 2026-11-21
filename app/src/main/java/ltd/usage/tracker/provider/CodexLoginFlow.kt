package ltd.usage.tracker.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** ChatGPT（Codex）设备码登录流程：委托给共享的 [deviceCodeFlow]。 */
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

    fun run(): Flow<Event> = deviceCodeFlow(
        now = now,
        requestDeviceCode = requestDeviceCode,
        expiresInSeconds = { it.expiresIn },
        intervalSeconds = { it.interval },
        onCodeReady = { Event.CodeReady(it.userCode, it.verificationUri) },
        onAuthorized = Event::Authorized,
        onFailed = Event::Failed,
        poll = { device ->
            when (val r = pollOnce(device)) {
                is CodexOAuth.PollResult.Authorized -> try {
                    DevicePollStep.Done(exchange(r.authorizationCode, r.codeVerifier))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val msg = if (e is OAuthException) e.message.orEmpty()
                    else "换取令牌失败: ${e.message ?: e.javaClass.simpleName}"
                    DevicePollStep.Failed(msg)
                }
                is CodexOAuth.PollResult.Pending -> DevicePollStep.Pending
                is CodexOAuth.PollResult.Failed -> DevicePollStep.Failed(r.message)
            }
        },
    )
}
