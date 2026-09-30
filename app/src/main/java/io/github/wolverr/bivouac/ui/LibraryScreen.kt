package io.github.wolverr.bivouac.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import io.github.wolverr.bivouac.llm.Catalog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.llm.LibFile
import java.util.Locale

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.library.collectAsStateWithLifecycle()
    val device = vm.runtime.device
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.linkFiles(uris)
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Library", style = MaterialTheme.typography.headlineSmall)
        Text(
            String.format(
                Locale.US, "%s · %s · %.1f GB RAM · fast cores %s",
                device.model, device.soc, device.totalRamGb, device.bigCores.joinToString(","),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GetDataSection(vm, s)

        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = s.busy == null) {
            Text("Add downloaded files (.gguf, .db)")
        }
        s.notice?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
            }
        }
        s.busy?.let { Text(it) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Models", style = MaterialTheme.typography.titleMedium)
        if (s.models.isEmpty()) {
            Text("No models yet. Download a .gguf file to the phone, then import it here.", style = MaterialTheme.typography.bodyMedium)
        }
        s.models.forEach { f ->
            val loaded = s.loadedModel == f.id
            FileCard(f, active = loaded, activeLabel = "Loaded", actionLabel = if (loaded) "Reload" else "Load",
                enabled = s.busy == null, onAction = { vm.loadModel(f) }, onDelete = { vm.delete(f) })
        }

        s.modelInfo?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    Text(info.system, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = vm::bench, enabled = s.busy == null) { Text("Benchmark (256 prompt / 64 gen)") }
                    s.benchResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text("Performance (applies on next Load)", style = MaterialTheme.typography.titleMedium)
        SettingRow("Use all cores for reading", "Faster reading of sources; answers still use the fast cores", s.allCores, vm::setAllCores)
        SettingRow("Automatic memory settings", "Chooses context length and repacking from the RAM free when loading", s.autoMemory, vm::setAutoMemory)
        if (!s.autoMemory) {
            SettingRow("Repack weights", "Faster math, but uses extra RAM about the model's size", s.repack, vm::setRepack)
        }

        Text("Knowledge packs", style = MaterialTheme.typography.titleMedium)
        if (s.packs.isEmpty()) {
            Text("No knowledge pack. Without one, answers come from the model's memory only.", style = MaterialTheme.typography.bodyMedium)
        }
        s.packs.forEach { f ->
            val inUse = s.openPack == f.id || s.openPlaces == f.id
            FileCard(f, active = inUse, activeLabel = "In use",
                actionLabel = if (inUse) null else "Use",
                enabled = s.busy == null, onAction = { vm.openPack(f) }, onDelete = { vm.delete(f) })
        }
        s.packInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        s.placesInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Downloads go through the phone's browser (resumable, works for tens of GB) so the
 * app itself never needs network access; the downloaded files are then linked in place.
 */
@Composable
private fun GetDataSection(vm: AppViewModel, s: LibraryState) {
    val context = LocalContext.current
    val items = remember { Catalog.load(context) }
    if (items.isEmpty()) return
    val have = (s.models + s.packs).map { it.name }.toSet()
    val ramGb = vm.runtime.device.totalRamGb
    Text("Get data", style = MaterialTheme.typography.titleMedium)
    Text(
        "1. Tap Download: your browser saves the file to Downloads.  2. Come back and tap \"Add downloaded files\". " +
            "Bivouac itself has no internet permission.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    items.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    String.format(Locale.US, "%.1f GB · %s", item.bytes / 1e9, item.license),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(item.about, style = MaterialTheme.typography.bodySmall)
                if (item.min_ram_gb > ramGb) {
                    Text(
                        String.format(Locale.US, "Needs about %.0f GB RAM; this phone has %.1f GB.", item.min_ram_gb, ramGb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (item.file in have) {
                    Text("Added", color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                    }) { Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text(
                String.format(Locale.US, "%.2f GB", f.size / 1e9) + if (f.linked) " · linked in place" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) Text(activeLabel, color = MaterialTheme.colorScheme.primary)
                if (actionLabel != null) TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                TextButton(onClick = onDelete, enabled = enabled) { Text(if (f.linked) "Remove" else "Delete") }
            }
        }
    }
}
