package top.zainzh.rearscreen.platform.mods

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import top.zainzh.rearscreen.AiRearWallpaperTemplate

/** Test-only authorship. The private key never leaves memory. */
internal fun signModTestArchive(manifest: JSONObject, entries: MutableMap<String, ByteArray>): ByteArray {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    manifest.getJSONObject("author").put("keyFingerprint", manifestDigest(pair.public.encoded))
    val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
    val signed = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(manifestBytes) }
    val publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP) +
        "\n-----END PUBLIC KEY-----\n"
    entries["manifest.json"] = manifestBytes
    entries["manifest.sig"] = signed.sign()
    entries["author.pub"] = publicPem.toByteArray(Charsets.US_ASCII)
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

internal fun wallpaperNavigationTestArchive(baseArchive: ByteArray): ByteArray {
    val entries = readModTestEntries(baseArchive)
    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        .put("id", "platform.route-wallpaper").put("name", "Route Test Wallpaper")
    val background = Bitmap.createBitmap(240, 148, Bitmap.Config.ARGB_8888)
    val image = try {
        background.eraseColor(Color.rgb(49, 78, 112))
        ByteArrayOutputStream().also { check(background.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    } finally {
        background.recycle()
    }
    val path = AiRearWallpaperTemplate.APPS_AND_LIST
    entries[path] = image
    manifest.getJSONArray("payloads")
        .put(JSONObject().put("path", path).put("bytes", image.size).put("sha256", manifestDigest(image)))
    return signModTestArchive(manifest, entries)
}

internal fun optionalRearSceneTestArchive(baseArchive: ByteArray): ByteArray {
    val entries = readModTestEntries(baseArchive)
    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
    val capabilities = manifest.getJSONObject("capabilities")
    val required = capabilities.getJSONArray("required")
    check(required.length() == 1 && required.getJSONObject(0).getString("name") == "rear.scene")
    capabilities.getJSONArray("optional").put(required.remove(0))
    return signModTestArchive(manifest, entries)
}

internal fun configDecisionTestArchive(baseArchive: ByteArray): ByteArray {
    val entries = readModTestEntries(baseArchive)
    val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
    val path = manifest.getJSONObject("scenes").getString("settings")
    val scene = JSONObject(entries.getValue(path).toString(Charsets.UTF_8))
    scene.getJSONObject("root").getJSONArray("children").put(JSONObject()
        .put("type", "ui.confirm").put("label", "确认递增").put("title", "要增加计数吗？")
        .put("message", "确认后按已保存的步长递增；取消不改变计数。".repeat(5))
        .put("onConfirm", JSONObject().put("type", "event").put("name", "counter.increment")))
    val payload = scene.toString().toByteArray(Charsets.UTF_8)
    entries[path] = payload
    val payloads = manifest.getJSONArray("payloads")
    for (i in 0 until payloads.length()) {
        val entry = payloads.getJSONObject(i)
        if (entry.getString("path") == path) {
            entry.put("bytes", payload.size).put("sha256", manifestDigest(payload))
        }
    }
    return signModTestArchive(manifest, entries)
}

private fun readModTestEntries(baseArchive: ByteArray): MutableMap<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(baseArchive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes()
        }
    }
    return entries
}

private fun manifestDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
