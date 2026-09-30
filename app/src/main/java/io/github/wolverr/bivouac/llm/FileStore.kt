package io.github.wolverr.bivouac.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Large files (models, knowledge packs) live in the app's own folder on shared
 * storage (Android/data/<app>/files/<subdir>): no storage permission needed, and
 * big enough for multi-GB files.
 */
class FileStore(private val context: Context, subdir: String, private val extension: String) {
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, subdir).apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(extension) }?.sortedBy { it.name }.orEmpty()

    /** Copies a user-picked file (Downloads, USB drive, SD card) into the models folder. */
    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, size) = describe(context, uri)
        require(name.endsWith(extension)) { "Expected a $extension file, got $name" }
        val target = File(dir, name)
        val partial = File(dir, "$name.part")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $name" }
            partial.outputStream().use { out ->
                val buf = ByteArray(8 shl 20)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress(copied, size)
                }
            }
        }
        check(partial.renameTo(target)) { "Could not finalize $name" }
        target
    }

    companion object {
        /** Display name and size (-1 if unknown) of a content URI. */
        fun describe(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment.orEmpty()
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = c.getLong(1)
                    }
                }
            return name to size
        }
    }
}
