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
    /**
     * System prompt in assets/prompts/ for this model; empty = system.txt. The 9B uses
     * system-calc2.txt (work calculations out before the result): +0.43 overall and
     * +3.7 on reasoning for the 9B, but no gain for the 4B (docs/ROADMAP.md).
     */
    val prompt: String = "",
)

@Serializable
private data class CatalogFile(val items: List<CatalogItem>)

object Catalog {
    fun load(context: Context): List<CatalogItem> =
        context.assets.open("catalog.json").use { input ->
            Json { ignoreUnknownKeys = true }.decodeFromString<CatalogFile>(input.readBytes().decodeToString()).items
        }.filter { it.url.isNotEmpty() }

    /** The biggest model that fits [ramGb] (the catalog lists them small to large), else the smallest. */
    fun recommendedModel(items: List<CatalogItem>, ramGb: Double): CatalogItem? {
        val models = items.filter { it.kind == "model" }
        return models.filter { it.min_ram_gb <= ramGb }.maxByOrNull { it.min_ram_gb } ?: models.minByOrNull { it.min_ram_gb }
    }
}
