package io.github.wolverr.bivouac.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A model or knowledge pack: a file in the app's folder, or a user file linked in place. */
data class LibFile(
    val id: String, val name: String, val size: Long, val uri: Uri? = null, val file: File? = null,
    /** A user file (usually in Downloads) linked by path; readable with storage access. */
    val linkedPath: File? = null,
) {
    val linked: Boolean get() = uri != null || linkedPath != null
}

/**
 * Large files (models, knowledge packs). Two kinds:
 *  - files in the app's own folder on shared storage (Android/data/<app>/files/<subdir>),
 *    e.g. pushed with adb;
 *  - files in Downloads (or picked elsewhere) that are *linked in place* by path, so a
 *    25 GB pack needs no copy and no second 25 GB of free space, and the app never needs
 *    network access to get its data. Native code (llama.cpp, SQLite) opens files by path,
 *    and a path in Downloads is only readable with storage access ([hasStorageAccess]):
 *    the picker's grant covers the descriptor, but /proc/self/fd/N is reopened as a path
 *    and fails ("not a readable GGUF file" on the Redmi, 2026-10-07). Older picker links
 *    (uri) are kept and resolved to their path when possible.
 */
class FileStore(private val context: Context, subdir: String, private val extension: String) {
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, subdir).apply { mkdirs() }
    private val prefs = context.getSharedPreferences("linked_$subdir", Context.MODE_PRIVATE)
    private val openFds = HashMap<String, ParcelFileDescriptor>()

    fun list(): List<LibFile> {
        val local = dir.listFiles { f -> f.isFile && f.name.endsWith(extension) }.orEmpty()
            .map { LibFile(it.absolutePath, it.name, it.length(), file = it) }
        val byPath = prefs.all.keys.filter { it.startsWith(PATH_KEY) }.mapNotNull { key ->
            val f = File(key.removePrefix(PATH_KEY))
            if (!f.isFile) {
                prefs.edit().remove(key).apply()  // deleted
                return@mapNotNull null
            }
            LibFile(key, f.name, f.length(), linkedPath = f)
        }
        val linked = prefs.all.keys.filter { !it.startsWith(PATH_KEY) }.mapNotNull { key ->
            val uri = Uri.parse(key)
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                prefs.edit().remove(key).apply()  // the grant is gone (file deleted or revoked)
                return@mapNotNull null
            }
            val (name, size) = describe(context, uri)
            LibFile(key, name, size, uri = uri)
        }
        return (local + byPath + linked).distinctBy { it.name }.sortedBy { it.name }
    }

    /** Links a file by path (Downloads, with storage access). Returns its entry. */
    fun linkPath(f: File): LibFile {
        require(f.name.endsWith(extension)) { "Expected a $extension file, got ${f.name}" }
        val key = PATH_KEY + f.absolutePath
        prefs.edit().putString(key, f.name).apply()
        return LibFile(key, f.name, f.length(), linkedPath = f)
    }

    /**
     * Links a user-picked file in place: by its path when storage access allows reading it
     * (native code needs a path), else by its uri. Returns its entry.
     */
    fun link(uri: Uri): LibFile {
        val (name, size) = describe(context, uri)
        require(name.endsWith(extension)) { "Expected a $extension file, got $name" }
        resolvePath(uri, name, size)?.takeIf { it.canRead() }?.let { return linkPath(it) }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(uri.toString(), name).apply()
        return LibFile(uri.toString(), name, size, uri = uri)
    }

    /**
     * A filesystem path native code can open (fopen/mmap/sqlite). For linked files this
     * is /proc/self/fd/N of a descriptor kept open for the life of the app.
     */
    suspend fun path(f: LibFile): String = withContext(Dispatchers.IO) {
        f.file?.let { return@withContext it.absolutePath }
        f.linkedPath?.let { return@withContext it.absolutePath }
        resolvePath(f.uri!!, f.name, f.size)?.takeIf { it.canRead() }?.let { return@withContext it.absolutePath }
        val pfd = openFds.getOrPut(f.id) {
            requireNotNull(context.contentResolver.openFileDescriptor(f.uri!!, "r")) { "Could not open ${f.name}" }
        }
        "/proc/self/fd/${pfd.fd}"
    }

    /** Deletes app-folder files; for linked files only forgets the link (the user's file stays). */
    fun remove(f: LibFile) {
        f.file?.delete()
        f.linkedPath?.let { prefs.edit().remove(f.id).apply() }
        f.uri?.let { uri ->
            openFds.remove(f.id)?.close()
            prefs.edit().remove(f.id).apply()
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** The real file behind a picked uri: "raw:/storage/…", "primary:Download/…", else by name in Downloads. */
    private fun resolvePath(uri: Uri, name: String, size: Long): File? = runCatching {
        val id = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else ""
        when {
            id.startsWith("raw:") -> File(id.removePrefix("raw:"))
            id.startsWith("primary:") -> File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
            else -> File(downloadsDir(), name).takeIf { it.isFile && (size < 0 || it.length() == size) }
        }
    }.getOrNull()

    companion object {
        private const val PATH_KEY = "path:"

        fun downloadsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        /** Whether files in Downloads can be read by path ("All files access"; Android 10: storage permission). */
        fun hasStorageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }

        /** Display name and size (-1 if unknown) of a content URI. */
        fun describe(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment.orEmpty()
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            name = c.getString(0) ?: name
                            size = c.getLong(1)
                        }
                    }
            }
            return name to size
        }
    }
}
