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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Locale

@Composable
fun LibraryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.library.collectAsStateWithLifecycle()
    val device = vm.runtime.device
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
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

        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = s.busy == null) {
            Text("Import a model (.gguf) or knowledge pack (.db)")
        }
        s.busy?.let { Text(it) }
        s.importProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Models", style = MaterialTheme.typography.titleMedium)
        if (s.models.isEmpty()) {
            Text("No models yet. Download a .gguf file to the phone, then import it here.", style = MaterialTheme.typography.bodyMedium)
        }
        s.models.forEach { f ->
            FileCard(f, active = s.loadedModel == f.absolutePath, activeLabel = "Loaded", actionLabel = "Load",
                enabled = s.busy == null, onAction = { vm.loadModel(f) }, onDelete = { vm.delete(f) })
        }

        s.modelInfo?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.desc, style = MaterialTheme.typography.titleSmall)
                    Text(
                        String.format(
                            Locale.US, "%.1fB params · %.2f GB · ctx %d · %d threads%s",
                            info.n_params / 1e9, info.size_bytes / 1e9, info.n_ctx, info.threads,
                            if (info.recurrent) " · hybrid/recurrent" else "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(info.system, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = vm::bench, enabled = s.busy == null) { Text("Benchmark (256 prompt / 64 gen)") }
                    s.benchResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text("Knowledge packs", style = MaterialTheme.typography.titleMedium)
        if (s.packs.isEmpty()) {
            Text("No knowledge pack. Without one, answers come from the model's memory only.", style = MaterialTheme.typography.bodyMedium)
        }
        s.packs.forEach { f ->
            FileCard(f, active = s.openPack == f.absolutePath, activeLabel = "In use", actionLabel = "Use",
                enabled = s.busy == null, onAction = { vm.openPack(f) }, onDelete = { vm.delete(f) })
        }
        s.packInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FileCard(
    f: File,
    active: Boolean,
    activeLabel: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text(String.format(Locale.US, "%.2f GB", f.length() / 1e9), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) Text(activeLabel, color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
            }
        }
    }
}
