package top.zainzh.rearscreen.platform.mods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModCastActivationSourceTest {
    private val mod = InstalledMod("mod-a", "A", "author", "fingerprint", "session", "1", 1, 1,
        true, emptyList(), emptyList(), emptySet())
    private val effect = ModCastStartEffect("cast", "VIRTUAL")

    @Test fun mainAndRearClicksDispatchOnlyToTheirExactLiveSurface() {
        val main = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "main-instance")
        val rear = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.REAR_SCENE, "rear-instance", "scene-1", 7)
        var mainCalls = 0
        var rearCalls = 0
        val mainConsumer = consumer { mainCalls++ }
        val rearConsumer = consumer { rearCalls++ }
        val mainRegistration = ModCastActivationSources.register(main, mainConsumer)
        val rearRegistration = ModCastActivationSources.register(rear, rearConsumer)

        assertTrue(ModCastActivationSources.dispatch(main, mod, "event-main", effect) { _, _, _ -> })
        assertTrue(ModCastActivationSources.dispatch(rear, mod, "event-rear", effect) { _, _, _ -> })
        assertEquals(1, mainCalls)
        assertEquals(1, rearCalls)

        mainRegistration.close()
        rearRegistration.close()
    }

    @Test fun staleRebuiltOrWrongModSourceCannotReceiveTheEffectOrResult() {
        val old = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "old-instance")
        val rebuilt = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "new-instance")
        var calls = 0
        val rebuiltConsumer = consumer { calls++ }
        val registration = ModCastActivationSources.register(rebuilt, rebuiltConsumer)
        var code: String? = null

        assertFalse(ModCastActivationSources.dispatch(old, mod, "event", effect) { accepted, reason, _ ->
            assertFalse(accepted); code = reason
        })
        assertEquals("E_CAST_SOURCE_STALE", code)
        assertFalse(ModCastActivationSources.isRegistered(rebuilt, "another-mod"))
        assertEquals(0, calls)
        registration.close()
        assertFalse(ModCastActivationSources.isRegistered(rebuilt))
    }

    @Test fun invalidCrossSurfaceIdentityCannotBeRegistered() {
        val malformed = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.REAR_SCENE, "rear", null, null)
        assertFalse(malformed.isWellFormed())
        assertFalse(runCatching { ModCastActivationSources.register(malformed, consumer {}) }.isSuccess)
    }

    @Test fun sourceStaysInsideTheSingleUseModAndWorkerEpochActivation() {
        val source = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "settings-1")
        val gesture = ModUserGesture(mod.id, 12L, 100L, source)
        assertEquals(source, gesture.castSource)
        assertFalse(gesture.consume("other-mod", 12L, 101L))
        assertFalse(gesture.consume(mod.id, 13L, 101L))
        assertTrue(gesture.consume(mod.id, 12L, 101L))
        assertFalse(gesture.consume(mod.id, 12L, 102L))
    }

    @Test fun oldSettingsInstanceCannotUnregisterItsReplacementAfterRecreation() {
        val source = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "restored-instance")
        var oldCalls = 0
        var newCalls = 0
        val oldConsumer = consumer { oldCalls++ }
        val newConsumer = consumer { newCalls++ }
        val oldRegistration = ModCastActivationSources.register(source, oldConsumer)
        val newRegistration = ModCastActivationSources.register(source, newConsumer)

        oldRegistration.close()
        assertTrue(ModCastActivationSources.dispatch(source, mod, "event", effect) { _, _, _ -> })
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)

        newRegistration.close()
        assertFalse(ModCastActivationSources.isRegistered(source))
    }

    @Test fun completionCarriesTheExactRequestEpoch() {
        val source = ModCastActivationSource(mod.id, ModCastActivationSource.Surface.MAIN_SETTINGS, "settings")
        var completedRequest: String? = null
        var currentRequest = "request-old"
        val consumer = object : ModHostEffectRouter.Consumer {
            override fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) = Unit
            override fun requestCastWithResult(mod: InstalledMod, eventId: String, effect: ModCastStartEffect,
                source: ModCastActivationSource, complete: (Boolean, String?, JSONObject?) -> Unit) = Unit
            override fun completeCast(source: ModCastActivationSource, requestId: String, accepted: Boolean,
                code: String?, result: JSONObject?) { completedRequest = requestId }
            override fun isCastRequestCurrent(source: ModCastActivationSource, requestId: String) = requestId == currentRequest
        }
        val registration = ModCastActivationSources.register(source, consumer)
        try {
            assertFalse(ModCastActivationSources.complete(source, "", false, "E_USER_CANCELLED"))
            assertTrue(ModCastActivationSources.isRequestCurrent(source, "request-old"))
            currentRequest = "request-new"
            assertFalse("a replacement request cannot inherit the old focus gate",
                ModCastActivationSources.isRequestCurrent(source, "request-old"))
            assertTrue(ModCastActivationSources.isRequestCurrent(source, "request-new"))
            assertTrue(ModCastActivationSources.complete(source, "request-new", false, "E_USER_CANCELLED"))
            assertEquals("request-new", completedRequest)
        } finally { registration.close() }
    }

    private fun consumer(onCall: () -> Unit) = object : ModHostEffectRouter.Consumer {
        override fun request(mod: InstalledMod, eventId: String, effect: ModRearOpenEffect) = Unit
        override fun requestCastWithResult(mod: InstalledMod, eventId: String, effect: ModCastStartEffect,
            source: ModCastActivationSource, complete: (Boolean, String?, JSONObject?) -> Unit) {
            onCall()
            complete(true, null, JSONObject().put("source", source.surface.name))
        }
    }
}
