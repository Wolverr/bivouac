package io.github.wolverr.bivouac.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.llm.Catalog
import io.github.wolverr.bivouac.llm.CatalogItem
import java.util.Locale

/** What the setup screen can ask for; the view model in the app, no-ops in screenshot tests. */
class SetupActions(
    val download: (CatalogItem) -> Unit = {},
    val addDownloads: () -> Unit = {},
    val askStorageAccess: () -> Unit = {},
    val chooseMyself: () -> Unit = {},
)

/**
 * First run, until a model and the Wikipedia pack are added: the files recommended for this
 * phone, downloaded one after another through the browser, then added in one tap.
 */
@Composable
fun SetupScreen(vm: AppViewModel, onChooseMyself: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val catalog = remember { Catalog.load(context) }
    val askStorageAccess = rememberStorageAccess(vm)
    var started by rememberSaveable { mutableStateOf(listOf<String>()) }
    val free = remember(s.models.size, s.packs.size) {
        runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()
    }
    SetupContent(
        s,
        ramGb = vm.runtime.device.totalRamGb,
        catalog = catalog,
        freeBytes = free,
        started = started.toSet(),
        actions = SetupActions(
            download = { item ->
                started = started + item.id
                openDownload(context, item.url)
            },
            addDownloads = { vm.addDownloads() },
            askStorageAccess = askStorageAccess,
            chooseMyself = {
                vm.finishSetup()
                onChooseMyself()
            },
        ),
        modifier = modifier,
    )
}

/** The setup screen from state alone, so it can be rendered in tests. */
@Composable
fun SetupContent(
    s: LibraryState,
    ramGb: Double,
    catalog: List<CatalogItem>,
    freeBytes: Long?,
    started: Set<String>,
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    val rb = LocalRainbow.current
    val have = (s.models + s.packs).map { it.name }.toSet()
    val picks = listOfNotNull(
        Catalog.recommendedModel(catalog, ramGb),
        catalog.firstOrNull { it.kind == "pack" && it.file.startsWith("wiki") },
        catalog.firstOrNull { it.kind == "pack" && it.file.startsWith("places") },
    )
    val missing = picks.filter { it.file !in have }
    val next = missing.firstOrNull { it.id !in started }
    val needBytes = missing.sumOf { it.bytes }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BivouacMark(34.dp)
            Spacer(Modifier.width(10.dp))
            Text("Set up Bivouac", style = MaterialTheme.typography.titleLarge)
        }
        RainbowRule()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Bivouac answers from Wikipedia and finds places with no internet connection. It needs three " +
                    "files on the phone, downloaded once (Wi-Fi recommended). After that it works fully offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Panel {
                Text(
                    String.format(Locale.US, "Recommended for this phone (%.1f GB RAM)", ramGb),
                    style = MaterialTheme.typography.titleSmall,
                )
                picks.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${item.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                String.format(Locale.US, "%.1f GB", item.bytes / 1e9),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            item.file in have -> Pill("Added", rb.pastel(5))
                            item.id in started -> Pill("Downloading", rb.pastel(3))
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    val enough = freeBytes == null || freeBytes >= needBytes
                    Text(
                        String.format(Locale.US, "To download: %.1f GB", needBytes / 1e9) +
                            (freeBytes?.let { String.format(Locale.US, " · free on the phone: %.1f GB", it / 1e9) } ?: "") +
                            if (enough) "" else ". Not enough space: free some, or skip Places (the last one).",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enough) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                next?.let { item ->
                    Button(onClick = { actions.download(item) }) {
                        Text("Download ${picks.indexOf(item) + 1} of ${picks.size}: ${item.name.substringBefore(" (")}")
                    }
                    Text(
                        "Your browser saves the file to Downloads. Come back here to start the next one; " +
                            "the downloads run side by side.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Panel {
                Text("When the downloads have finished", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Bivouac uses the files where the browser saved them, without copying them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = actions.addDownloads, enabled = s.busy == null) { Text("Add downloaded files") }
                if (s.needsStorageAccess) {
                    Text(
                        "To read the files in Downloads, Bivouac needs \"All files access\". It has no internet " +
                            "permission, so nothing it reads can leave the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = actions.askStorageAccess) { Text("Allow file access") }
                }
                s.busy?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    RainbowProgress(null)
                }
                s.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = actions.chooseMyself) { Text("Choose other models in the Library") }
        }
    }
}
