package ltd.usage.tracker.provider

import kotlinx.coroutines.flow.Flow

/** xAI 设备码登录流程：委托给共享的 [deviceCodeFlow]。 */
class XaiLoginFlow(
    private val requestDeviceCode: () -> XaiOAuth.DeviceCode = XaiOAuth::requestDeviceCode,
    private val pollOnce: (String) -> XaiOAuth.PollResult = XaiOAuth::pollOnce,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        data class CodeReady(val userCode: String, val verificationUri: String) : Event
        data class Authorized(val tokens: XaiOAuth.Tokens) : Event
        data class Failed(val message: String) : Event
    }

    fun run(): Flow<Event> = deviceCodeFlow(
        now = now,
        requestDeviceCode = requestDeviceCode,
        expiresInSeconds = { it.expiresIn },
        intervalSeconds = { it.interval },
        onCodeReady = { Event.CodeReady(it.userCode, it.verificationUriComplete ?: it.verificationUri) },
        onAuthorized = Event::Authorized,
        onFailed = Event::Failed,
        poll = { device ->
            when (val r = pollOnce(device.deviceCode)) {
                is XaiOAuth.PollResult.Authorized -> DevicePollStep.Done(r.tokens)
                is XaiOAuth.PollResult.Pending -> DevicePollStep.Pending
                is XaiOAuth.PollResult.SlowDown -> DevicePollStep.SlowDown
                is XaiOAuth.PollResult.Failed -> DevicePollStep.Failed(r.message)
            }
        },
    )
}
