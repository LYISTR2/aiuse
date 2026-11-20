package ltd.usage.tracker.ui.theme

import ltd.usage.tracker.data.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogoTest {

    @Test
    fun everyProviderHasLogoMapping() {
        val missing = Provider.entries.filter { providerLogoSpec(it.id) == null }
        assertEquals("全部服务商都应有 logo 映射，缺失: $missing", emptyList<Provider>(), missing)
    }

    @Test
    fun unknownProviderFallsBackToLetter() {
        assertNull(providerLogoSpec("unknown-vendor"))
        assertNull(providerLogoVariant("unknown-vendor", isDark = true))
        assertEquals("?", providerLetter("   ", "  "))
    }

    @Test
    fun subscriptionsShareVendorLogo() {
        assertEquals(providerLogoSpec(Provider.OPENAI.id), providerLogoSpec(Provider.CHATGPT_SUB.id))
        assertEquals(providerLogoSpec(Provider.ANTHROPIC.id), providerLogoSpec(Provider.CLAUDE_SUB.id))
    }

    @Test
    fun variantTableMatchesContrastPolicy() {
        fun v(id: String, dark: Boolean) = providerLogoVariant(id, dark)
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.DEEPSEEK.id, false))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.DEEPSEEK.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.ANTHROPIC.id, false))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.CLAUDE_SUB.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.KIMI.id, false))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.KIMI.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.ZHIPU.id, false))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.ZHIPU.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.MISTRAL.id, false))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.MISTRAL.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.SILICONFLOW.id, false))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.SILICONFLOW.id, true))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.NOVITA.id, false))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.NOVITA.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.DEEPINFRA.id, false))
        assertEquals(ProviderLogoVariant.MONO, v(Provider.DEEPINFRA.id, true))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.STEPFUN.id, false))
        assertEquals(ProviderLogoVariant.COLOR, v(Provider.STEPFUN.id, true))
        for (p in listOf(Provider.OPENAI, Provider.CHATGPT_SUB, Provider.XAI, Provider.OPENROUTER, Provider.OPENCODE_GO, Provider.COMMAND_CODE)) {
            assertEquals(p.id, ProviderLogoVariant.MONO, v(p.id, false))
            assertEquals(p.id, ProviderLogoVariant.MONO, v(p.id, true))
        }
        Provider.entries.forEach {
            assertNotNull(it.id, v(it.id, false))
            assertNotNull(it.id, v(it.id, true))
        }
        assertTrue(providerLogoSpec(Provider.OPENAI.id)!!.colorRes == null)
        assertTrue(providerLogoSpec(Provider.DEEPSEEK.id)!!.colorRes != null)
    }
}
