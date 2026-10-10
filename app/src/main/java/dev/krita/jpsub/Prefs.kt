package dev.krita.jpsub

import android.content.Context

/** Subtitle look, shared by the settings screen (live preview) and the overlay. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)

    /** Chinese caption size in sp */
    var textSp: Float
        get() = sp.getFloat("textSp", 20f)
        set(v) = sp.edit().putFloat("textSp", v).apply()

    /** Background opacity 0..1 */
    var bgAlpha: Float
        get() = sp.getFloat("bgAlpha", 0.55f)
        set(v) = sp.edit().putFloat("bgAlpha", v).apply()

    /** Size and opacity in one edit, so a change listener never sees one new and one old value. */
    fun setStyle(sizeSp: Float, alpha: Float) = sp.edit().putFloat("textSp", sizeSp).putFloat("bgAlpha", alpha).apply()

    /** Overlay width as a fraction of the screen width */
    var widthFrac: Float
        get() = sp.getFloat("widthFrac", DEFAULT_WIDTH)
        set(v) = sp.edit().putFloat("widthFrac", v).apply()

    /** Show the recognised Japanese above the Chinese line */
    var showJa: Boolean
        get() = sp.getBoolean("showJa", false)
        set(v) = sp.edit().putBoolean("showJa", v).apply()

    /** Show the small timing line under the captions */
    var showStats: Boolean
        get() = sp.getBoolean("showStats", false)
        set(v) = sp.edit().putBoolean("showStats", v).apply()

    /** Overlay position (top-left, px); -1 = default */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()
    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    /** Overlay keeps old sentences until there are more than [keepSentences] AND more than [keepChars] characters. */
    var keepSentences: Int
        get() = sp.getInt("keepSentences", CaptionBoard.DEFAULT_SENTENCES)
        set(v) = sp.edit().putInt("keepSentences", v).apply()
    var keepChars: Int
        get() = sp.getInt("keepChars", CaptionBoard.DEFAULT_CHARS)
        set(v) = sp.edit().putInt("keepChars", v).apply()

    /** DownloadManager ids by model path, so a download survives the activity being recreated */
    fun downloadId(path: String) = sp.getLong("dl:$path", -1)
    fun setDownloadId(path: String, id: Long) = sp.edit().putLong("dl:$path", id).apply()
    /** true = only show finished sentences; false = live drafts that are corrected in place. Overlay and home share it. */
    var finalOnly: Boolean
        get() = sp.getBoolean("finalOnly", false)
        set(v) = sp.edit().putBoolean("finalOnly", v).apply()

    /** Translate with bilibili's public API (default) or the local Index-Translate-2B; read when a session starts. */
    var mtOnline: Boolean
        get() = sp.getBoolean("mtOnline", true)
        set(v) = sp.edit().putBoolean("mtOnline", v).apply()
    var onlineModel: OnlineModel
        get() = runCatching { OnlineModel.valueOf(sp.getString("onlineModel", null) ?: "") }.getOrDefault(OnlineModel.B35)
        set(v) = sp.edit().putString("onlineModel", v.name).apply()

    /** What the subtitles are about (translator background + term list); read when a session starts. */
    var scene: Scene
        get() = runCatching { Scene.valueOf(sp.getString("scene", null) ?: "") }.getOrDefault(Scene.GENERAL)
        set(v) = sp.edit().putString("scene", v.name).apply()

    /** Use Jev (cloud) for sentence ends and translation checks; needs the key of [jevProvider]. */
    var jevOn: Boolean
        get() = sp.getBoolean("jevOn", false)
        set(v) = sp.edit().putBoolean("jevOn", v).apply()
    var jevProvider: JevProvider
        // a choice saved earlier stays; with none yet, the free Zen endpoint is the default
        get() = runCatching { JevProvider.valueOf(sp.getString("jevProvider", null) ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = sp.edit().putString("jevProvider", v.name).apply()

    /** API key per provider, encrypted with an Android Keystore key (the stored value is useless off this device). */
    fun jevKey(p: JevProvider): String? = sp.getString("jevKey:${p.name}", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    fun setJevKey(p: JevProvider, key: String?) =
        sp.edit().apply { if (key.isNullOrBlank()) remove("jevKey:${p.name}") else putString("jevKey:${p.name}", KeyBox.seal(key.trim())) }.apply()


    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        /** Width slider / resize range as a fraction of the screen width. */
        const val MIN_WIDTH = 0.40f
    }
}

/** AES-GCM with a non-exportable Android Keystore key; output = base64(iv ‖ ciphertext). */
private object KeyBox {
    private const val ALIAS = "jpsub-jev"
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun seal(s: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return android.util.Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), android.util.Base64.NO_WRAP)
    }
    fun open(b64: String): String {
        val b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
