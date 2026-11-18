package top.zainzh.rearscreen.platform.mods

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.zainzh.rearscreen.platform.RearCapabilityCore
import java.io.ByteArrayOutputStream

/** Feed this check v1/v2 archives made from the extracted author kit via mrssUpdateFixtureDir. */
@RunWith(AndroidJUnit4::class)
class Rc6ModDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext

    @Test fun standaloneKitUpdateAndPreview() {
        val assets = instrument.context.assets
        assumeTrue("The standalone-kit acceptance pair is not bundled in ordinary AndroidTest builds",
            assets.list("platform/mods").orEmpty().contains("rc6-rear-info-v2.zip"))
        val oldZip = assets.open("platform/mods/rc6-rear-info-v1.zip").use { it.readBytes() }
        val newZip = assets.open("platform/mods/rc6-rear-info-v2.zip").use { it.readBytes() }
        val old = ModPackageReader.read(oldZip.inputStream())
        val new = ModPackageReader.read(newZip.inputStream())
        val initialDecision = ModInstallDecision(old.authorFingerprint, setOf("rear.scene"))
        val upgradeDecision = ModInstallDecision(new.authorFingerprint, setOf("rear.scene"))
        ModPackageStoreTestSession.create(app, "rear-info-${System.nanoTime()}").use { store ->
            val v1 = store.install(oldZip, initialDecision)
            assertTrue(store.setEnabled(v1.id, true).enabled)
            val model = requireNotNull(store.model(v1.id))
            val saved = store.commitModel(v1.id, model.revision, model.state, JSONObject().put("label", "保留设置"))
            val v2 = store.install(newZip, upgradeDecision)
            assertEquals(2, v2.versionCode)
            assertEquals(v1.logicalSessionId, v2.logicalSessionId)
            assertTrue(v2.enabled)
            assertEquals(setOf("rear.scene"), v2.grantedCapabilities)
            val restored = requireNotNull(store.model(v2.id))
            assertEquals(saved.revision, restored.revision)
            assertEquals("保留设置", restored.config.getString("label"))
        }

        // Guard installs only a fresh debug host. Refuse touching an existing package even there.
        check(ModPackageStore.installed(app, new.id) == null)
        ModPackageStore.install(app, newZip, upgradeDecision)
        ModPackageStore.setEnabled(app, new.id, true)
        try {
            ActivityScenario.launch<ModConfigActivity>(
                Intent(app, ModConfigActivity::class.java).putExtra("mod_id", new.id)
            ).use {
                awaitText("请在 Mod 管理中授予背屏信息权限")
                ModPackageStore.setCapabilityGrant(app, new.id, "rear.info", 1, true)
                awaitText("未检测到可识别的背屏")
            }

            val receipts = Bundle()
            ActivityScenario.launch<ModPreviewActivity>(
                Intent(app, ModPreviewActivity::class.java).putExtra("mod_id", new.id)
            ).use { preview ->
                awaitText("本机预览不读取实测数据")
                val cover = compose.onNodeWithText("模拟临时覆盖")
                cover.performScrollTo().performClick()
                awaitText("关闭覆盖后返回 Mod")
                preview.recreate()
                awaitText("关闭覆盖后返回 Mod")
                cover.performScrollTo().performClick()
                awaitText("正在充电 · 示例")
                capturePreview(receipts, "rc6-preview-charging", "正在充电 · 示例")
                cover.performScrollTo().performClick()
                awaitText("本机预览不读取实测数据")
                compose.onNodeWithText("主题").performScrollTo().performClick()
                compose.onNodeWithText("主题").performClick()
                compose.onNodeWithText("字号").performScrollTo().performClick()
                compose.onNodeWithText("旋转").performScrollTo().performClick()
                capturePreview(receipts, "rc6-preview-restored", "本机预览不读取实测数据")
            }
            assertFalse(RearCapabilityCore.hasActiveSceneSessions())
            instrument.addResults(receipts)
        } finally {
            ModPackageStore.uninstall(app, new.id)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capturePreview(receipts: Bundle, name: String, visibleLabel: String) {
        compose.onNodeWithText(visibleLabel).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val full = requireNotNull(instrument.uiAutomation.takeScreenshot())
        val thumbnail = Bitmap.createScaledBitmap(full, 420, full.height * 420 / full.width, true)
        try {
            val bytes = ByteArrayOutputStream()
            check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 81, bytes))
            receipts.putString(name, Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        } finally {
            thumbnail.recycle()
            full.recycle()
        }
    }
}
