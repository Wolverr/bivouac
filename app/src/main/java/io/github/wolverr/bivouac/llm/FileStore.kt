package io.github.wolverr.bivouac.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A model or knowledge pack: a file in the app's folder, or a user file linked in place. */
data class LibFile(val id: String, val name: String, val size: Long, val uri: Uri? = null, val file: File? = null) {
    val linked: Boolean get() = uri != null
}

/**
 * Large files (models, knowledge packs). Two kinds:
 *  - files in the app's own folder on shared storage (Android/data/<app>/files/<subdir>),
 *    e.g. pushed with adb;
 *  - files the user picked (usually in Downloads) that are *linked in place*: the app
 *    keeps a persistable read grant and opens them through /proc/self/fd, so a 25 GB
 *    pack needs no copy and no second 25 GB of free space, and the app never needs
 *    network access to get its data.
 */
class FileStore(private val context: Context, subdir: String, private val extension: String) {
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, subdir).apply { mkdirs() }
    private val prefs = context.getSharedPreferences("linked_$subdir", Context.MODE_PRIVATE)
    private val openFds = HashMap<String, ParcelFileDescriptor>()

    fun list(): List<LibFile> {
        val local = dir.listFiles { f -> f.isFile && f.name.endsWith(extension) }.orEmpty()
            .map { LibFile(it.absolutePath, it.name, it.length(), file = it) }
        val linked = prefs.all.keys.mapNotNull { key ->
            val uri = Uri.parse(key)
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                prefs.edit().remove(key).apply()  // the grant is gone (file deleted or revoked)
                return@mapNotNull null
            }
            val (name, size) = describe(context, uri)
            LibFile(key, name, size, uri = uri)
        }
        return (local + linked).sortedBy { it.name }
    }

    /** Links a user-picked file in place. Returns its entry. */
    fun link(uri: Uri): LibFile {
        val (name, size) = describe(context, uri)
        require(name.endsWith(extension)) { "Expected a $extension file, got $name" }
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
        val pfd = openFds.getOrPut(f.id) {
            requireNotNull(context.contentResolver.openFileDescriptor(f.uri!!, "r")) { "Could not open ${f.name}" }
        }
        "/proc/self/fd/${pfd.fd}"
    }

    /** Deletes app-folder files; for linked files only forgets the link (the user's file stays). */
    fun remove(f: LibFile) {
        f.file?.delete()
        f.uri?.let { uri ->
            openFds.remove(f.id)?.close()
            prefs.edit().remove(f.id).apply()
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    companion object {
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
