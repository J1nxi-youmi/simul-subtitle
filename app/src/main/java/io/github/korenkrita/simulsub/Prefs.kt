package io.github.korenkrita.simulsub

import android.content.Context

/** Subtitle look, shared by the settings screen (live preview) and the overlay. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)

    /** Chinese caption size in sp */
    var textSp: Float
        get() = sp.getFloat("textSp", 13f)
        set(v) = sp.edit().putFloat("textSp", v).apply()

    /** Background opacity 0..1 */
    var bgAlpha: Float
        get() = sp.getFloat("bgAlpha", CaptionOverlayView.DEFAULT_ALPHA)
        set(v) = sp.edit().putFloat("bgAlpha", v).apply()

    /** Size and opacity in one edit, so a change listener never sees one new and one old value. */
    fun setStyle(sizeSp: Float, alpha: Float) = sp.edit().putFloat("textSp", sizeSp).putFloat("bgAlpha", alpha).apply()

    /** Home screen theme: "system", "light" or "dark" */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Caption colour scheme id, see CaptionOverlayView.PALETTES */
    var palette: String
        get() = sp.getString("palette", CaptionOverlayView.PALETTES[0].id)!!
        set(v) = sp.edit().putString("palette", v).apply()

    /** Overlay width as a fraction of the screen width */
    var widthFrac: Float
        get() = sp.getFloat("widthFrac", DEFAULT_WIDTH)
        set(v) = sp.edit().putFloat("widthFrac", v).apply()

    /** Caption area height as a fraction of the usable screen height; 0 = fit the text (default) */
    var heightFrac: Float
        get() = sp.getFloat("heightFrac", 0f)
        set(v) = sp.edit().putFloat("heightFrac", v).apply()

    /** Show the recognised Japanese above the Chinese line */
    var showJa: Boolean
        get() = sp.getBoolean("showJa", true)
        set(v) = sp.edit().putBoolean("showJa", v).apply()

    /** Show the small timing line under the captions */
    var showStats: Boolean
        get() = sp.getBoolean("showStats", true)
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
    /** Source to continue [path] from after its download was rejected (-1 none): survives DownloadManager cleanup. */
    fun pendingSource(path: String) = sp.getInt("dlNext:$path", -1)
    fun setPendingSource(path: String, source: Int) = sp.edit().putInt("dlNext:$path", source).apply()
    /** The user cancelled the model download: no automatic switch to another source until 下载 / 重试. */
    var downloadCancelled: Boolean
        get() = sp.getBoolean("dlCancelled", false)
        set(v) = sp.edit().putBoolean("dlCancelled", v).apply()
    /** true = only show finished sentences; false = live drafts that are corrected in place. Overlay and home share it. */
    var finalOnly: Boolean
        get() = sp.getBoolean("finalOnly", false)
        set(v) = sp.edit().putBoolean("finalOnly", v).apply()

    /**
     * How captions are translated; read when a session starts. Before BYOK this was the mtOnline switch, so a phone
     * that never chose keeps what it had (online = Index, off = local).
     */
    var mtMode: MtMode
        get() = sp.getString("mtMode", null)?.let { runCatching { MtMode.valueOf(it) }.getOrNull() }
            ?: if (sp.getBoolean("mtOnline", true)) MtMode.INDEX else MtMode.LOCAL
        set(v) = sp.edit().putString("mtMode", v.name).apply()
    /** Translation needs the network (Index or BYOK), not the local model file. */
    val mtOnline: Boolean get() = mtMode != MtMode.LOCAL
    var onlineModel: OnlineModel
        get() = runCatching { OnlineModel.valueOf(sp.getString("onlineModel", null) ?: "") }.getOrDefault(OnlineModel.B35)
        set(v) = sp.edit().putString("onlineModel", v.name).apply()

    /** Spoken language (null = auto: Japanese or English) and caption language; read when a session starts. */
    var sourceLang: Lang?
        get() = sp.getString("sourceLang", "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = sp.edit().putString("sourceLang", v?.code ?: "auto").apply()
    var targetLang: Lang
        get() = Lang.of(sp.getString("targetLang", null)) ?: Lang.ZH
        set(v) = sp.edit().putString("targetLang", v.code).apply()

    /** What the subtitles are about (translator background + term list); read when a session starts. */
    var scene: Scene
        get() = runCatching { Scene.valueOf(sp.getString("scene", null) ?: "") }.getOrDefault(Scene.GENERAL)
        set(v) = sp.edit().putString("scene", v.name).apply()

    /** Use Jev (cloud) for sentence ends; needs the key of [jevProvider]. */
    var jevOn: Boolean
        get() = sp.getBoolean("jevOn", false)
        set(v) = sp.edit().putBoolean("jevOn", v).apply()
    /** Check every final translation with Jev; independent of [jevOn]. Off by default, its own key so an old jevOn does not turn it on. */
    var jevCheck: Boolean
        get() = sp.getBoolean("jevCheck", false)
        set(v) = sp.edit().putBoolean("jevCheck", v).apply()
    /** Drop a repeat at the join of a kept draft and the rest (Pipeline.dedupeJoin). Off by default. */
    var dedupeJoin: Boolean
        get() = sp.getBoolean("dedupeJoin", false)
        set(v) = sp.edit().putBoolean("dedupeJoin", v).apply()
    /** Write a session log (Settings → 使用记录). Off by default: nothing is recorded until the user turns it on. */
    var keepLogs: Boolean
        get() = sp.getBoolean("keepLogs", false)
        set(v) = sp.edit().putBoolean("keepLogs", v).apply()
    /** Give the translator the last finished sentences as context (on by default, as before the switch). */
    var contextOn: Boolean
        get() = sp.getBoolean("contextOn", true)
        set(v) = sp.edit().putBoolean("contextOn", v).apply()
    var contextSentences: Int
        get() = sp.getInt("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = sp.edit().putInt("contextSentences", v).apply()
    var jevProvider: JevProvider
        // a choice saved earlier stays; with none yet, the free Zen endpoint is the default
        get() = runCatching { JevProvider.valueOf(sp.getString("jevProvider", null) ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = sp.edit().putString("jevProvider", v.name).apply()

    /** API key per provider, encrypted with an Android Keystore key (the stored value is useless off this device). */
    fun jevKey(p: JevProvider): String? = sp.getString("jevKey:${p.name}", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    fun setJevKey(p: JevProvider, key: String?) =
        sp.edit().apply { if (key.isNullOrBlank()) remove("jevKey:${p.name}") else putString("jevKey:${p.name}", KeyBox.seal(key.trim())) }.apply()

    /** BYOK: the user's OpenAI-compatible service. The key is sealed like the Jev keys. */
    var byokUrl: String
        get() = sp.getString("byokUrl", "") ?: ""
        set(v) = sp.edit().putString("byokUrl", v.trim()).apply()
    var byokModel: String
        get() = sp.getString("byokModel", "") ?: ""
        set(v) = sp.edit().putString("byokModel", v.trim()).apply()
    var byokKey: String?
        get() = sp.getString("byokKey", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v.isNullOrBlank()) remove("byokKey") else putString("byokKey", KeyBox.seal(v.trim())) }.apply()
    /**
     * The [ByokTranslator.THINKING_OFF] variant (by name) that answered without reasoning for this exact service URL and
     * model; null = not found yet. Kept per URL + model, so switching back and forth does not probe again.
     */
    fun byokVariant(url: String, model: String): String? = sp.getString("byokVariant:$url|$model", null)
    fun setByokVariant(url: String, model: String, name: String?) =
        sp.edit().apply { if (name == null) remove("byokVariant:$url|$model") else putString("byokVariant:$url|$model", name) }.apply()
    /** URL, model and key are all there and valid: BYOK can start. */
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty() && byokKey != null


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
