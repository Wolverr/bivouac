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
import java.util.Locale

@Composable
fun ModelsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.models.collectAsStateWithLifecycle()
    val device = vm.runtime.device
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Models", style = MaterialTheme.typography.headlineSmall)
        Text(
            String.format(
                Locale.US, "%s · %s · %.1f GB RAM · fast cores %s",
                device.model, device.soc, device.totalRamGb, device.bigCores.joinToString(","),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = s.busy == null) {
            Text("Import .gguf from storage")
        }
        s.busy?.let { Text(it) }
        s.importProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.files.isEmpty()) {
            Text(
                "No models yet. Download a .gguf file to the phone, then import it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        s.files.forEach { f ->
            val loaded = s.loadedPath == f.absolutePath
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall)
                    Text(String.format(Locale.US, "%.2f GB", f.length() / 1e9), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (loaded) Text("Loaded", color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { vm.loadModel(f) }, enabled = s.busy == null) { Text("Load") }
                        TextButton(onClick = { vm.deleteModel(f) }, enabled = s.busy == null) { Text("Delete") }
                    }
                }
            }
        }

        s.loadedInfo?.let { info ->
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
    }
}
