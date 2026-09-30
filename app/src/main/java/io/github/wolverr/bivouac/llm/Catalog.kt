package io.github.wolverr.bivouac.llm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Recommended models and packs (assets/catalog.json), with their download links. */
@Serializable
data class CatalogItem(
    val id: String,
    val kind: String,
    val name: String,
    val file: String,
    val url: String = "",
    val bytes: Long = 0,
    val min_ram_gb: Double = 0.0,
    val license: String = "",
    val about: String = "",
)

@Serializable
private data class CatalogFile(val items: List<CatalogItem>)

object Catalog {
    fun load(context: Context): List<CatalogItem> =
        context.assets.open("catalog.json").use { input ->
            Json { ignoreUnknownKeys = true }.decodeFromString<CatalogFile>(input.readBytes().decodeToString()).items
        }.filter { it.url.isNotEmpty() }
}
