package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Where the app keeps its settings and logs: ~/Library/Application Support/SimulSub (or -Dsimulsub.data). */
val dataDir: File = File(System.getProperty("simulsub.data")
    ?: "${System.getProperty("user.home")}/Library/Application Support/SimulSub").also { it.mkdirs() }

/**
 * The phone's settings with the same names and defaults, kept in settings.json; keys (Jev, BYOK) go to the macOS
 * Keychain instead. Every read also reads [rev], so a composable that shows a setting follows its changes.
 * Listeners get the changed key on the thread that changed it, like SharedPreferences.
 */
class Prefs private constructor(private val file: File) {
    private val lock = Any()
    private val json: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val rev = mutableIntStateOf(0)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun <T> get(read: JSONObject.() -> T): T { rev.intValue; return synchronized(lock) { json.read() } }
    private fun put(vararg kv: Pair<String, Any?>) {
        synchronized(lock) {
            for ((k, v) in kv) if (v == null) json.remove(k) else json.put(k, v)
            // whole-file replace: a crash mid-write never leaves half a settings file
            val tmp = File(file.path + ".tmp")
            runCatching { tmp.writeText(json.toString(1)); tmp.renameTo(file) }
        }
        rev.intValue++
        for ((k, _) in kv) listeners.forEach { it(k) }
    }
    private fun float(k: String, d: Float) = get { optDouble(k, d.toDouble()).toFloat() }
    private fun int(k: String, d: Int) = get { optInt(k, d) }
    private fun bool(k: String, d: Boolean) = get { optBoolean(k, d) }
    private fun str(k: String): String? = get { if (has(k)) optString(k) else null }

    var textSp: Float get() = float("textSp", 13f); set(v) = put("textSp" to v)
    var bgAlpha: Float get() = float("bgAlpha", DEFAULT_ALPHA); set(v) = put("bgAlpha" to v)
    fun setStyle(sizeSp: Float, alpha: Float) = put("textSp" to sizeSp, "bgAlpha" to alpha)
    /** page colours: "system", "light" or "dark" */
    var theme: String get() = str("theme") ?: "system"; set(v) = put("theme" to v)
    var palette: String get() = str("palette") ?: PALETTES[0].id; set(v) = put("palette" to v)
    /** caption card width as a part of the screen width */
    var widthFrac: Float get() = float("widthFrac", DEFAULT_WIDTH); set(v) = put("widthFrac" to v)
    /** caption area height as a part of the screen height; 0 = follows the content */
    var heightFrac: Float get() = float("heightFrac", 0f); set(v) = put("heightFrac" to v)
    var showJa: Boolean get() = bool("showJa", true); set(v) = put("showJa" to v)
    var showStats: Boolean get() = bool("showStats", true); set(v) = put("showStats" to v)
    /** the card's top-left in points, on any display (left of or above the main one is negative); null = not placed yet */
    val overlayX: Int? get() = get { if (has("overlayX")) optInt("overlayX") else null }
    val overlayY: Int? get() = get { if (has("overlayY")) optInt("overlayY") else null }
    fun setOverlay(x: Int?, y: Int?, widthFrac: Float, heightFrac: Float) =
        put("overlayX" to x, "overlayY" to y, "widthFrac" to widthFrac, "heightFrac" to heightFrac)
    var keepSentences: Int get() = int("keepSentences", CaptionBoard.DEFAULT_SENTENCES); set(v) = put("keepSentences" to v)
    var keepChars: Int get() = int("keepChars", CaptionBoard.DEFAULT_CHARS); set(v) = put("keepChars" to v)
    var finalOnly: Boolean get() = bool("finalOnly", false); set(v) = put("finalOnly" to v)
    /** null: not sure (the recognizer decides) */
    var sourceLang: Lang?
        get() = (str("sourceLang") ?: "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = put("sourceLang" to (v?.code ?: "auto"))
    var targetLang: Lang get() = Lang.of(str("targetLang")) ?: Lang.ZH; set(v) = put("targetLang" to v.code)
    var scene: Scene
        get() = runCatching { Scene.valueOf(str("scene") ?: "") }.getOrDefault(Scene.GENERAL)
        set(v) = put("scene" to v.name)
    var jevOn: Boolean get() = bool("jevOn", false); set(v) = put("jevOn" to v)
    var jevCheck: Boolean get() = bool("jevCheck", false); set(v) = put("jevCheck" to v)
    var dedupeJoin: Boolean get() = bool("dedupeJoin", false); set(v) = put("dedupeJoin" to v)
    var keepLogs: Boolean get() = bool("keepLogs", false); set(v) = put("keepLogs" to v)
    var contextOn: Boolean get() = bool("contextOn", true); set(v) = put("contextOn" to v)
    var contextSentences: Int
        get() = int("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = put("contextSentences" to v)
    var jevProvider: JevProvider
        get() = runCatching { JevProvider.valueOf(str("jevProvider") ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = put("jevProvider" to v.name)
    var autoUpdate: Boolean get() = bool("autoUpdate", true); set(v) = put("autoUpdate" to v)
    /** a release the user chose to skip (its versionCode) */
    var skippedUpdate: Int get() = int("skippedUpdate", 0); set(v) = put("skippedUpdate" to v)


    fun jevKey(p: JevProvider): String? { rev.intValue; return Keychain.get("jevKey:${p.name}") }
    /** false when the keychain refused it */
    fun setJevKey(p: JevProvider, key: String?): Boolean =
        Keychain.set("jevKey:${p.name}", key?.trim()).also { if (it) put("jevKey:${p.name}" to null) }

    var byokUrl: String get() = str("byokUrl").orEmpty(); set(v) = put("byokUrl" to v.trim())
    var byokModel: String get() = str("byokModel").orEmpty(); set(v) = put("byokModel" to v.trim())
    val byokKey: String? get() { rev.intValue; return Keychain.get("byokKey") }
    fun clearByokKey(): Boolean = synchronized(byokLock) { Keychain.set("byokKey", null).also { if (it) put("byokKey" to null) } }
    /** BYOK's fields and key change together: a running session must never pair a new key with an old address */
    private val byokLock = Any()
    /**
     * "保存": the fields, then byokSaved, so a running session never takes a half-saved set. False when the keychain
     * refused the key; then nothing is saved and the session keeps the old set.
     */
    fun saveByok(url: String, model: String, key: String?): Boolean = synchronized(byokLock) {
        if (!key.isNullOrBlank() && !Keychain.set("byokKey", key.trim())) return false
        put("byokUrl" to url.trim(), "byokModel" to model.trim())
        put("byokSaved" to System.nanoTime())
        true
    }
    fun byokTriple(): Triple<String, String, String?> = synchronized(byokLock) {
        val (u, m) = get { optString("byokUrl") to optString("byokModel") }
        Triple(u, m, Keychain.get("byokKey"))
    }
    fun byokVariant(url: String, model: String): String? = str("byokVariant:$url|$model")
    fun setByokVariant(url: String, model: String, name: String?) = put("byokVariant:$url|$model" to name)
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty() && byokKey != null

    fun listen(l: (String) -> Unit) = listeners.add(l)
    fun unlisten(l: (String) -> Unit) = listeners.remove(l)

    /** Back to the defaults of the caption look (「恢复默认」 on 字幕). */
    fun resetStyle() = put("textSp" to null, "bgAlpha" to null, "palette" to null, "widthFrac" to null, "heightFrac" to null,
        "showJa" to null, "showStats" to null, "keepSentences" to null, "keepChars" to null)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        // 0.40 on the phone; a desk screen has room for a narrower card (about 300 points on a 1512-wide display)
        const val MIN_WIDTH = 0.20f
        const val DEFAULT_ALPHA = 0.78f
        val prefs by lazy { Prefs(File(dataDir, "settings.json")) }
    }
}

/**
 * Secrets in the login keychain (service "SimulSub"), through /usr/bin/security. Values are written over its stdin
 * (`security -i`), never on a command line other processes could read. Cached: [Prefs] reads keys on every setup.
 */
object Keychain {
    private const val SERVICE = "SimulSub"
    private val cache = HashMap<String, String?>()

    @Synchronized fun get(account: String): String? = cache.getOrPut(account) {
        runCatching {
            val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.readBytes().decodeToString().trimEnd('\n')
            if (p.waitFor() == 0 && out.isNotEmpty()) out else null
        }.getOrNull()
    }

    /** true when the keychain took it (deleting a key that is not there counts as done) */
    @Synchronized fun set(account: String, value: String?): Boolean {
        cache.remove(account)
        val del = value.isNullOrEmpty()
        val cmd = if (del) "delete-generic-password -s ${q(SERVICE)} -a ${q(account)}"
            else "add-generic-password -U -s ${q(SERVICE)} -a ${q(account)} -w ${q(value!!)}"
        val ok = runCatching {
            val p = ProcessBuilder("/usr/bin/security", "-i").redirectErrorStream(true).start()
            p.outputStream.use { it.write((cmd + "\n").toByteArray()) }
            val out = p.inputStream.readBytes().decodeToString()
            // a failed command makes `security -i` exit non-zero (a missing item on delete: "could not be found")
            p.waitFor() == 0 || (del && "could not be found" in out)
        }.getOrDefault(false)
        // on failure the cache stays empty: the next read asks the keychain what it really holds
        if (ok) cache[account] = value?.ifEmpty { null }
        return ok
    }

    /** a word for `security -i`: double-quoted, with \ and " escaped (keys are checked to be printable ASCII) */
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
