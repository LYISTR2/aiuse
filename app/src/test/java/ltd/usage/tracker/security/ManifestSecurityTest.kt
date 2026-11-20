package ltd.usage.tracker.security

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestSecurityTest {

    @Test
    fun backupAndCleartextAreDisabled() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val flags = app.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    @Test
    fun onlyLauncherActivityIsExported() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = app.packageManager
        val packageInfo = pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS)
        // debug 测试依赖会注入 androidx 的 exported Activity，只检查本包组件
        val exportedActivities = packageInfo.activities.orEmpty()
            .filter { it.exported && it.name.startsWith("ltd.usage.tracker") }
        assertEquals(1, exportedActivities.size)
        assertTrue(exportedActivities.single().name.endsWith(".MainActivity"))
        val receivers = packageInfo.receivers
        val services = packageInfo.services
        val providers = packageInfo.providers
        assertTrue(receivers.isNullOrEmpty() || receivers.none { it.exported && it.name.startsWith("ltd.usage.tracker") })
        assertTrue(services.isNullOrEmpty() || services.none { it.exported && it.name.startsWith("ltd.usage.tracker") })
        assertTrue(providers.isNullOrEmpty() || providers.none { it.exported && it.name.startsWith("ltd.usage.tracker") })
    }

    @Test
    fun networkSecurityConfigHasNoCleartextExceptions() {
        val xml = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        assertFalse(xml.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(xml.contains("<domain-config"))
        assertTrue(xml.contains("cleartextTrafficPermitted=\"false\""))
    }

}
