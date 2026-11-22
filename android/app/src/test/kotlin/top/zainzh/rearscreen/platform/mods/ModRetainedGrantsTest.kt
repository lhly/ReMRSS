package top.zainzh.rearscreen.platform.mods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModRetainedGrantsTest {
    private val prior = InstalledMod(
        id = "mod.example", name = "Example", authorName = "Example", authorFingerprint = "same",
        logicalSessionId = "session", version = "1", versionCode = 1, dataVersion = 1, enabled = true,
        requiredCapabilities = listOf(ModCapability("becomesOptional", 1)),
        optionalCapabilities = listOf(ModCapability("keep", 1), ModCapability("changed", 1),
            ModCapability("removed", 1), ModCapability("notGranted", 1)),
        grantedCapabilities = setOf("keep", "changed", "removed", "becomesOptional"),
    )

    private fun candidate(author: String = "same") = VerifiedModPackage(
        id = prior.id, name = prior.name, version = "2", versionCode = 2, authorName = prior.authorName,
        authorFingerprint = author, platformApi = 1, minimumAndroidApi = 36, requiredCapabilities = emptyList(),
        optionalCapabilities = listOf(ModCapability("keep", 1), ModCapability("changed", 2),
            ModCapability("new", 1), ModCapability("notGranted", 1), ModCapability("becomesOptional", 1)),
        requiredPermissions = emptyList(), optionalPermissions = emptyList(), settingsScene = JSONObject(),
        rearScene = null, stateSchema = JSONObject(), configSchema = JSONObject(), dataVersion = 1,
        runtime = ModRuntimeKind.NONE, reducerSource = null, publicKeyPem = byteArrayOf(),
        signedManifest = byteArrayOf(), payloads = emptyMap(),
    )

    @Test fun onlyUnchangedAlreadyGrantedOptionalCapabilitiesArePreselected() {
        assertEquals(setOf("keep"), retainedOptionalGrants(prior, candidate()))
    }

    @Test fun freshInstallOrDifferentAuthorRetainsNothing() {
        assertEquals(emptySet<String>(), retainedOptionalGrants(null, candidate()))
        assertEquals(emptySet<String>(), retainedOptionalGrants(prior, candidate("different")))
    }
}
