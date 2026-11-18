package top.zainzh.rearscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AiRearWallpaperTemplateTest {
    @Test fun mapsOnlyTheTwoSignedAssetPaths() {
        val pixels = byteArrayOf(1, 2, 3)
        val six = AiRearWallpaperTemplate.fromVerifiedAssets(mapOf(AiRearWallpaperTemplate.SIX_APPS to pixels))
        val list = AiRearWallpaperTemplate.fromVerifiedAssets(mapOf(AiRearWallpaperTemplate.APPS_AND_LIST to pixels))
        assertFalse(six.allApps)
        assertTrue(list.allApps)
        assertArrayEquals(pixels, six.background)
    }

    @Test fun cannotInferTemplateFromAnUnrelatedOrAmbiguousArchive() {
        val bytes = byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) {
            AiRearWallpaperTemplate.fromVerifiedAssets(mapOf("assets/cover.png" to bytes))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRearWallpaperTemplate.fromVerifiedAssets(mapOf(
                AiRearWallpaperTemplate.SIX_APPS to bytes, AiRearWallpaperTemplate.APPS_AND_LIST to bytes))
        }
    }
}
