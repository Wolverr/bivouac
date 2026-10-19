package io.github.wolverr.bivouac.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.llm.Catalog
import io.github.wolverr.bivouac.llm.CatalogItem
import io.github.wolverr.bivouac.llm.LibFile
import java.util.Locale

/** What the Library screen can ask for; the view model in the app, no-ops in screenshot tests. */
class LibraryActions(
    val addDownloads: () -> Unit = {},
    val pickFile: () -> Unit = {},
    val askStorageAccess: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val loadModel: (LibFile) -> Unit = {},
    val openPack: (LibFile) -> Unit = {},
    val delete: (LibFile) -> Unit = {},
    val bench: () -> Unit = {},
    val setAllCores: (Boolean) -> Unit = {},
    val setAutoMemory: (Boolean) -> Unit = {},
    val setRepack: (Boolean) -> Unit = {},
    val setAutoThink: (Boolean) -> Unit = {},
    val download: (String) -> Unit = {},
)

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.library.collectAsStateWithLifecycle()
    val device = vm.runtime.device
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.linkFiles(uris)
    }
    val context = LocalContext.current
    val askStorageAccess = rememberStorageAccess(vm)
    val catalog = remember { Catalog.load(context) }
    LibraryContent(
        s,
        device = String.format(
            Locale.US, "%s · %s · %.1f GB RAM · fast cores %s",
            device.model, device.soc, device.totalRamGb, device.bigCores.joinToString(","),
        ),
        ramGb = device.totalRamGb,
        catalog = catalog,
        actions = LibraryActions(
            addDownloads = { vm.addDownloads() },
            pickFile = { picker.launch(arrayOf("*/*")) },
            askStorageAccess = askStorageAccess,
            dismissNotice = { vm.dismissNotice() },
            loadModel = { vm.loadModel(it) },
            openPack = { vm.openPack(it) },
            delete = { vm.delete(it) },
            bench = { vm.bench() },
            setAllCores = vm::setAllCores,
            setAutoMemory = vm::setAutoMemory,
            setRepack = vm::setRepack,
            setAutoThink = vm::setAutoThink,
            download = { url -> openDownload(context, url) },
        ),
        modifier = modifier,
    )
}

/** Downloads open in the browser: the app has no internet permission. */
fun openDownload(context: android.content.Context, url: String) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** Storage access: "All files access" in Settings (Android 11+), the storage permission on Android 10. */
@Composable
fun rememberStorageAccess(vm: AppViewModel): () -> Unit {
    val context = LocalContext.current
    val allFilesScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.storageAccessChanged()
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.storageAccessChanged()
    }
    return {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { allFilesScreen.launch(intent) }.onFailure {
                allFilesScreen.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Unit
        } else {
            storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

/** The Library screen from state alone (no view model), so it can be rendered in tests. */
@Composable
fun LibraryContent(
    s: LibraryState,
    device: String,
    ramGb: Double,
    catalog: List<CatalogItem>,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val a = actions
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
            Text("Library", style = MaterialTheme.typography.headlineSmall)
            Text(device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RainbowRule()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GetDataSection(s, ramGb, catalog, a)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = a.addDownloads, enabled = s.busy == null) { Text("Add downloaded files") }
                TextButton(onClick = a.pickFile, enabled = s.busy == null) { Text("Pick a file") }
            }
            if (s.needsStorageAccess) {
                Panel {
                    Text(
                        "To use the files where your browser saved them, without copying 25 GB, Bivouac needs " +
                            "\"All files access\". It only reads its models and packs, and it has no internet " +
                            "permission, so nothing it reads can leave the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = a.askStorageAccess) { Text("Allow file access") }
                }
            }
            s.notice?.let { msg ->
                Panel {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = a.dismissNotice) { Text("OK") }
                }
            }
            s.busy?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                RainbowProgress(null)
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Heading("Models")
            if (s.models.isEmpty()) {
                Text("No models yet. Download a .gguf file to the phone, then import it here.", style = MaterialTheme.typography.bodyMedium)
            }
            s.models.forEach { f ->
                val loaded = s.loadedModel == f.id
                FileCard(f, active = loaded, activeLabel = "Loaded", actionLabel = if (loaded) "Reload" else "Load",
                    enabled = s.busy == null, onAction = { a.loadModel(f) }, onDelete = { a.delete(f) })
            }

            s.modelInfo?.let { info ->
                Panel {
                    Text(info.desc, style = MaterialTheme.typography.titleSmall)
                    Text(
                        String.format(
                            Locale.US, "%.1fB params · %.2f GB · ctx %d · %d threads (%d reading)%s",
                            info.n_params / 1e9, info.size_bytes / 1e9, info.n_ctx, info.threads, info.batch_threads,
                            if (info.recurrent) " · hybrid/recurrent" else "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    s.loadNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(info.system, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = a.bench, enabled = s.busy == null) { Text("Benchmark (256 prompt / 64 gen)") }
                    s.benchResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Heading("Knowledge packs")
            if (s.packs.isEmpty()) {
                Text("No knowledge pack. Without one, answers come from the model's memory only.", style = MaterialTheme.typography.bodyMedium)
            }
            s.packs.forEach { f ->
                val inUse = s.openPack == f.id || s.openPlaces == f.id
                FileCard(f, active = inUse, activeLabel = "In use",
                    actionLabel = if (inUse) null else "Use",
                    enabled = s.busy == null, onAction = { a.openPack(f) }, onDelete = { a.delete(f) })
            }
            s.packInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            s.placesInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Heading("Answers")
            Panel {
                SettingRow("Think harder for calculations", "Reasons first on questions with numbers to work out: right far more often, a few minutes slower", s.autoThink, a.setAutoThink)
            }

            Heading("Performance (applies on next Load)")
            Panel {
                SettingRow("Use all cores for reading", "Faster reading of sources; answers still use the fast cores", s.allCores, a.setAllCores)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow("Automatic memory settings", "Chooses context length and repacking from the RAM free when loading", s.autoMemory, a.setAutoMemory)
                if (!s.autoMemory) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingRow("Repack weights", "Faster math, but uses extra RAM about the model's size", s.repack, a.setRepack)
                }
            }
        }
    }
}

/**
 * Downloads go through the phone's browser (resumable, works for tens of GB) so the
 * app itself never needs network access; the downloaded files are then linked in place.
 */
@Composable
private fun GetDataSection(s: LibraryState, ramGb: Double, items: List<CatalogItem>, a: LibraryActions) {
    if (items.isEmpty()) return
    val rb = LocalRainbow.current
    val have = (s.models + s.packs).map { it.name }.toSet()
    val recommended = Catalog.recommendedModel(items, ramGb)?.id
    Heading("Get data")
    Text(
        "1. Tap Download: your browser saves the file to Downloads.  2. Come back and tap \"Add downloaded files\". " +
            "Bivouac itself has no internet permission.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    items.forEach { item ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (item.file in have) Pill("Added", rb.pastel(5))
            }
            if (item.id == recommended) Pill("Recommended for this phone", rb.pastel(4))
            Text(
                String.format(Locale.US, "%.1f GB · %s", item.bytes / 1e9, item.license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(item.about, style = MaterialTheme.typography.bodySmall)
            if (item.min_ram_gb > ramGb) {
                Text(
                    String.format(Locale.US, "Needs about %.0f GB RAM; this phone has %.1f GB.", item.min_ram_gb, ramGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.file !in have) {
                FilledTonalButton(onClick = { a.download(item.url) }) { Text("Download") }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

/** A bordered card in the blog's callout style. */
@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FileCard(
    f: LibFile,
    active: Boolean,
    activeLabel: String,
    actionLabel: String?,
    enabled: Boolean,
    onAction: () -> Unit,
    onDelete: () -> Unit,
) {
    val rb = LocalRainbow.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (active) Pill(activeLabel, rb.pastel(4))
        }
        Text(
            String.format(Locale.US, "%.2f GB", f.size / 1e9) + if (f.linked) " · linked in place" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (actionLabel != null) FilledTonalButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
            TextButton(onClick = onDelete, enabled = enabled) { Text(if (f.linked) "Remove" else "Delete") }
        }
    }
}
