package com.ursatxt

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One file or folder on a list. [notes] counts the folder's items of its own kind. */
class Entry(
    val file: File, val dir: Boolean, val name: String, val rel: String,
    val added: Long, val opened: Long, val size: Long, val pinned: Boolean, val hue: Int,
    val preview: String = "", val notes: Int = 0, val folders: Int = 0,
)

/** The index file in each library's root. */
const val META = ".ursatxt.json"

/**
 * The app's folder: Documents/ArkNotes once access is granted, the app's own folder
 * before that. It holds one folder per library, Notes and Sketches.
 */
object Store {
    /** True when the shared folder can be used: Android 11+ needs All files access. */
    fun shared(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun hasLegacy(c: Context): Boolean = Build.VERSION.SDK_INT >= 30 ||
        c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Documents/ArkNotes. Notes from when the app was UrsaNotes, or UrsaTXT before that,
     *  move here the first time. */
    fun publicBase(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val root = File(docs, "ArkNotes")
        for (name in listOf("UrsaNotes", "UrsaTXT")) {
            val old = File(docs, name)
            if (!root.exists() && old.isDirectory) old.renameTo(root)
        }
        return layout(root)
    }

    fun privateBase(c: Context) = layout(File(c.getExternalFilesDir(null) ?: c.filesDir, "UrsaTXT"))

    fun usePublic(c: Context) = shared() && hasLegacy(c)

    fun base(c: Context): File = if (usePublic(c)) publicBase() else privateBase(c)

    private const val LAYOUT = ".layout-2"

    /**
     * Before sketches, the notes sat straight in the base folder. They move into Notes/
     * once, with their index, and the base gets a marker so this never runs again.
     */
    private fun layout(base: File): File {
        if (File(base, LAYOUT).exists()) return base
        base.mkdirs()
        val notes = File(base, "Notes")
        val olds = base.listFiles()?.toList() ?: emptyList()
        notes.mkdirs()
        for (f in olds) {
            if (f == notes) continue
            val to = File(notes, f.name)
            if (!f.renameTo(to)) { f.copyRecursively(to); f.deleteRecursively() }
        }
        File(base, "Sketches").mkdirs()
        File(base, LAYOUT).writeText("Notes and Sketches each hold one library.\n")
        return base
    }
}

/**
 * One library: every item is one file under [root] with [ext], and every folder a real
 * folder, so other apps can read them. What a file cannot hold (its colour, a pin, when
 * it was added and opened, whether its name was typed) sits in one index file, [META],
 * keyed by path from the root.
 */
open class Library(private val sub: String, val EXT: String, val untitled: String) {
    /** Yeetlist's starting hues, dealt in turn to new items; any of the 16 can be picked. */
    private val HUES = intArrayOf(24, 48, 144, 208)
    val SWATCHES = intArrayOf(8, 24, 36, 48, 60, 76, 128, 144, 168, 180, 192, 208, 240, 288, 316, 344)

    private var meta: JSONObject? = null
    private var metaRoot: File? = null

    /** Items written in the app's own folder before access move to the shared one. */
    fun root(c: Context): File {
        val priv = File(Store.privateBase(c), sub)
        val r = if (Store.usePublic(c)) File(Store.publicBase(), sub) else priv
        if (!r.exists()) r.mkdirs()
        if (r != priv && priv.exists() && (priv.list()?.isNotEmpty() == true)) migrate(priv, r)
        if (metaRoot != r) { metaRoot = r; meta = null }
        return r
    }

    private fun migrate(from: File, to: File) {
        val old = try { JSONObject(File(from, META).readText()) } catch (_: Exception) { JSONObject() }
        val now = load(to)
        from.listFiles()?.forEach { f ->
            if (f.name == META) return@forEach
            val dest = unique(to, f.name, f.isDirectory)
            if (f.renameTo(dest) || (f.copyRecursively(dest) && f.deleteRecursively())) {
                val prefix = f.name
                for (k in old.keys()) if (k == prefix || k.startsWith("$prefix/")) now.put(dest.name + k.substring(prefix.length), old.get(k))
            }
        }
        File(from, META).delete()
        meta = now; metaRoot = to
        store(to)
    }

    // ---- the index ----

    private fun load(r: File): JSONObject {
        if (metaRoot == r && meta != null) return meta!!
        val m = try { JSONObject(File(r, META).readText()) } catch (_: Exception) { JSONObject() }
        meta = m; metaRoot = r
        return m
    }

    private fun store(r: File) {
        val m = meta ?: return
        val tmp = File(r, "$META.tmp")
        tmp.writeText(m.toString())
        if (!tmp.renameTo(File(r, META))) { File(r, META).writeText(m.toString()); tmp.delete() }
    }

    fun rel(c: Context, f: File): String = f.absolutePath.removePrefix(root(c).absolutePath).trimStart('/')

    private fun info(c: Context, f: File): JSONObject {
        val m = load(root(c))
        val k = rel(c, f)
        return m.optJSONObject(k) ?: JSONObject().also { m.put(k, it) }
    }

    fun edit(c: Context, f: File, block: (JSONObject) -> Unit) {
        block(info(c, f)); store(root(c))
    }

    fun flag(c: Context, f: File, key: String) = info(c, f).optBoolean(key, false)

    /** Moves every index entry under [from] to [to], or copies them when [keep]. */
    private fun remap(c: Context, from: File, to: File, keep: Boolean) {
        val m = load(root(c))
        val a = rel(c, from); val b = rel(c, to)
        val keys = m.keys().asSequence().filter { it == a || it.startsWith("$a/") }.toList()
        for (k in keys) {
            val v = m.getJSONObject(k)
            m.put(b + k.substring(a.length), if (keep) JSONObject(v.toString()) else v)
            if (!keep) m.remove(k)
        }
        store(root(c))
    }

    private fun drop(c: Context, f: File) {
        val m = load(root(c)); val a = rel(c, f)
        m.keys().asSequence().filter { it == a || it.startsWith("$a/") }.toList().forEach { m.remove(it) }
        store(root(c))
    }

    // ---- reading ----

    /**
     * An item left blank and unnamed goes. The editor removes one on close; this catches
     * one whose editor never closed, as when the app was stopped. Run only while no
     * editor is open.
     */
    fun sweep(c: Context, dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(EXT) && f.length() == 0L && !named(c, f)) delete(c, f)
        }
    }

    fun list(c: Context, dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray()).filter { visible(it) }.map { entry(c, it) }

    /** Every item at or under [dir] whose name, or a note's text, holds [q]. */
    fun search(c: Context, dir: File, q: String): List<Entry> {
        val out = ArrayList<Entry>()
        dir.walkTopDown().onEnter { it == dir || visible(it) }.forEach { f ->
            if (f == dir || !visible(f)) return@forEach
            val nameHit = f.name.removeSuffix(EXT).contains(q, ignoreCase = true)
            if (f.isDirectory) { if (nameHit) out += entry(c, f); return@forEach }
            if (nameHit || (searchesText && f.length() < 2_000_000 && f.readText().contains(q, ignoreCase = true))) out += entry(c, f)
        }
        return out
    }

    /** Notes are searched by their text as well as their name. */
    open val searchesText = false

    private fun visible(f: File) = !f.name.startsWith(".") && (f.isDirectory || f.name.endsWith(EXT, ignoreCase = true))

    fun entry(c: Context, f: File): Entry {
        val i = info(c, f)
        val added = i.optLong("added", f.lastModified())
        val opened = i.optLong("opened", f.lastModified())
        return if (f.isDirectory) {
            val kids = f.listFiles()?.filter { visible(it) } ?: emptyList()
            Entry(f, true, f.name, rel(c, f), added, opened, f.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                i.optBoolean("pin"), i.optInt("hue", -1), notes = kids.count { !it.isDirectory }, folders = kids.count { it.isDirectory })
        } else {
            Entry(f, false, f.name.removeSuffix(EXT), rel(c, f), added, opened, f.length(),
                i.optBoolean("pin"), hueOf(c, f), preview = preview(f))
        }
    }

    fun hueOf(c: Context, f: File): Int {
        val i = info(c, f)
        if (i.has("hue")) return i.optInt("hue")
        val n = load(root(c)).optInt("#dealt", 0)
        val h = HUES[n % HUES.size]
        i.put("hue", h); load(root(c)).put("#dealt", n + 1); store(root(c))
        return h
    }

    fun named(c: Context, f: File) = info(c, f).optBoolean("named", false)

    /** A folder's picked hue, or -1 while it has none. */
    fun folderHue(c: Context, f: File) = info(c, f).optInt("hue", -1)

    /** What a card shows under the name. */
    open fun preview(f: File): String = ""

    // ---- writing ----

    /** [name] made safe as a file name: the characters Android and desktops refuse go. */
    fun clean(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').take(80).ifEmpty { untitled }

    /** A free name in [dir]: "Name", then "Name (2)", "Name (3)". */
    fun unique(dir: File, name: String, folder: Boolean, except: File? = null): File {
        val base = if (folder) name else name.removeSuffix(EXT)
        val ext = if (folder) "" else EXT
        var k = 1
        while (true) {
            val f = File(dir, if (k == 1) "$base$ext" else "$base ($k)$ext")
            if (!f.exists() || f == except) return f
            k++
        }
    }

    /** Records a new item's dates and colour; the file itself is the caller's. */
    fun added(c: Context, f: File) {
        val now = System.currentTimeMillis()
        edit(c, f) { it.put("added", now); it.put("opened", now); it.put("named", false) }
        hueOf(c, f)
    }

    fun newFolder(c: Context, dir: File, name: String = "New Folder"): File {
        val f = unique(dir, clean(name).let { if (it == untitled) "New Folder" else it }, true)
        f.mkdirs()
        val now = System.currentTimeMillis()
        edit(c, f) { it.put("added", now); it.put("opened", now) }
        return f
    }

    fun touch(c: Context, f: File) = edit(c, f) { it.put("opened", System.currentTimeMillis()) }
    fun setPin(c: Context, f: File, on: Boolean) = edit(c, f) { if (on) it.put("pin", true) else it.remove("pin") }
    fun setHue(c: Context, f: File, h: Int) = edit(c, f) { it.put("hue", h) }

    /** Renames, keeping the index; returns the file as it is now called. */
    fun rename(c: Context, f: File, name: String, typed: Boolean = true): File {
        val to = unique(f.parentFile!!, clean(name), f.isDirectory, except = f)
        if (to != f) {
            if (!f.renameTo(to)) return f
            remap(c, f, to, keep = false)
        }
        if (!to.isDirectory) edit(c, to) { it.put("named", typed) }
        return to
    }

    fun delete(c: Context, f: File): Boolean {
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) drop(c, f)
        return ok
    }

    /** False when [into] is [f] or inside it, where a folder cannot go. */
    fun canGo(f: File, into: File) = !f.isDirectory || (into != f && !into.absolutePath.startsWith(f.absolutePath + "/"))

    fun move(c: Context, f: File, into: File): File? {
        if (!canGo(f, into) || f.parentFile == into) return if (f.parentFile == into) f else null
        val to = unique(into, f.name, f.isDirectory)
        if (!f.renameTo(to)) {
            if (!(f.copyRecursively(to) && f.deleteRecursively())) return null
        }
        remap(c, f, to, keep = false)
        return to
    }

    fun copy(c: Context, f: File, into: File): File? {
        if (!canGo(f, into)) return null
        val to = unique(into, f.name, f.isDirectory)
        if (!f.copyRecursively(to)) return null
        remap(c, f, to, keep = true)
        val now = System.currentTimeMillis()
        edit(c, to) { it.put("added", now); it.put("opened", now) }
        return to
    }

    // ---- backup ----

    /** Every item, folder and the index, under [prefix]/ in the zip. Returns the items. */
    fun export(c: Context, z: ZipOutputStream, prefix: String): Int {
        val r = root(c)
        var n = 0
        r.walkTopDown().forEach { f ->
            if (f == r) return@forEach
            val path = "$prefix/" + rel(c, f)
            if (path.endsWith(".tmp")) return@forEach
            if (f.isDirectory) { z.putNextEntry(ZipEntry("$path/")); z.closeEntry(); return@forEach }
            z.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
            if (f.name.endsWith(EXT, ignoreCase = true)) n++
        }
        return n
    }

    private var incoming = JSONObject()
    private val renamed = HashMap<String, String>()

    fun importStart() { incoming = JSONObject(); renamed.clear() }

    /**
     * One zip entry at [path] inside this library. An item whose name is taken arrives
     * beside it as "Name (2)"; a folder of the same name is merged into. True for an item.
     */
    fun importEntry(c: Context, path: String, dir: Boolean, time: Long, data: java.io.InputStream): Boolean {
        val r = root(c)
        if (path == META) { incoming = try { JSONObject(data.readBytes().decodeToString()) } catch (_: Exception) { JSONObject() }; return false }
        val target = File(r, path)
        if (dir) { target.mkdirs(); return false }
        if (!path.endsWith(EXT, ignoreCase = true)) return false
        target.parentFile?.mkdirs()
        val dest = unique(target.parentFile!!, target.name, false)
        dest.outputStream().use { data.copyTo(it) }
        if (time > 0) dest.setLastModified(time)
        renamed[path] = rel(c, dest)
        return true
    }

    fun importEnd(c: Context) {
        val r = root(c)
        val m = load(r)
        for (k in incoming.keys()) {
            if (k.startsWith("#")) continue
            val to = renamed[k] ?: if (File(r, k).isDirectory && !m.has(k)) k else continue
            m.put(to, incoming.get(k))
        }
        store(r)
    }
}

object Notes : Library("Notes", ".md", "Untitled note") {
    override val searchesText = true

    fun newNote(c: Context, dir: File): File {
        val f = unique(dir, untitled, false)
        f.writeText("")
        added(c, f)
        return f
    }

    /** The first two lines of text, without markdown marks. */
    override fun preview(f: File): String {
        val lines = ArrayList<String>()
        try {
            f.bufferedReader().useLines { seq ->
                for (raw in seq) {
                    val l = raw.trim()
                    if (l.isEmpty()) continue
                    lines += l.replace(Regex("^#{1,6}\\s+"), "")
                        .replace(Regex("^(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s*)?"), "")
                        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
                        .replace(Regex("\\*\\*|~~|[*_`]"), "")
                        .replace(Regex("\\\\([\\\\`*_~\\[\\]])"), "$1")
                    if (lines.size == 2) break
                }
            }
        } catch (_: Exception) {}
        return lines.joinToString("\n")
    }
}

object Sketches : Library("Sketches", ".png", "Untitled sketch")

/** Both libraries in one zip, and back. */
object Backup {
    private val libs = listOf("Notes" to Notes, "Sketches" to Sketches)

    /** Every note, sketch and folder, at the highest compression. Returns the items. */
    fun export(c: Context, out: java.io.OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            z.setLevel(Deflater.BEST_COMPRESSION)
            for ((name, lib) in libs) n += lib.export(c, z, name)
        }
        return n
    }

    /**
     * Unpacks a backup. Entries under Notes/ or Sketches/ go to that library; a backup
     * made before sketches has neither, and all of it goes to Notes. Returns the items.
     */
    fun import(c: Context, input: java.io.InputStream): Int {
        for ((_, lib) in libs) lib.importStart()
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/').trimStart('/')
                if (path.split('/').any { it == ".." }) continue
                val head = path.substringBefore('/')
                val lib = libs.firstOrNull { it.first == head && path.contains('/') }
                val (target, rest) = if (lib != null) lib.second to path.substringAfter('/') else Notes to path
                if (rest.isEmpty()) continue
                if (target.importEntry(c, rest.trimEnd('/'), e.isDirectory, e.time, z)) n++
            }
        }
        for ((_, lib) in libs) lib.importEnd(c)
        return n
    }
}
