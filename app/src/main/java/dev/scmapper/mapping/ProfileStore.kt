package dev.scmapper.mapping

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Profiles are stored as one JSON file each under files/profiles. */
class ProfileStore(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<() -> Unit>()

    init {
        if (!prefs.getBoolean("seeded", false)) {
            Presets.all().forEach { save(it, notify = false) }
            prefs.edit().putBoolean("seeded", true).putString("active", Presets.all().first().name).apply()
        }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    @Synchronized
    fun list(): List<Profile> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { load(it) }
        .sortedBy { it.name.lowercase() }

    fun names(): List<String> = list().map { it.name }

    @Synchronized
    fun get(name: String): Profile? = load(fileFor(name))

    var activeName: String
        get() = prefs.getString("active", null) ?: names().firstOrNull() ?: Presets.gamepad().name
        set(value) {
            prefs.edit().putString("active", value).apply()
            notifyChanged()
        }

    /** The active profile, falling back to the first stored one or the default gamepad preset. */
    fun active(): Profile = get(activeName) ?: list().firstOrNull() ?: Presets.gamepad().also { save(it) }

    @Synchronized
    fun save(p: Profile, notify: Boolean = true) {
        fileFor(p.name).writeText(p.toJson().toString(2))
        if (notify) notifyChanged()
    }

    @Synchronized
    fun delete(name: String) {
        fileFor(name).delete()
        if (activeName == name) prefs.edit().putString("active", names().firstOrNull()).apply()
        notifyChanged()
    }

    @Synchronized
    fun rename(old: String, new: String): Boolean {
        if (new.isBlank() || exists(new)) return false
        val p = get(old) ?: return false
        fileFor(old).delete()
        p.name = new
        save(p, notify = false)
        if (activeName == old) prefs.edit().putString("active", new).apply()
        notifyChanged()
        return true
    }

    fun exists(name: String) = fileFor(name).exists()

    fun uniqueName(base: String): String {
        if (!exists(base)) return base
        var i = 2
        while (exists("$base ($i)")) i++
        return "$base ($i)"
    }

    /** Re-creates the built-in presets (overwriting same-named ones). */
    fun restorePresets() {
        Presets.all().forEach { save(it, notify = false) }
        notifyChanged()
    }

    /**
     * Imports an SC Mapper profile (JSON) or a Steam Input configuration (.vdf).
     * Returns the saved profile and notes about anything that couldn't be converted.
     */
    fun import(text: String, fileName: String?): Pair<Profile, List<String>> {
        val base = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported"
        val (p, notes) = if (text.trimStart().startsWith("{")) {
            Profile.fromJson(JSONObject(text)) to emptyList()
        } else {
            val r = SteamInputVdf.import(text, base)
            r.profile to r.notes
        }
        p.name = uniqueName(p.name.ifBlank { base })
        save(p)
        return p to notes
    }

    private fun fileFor(name: String) = File(dir, sanitize(name) + ".json")

    private fun load(f: File): Profile? = try {
        if (f.exists()) Profile.fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        Log.w("ProfileStore", "Bad profile ${f.name}", e)
        null
    }

    private fun sanitize(name: String): String {
        // Keep it readable but filesystem-safe and collision-free for distinct names.
        val safe = name.map { if (it.isLetterOrDigit() || it in " -_().") it else '_' }.joinToString("")
        return safe + "-" + Integer.toHexString(name.hashCode())
    }
}
