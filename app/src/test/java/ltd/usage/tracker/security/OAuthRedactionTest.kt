package ltd.usage.tracker.security

import ltd.usage.tracker.provider.CodexOAuth
import ltd.usage.tracker.provider.XaiOAuth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthRedactionTest {

    @Test
    fun xaiTokensToStringHidesSecrets() {
        val access = "xai-access-SECRET-abcdef"
        val refresh = "xai-refresh-SECRET-987654"
        val s = XaiOAuth.Tokens(access, refresh, 3600).toString()
        assertFalse(s.contains(access))
        assertFalse(s.contains(refresh))
        assertTrue(s.contains("***(${access.length})"))
        assertTrue(s.contains("***(${refresh.length})"))
    }

    @Test
    fun xaiDeviceCodeToStringHidesDeviceCode() {
        val dc = "device-code-SECRET-abcdef"
        val s = XaiOAuth.DeviceCode(
            deviceCode = dc,
            userCode = "ABCD-EFGH",
            verificationUri = "https://x/device",
            verificationUriComplete = "https://x/device?c=ABCD-EFGH",
            expiresIn = 600,
            interval = 5,
        ).toString()
        assertFalse(s.contains(dc))
        assertTrue(s.contains("ABCD-EFGH"))
        assertTrue(s.contains("***(${dc.length})"))
    }

    @Test
    fun codexDeviceCodeToStringHidesDeviceAuthId() {
        val id = "device-auth-id-SECRET-xyz"
        val s = CodexOAuth.DeviceCode(
            deviceAuthId = id,
            userCode = "WXYZ-0001",
            verificationUri = "https://chatgpt.com/codex/device",
            interval = 5,
        ).toString()
        assertFalse(s.contains(id))
        assertTrue(s.contains("WXYZ-0001"))
    }

    @Test
    fun codexAuthorizedToStringHidesCodeAndVerifier() {
        val code = "authz-code-SECRET-abcdef"
        val verifier = "pkce-verifier-SECRET-987654"
        val s = CodexOAuth.PollResult.Authorized(code, verifier).toString()
        assertFalse(s.contains(code))
        assertFalse(s.contains(verifier))
    }
}
