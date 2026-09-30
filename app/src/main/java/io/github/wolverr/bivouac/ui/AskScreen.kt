package io.github.wolverr.bivouac.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.places.Place
import io.github.wolverr.bivouac.research.CrisisResources
import io.github.wolverr.bivouac.research.ResearchPipeline
import io.github.wolverr.bivouac.research.Source
import java.util.Locale

@Composable
fun AskScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.ask.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val evalStatus by vm.eval.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val ask = {
        focus.clearFocus()  // hide the keyboard so the answer is visible
        vm.askQuestion()
    }

    // Follow the newest turn while it streams.
    val last = s.turns.lastOrNull()
    LaunchedEffect(s.turns.size, last?.answer?.length) {
        if (s.turns.isNotEmpty()) list.animateScrollToItem(s.turns.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Bivouac", style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(
                        lib.modelInfo?.desc ?: "No model loaded",
                        if (lib.openPack != null) "Wikipedia" else "no library",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.turns.isNotEmpty()) TextButton(onClick = vm::newChat, enabled = !s.running) { Text("New chat") }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (s.turns.isEmpty()) {
                item {
                    Text(
                        "Ask anything — it works offline. Questions that need facts are looked up in the " +
                            "offline Wikipedia; everything else is answered directly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(s.turns) { i, turn ->
                TurnView(turn, running = s.running && i == s.turns.lastIndex, onRetry = vm::retryLast)
            }
        }

        evalStatus?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = s.input,
                onValueChange = vm::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (s.turns.isEmpty()) "Ask anything" else "Follow up…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ask() }),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            if (s.running) {
                FilledIconButton(onClick = vm::stop) { Icon(Icons.Outlined.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = { ask() }, enabled = s.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Ask")
                }
            }
        }
    }
}

@Composable
private fun TurnView(t: TurnUi, running: Boolean, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t.question, style = MaterialTheme.typography.titleMedium)

        if (t.crisis) CrisisCard()
        if (t.placeSearch != null) PlacesBlock(t, running, onRetry)

        val prefill = t.prefill
        if (running && t.answer.isEmpty()) {
            Text(
                if (prefill != null && prefill.second > 0) "Reading ${prefill.first}/${prefill.second} tokens" else t.status ?: "Working…",
                style = MaterialTheme.typography.bodySmall,
            )
            if (prefill != null && prefill.second > 0) {
                LinearProgressIndicator(progress = { prefill.first.toFloat() / prefill.second }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (t.answer.isNotEmpty()) {
            SelectionContainer {
                Text(renderAnswer(t.answer, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge)
            }
        }
        t.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        t.stats?.let { st ->
            Text(
                String.format(
                    Locale.US,
                    "%s%d tok read (%d cached) %.1f tok/s · %d tok written %.1f tok/s · first token %.1fs",
                    t.searchMs?.let { "search $it ms · " } ?: "",
                    st.n_prefill, st.n_reused, st.prefillTps, st.n_gen, st.genTps, st.t_first_token_ms / 1000,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (t.sources.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.labelLarge)
            t.sources.forEach { SourceCard(it) }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlacesBlock(t: TurnUi, running: Boolean, onRetry: () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) onRetry()
    }
    val center = t.placeCenter
    when {
        t.needsLocationPermission -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("To find places near you, Bivouac needs your location (GPS works offline; nothing leaves the phone).")
                FilledTonalButton(onClick = {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, enabled = !running) { Text("Allow location") }
            }
        }
        center == null -> Text(
            if (t.placeSearch?.nearMe == true) "Couldn't get your location. Is location turned on?"
            else "Couldn't find \"${t.placeSearch?.location}\" on the offline map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        t.places.isEmpty() -> Text("No matching places found near ${center.label}.", style = MaterialTheme.typography.bodySmall)
        else -> {
            Text("Near ${center.label}", style = MaterialTheme.typography.labelLarge)
            t.places.forEachIndexed { i, p -> PlaceRow(i + 1, p) }
        }
    }
}

@Composable
private fun PlaceRow(n: Int, p: Place) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("[P$n] ${p.displayName}", style = MaterialTheme.typography.labelLarge)
            Text(
                listOfNotNull(p.categoryLabel, ResearchPipeline.formatDistance(p.distanceM), p.street, p.phone).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CrisisCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("If you're in danger, please reach out now", style = MaterialTheme.typography.labelLarge)
            CrisisResources.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SourceCard(src: Source) {
    var expanded by remember(src.passage.chunk) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "[${src.n}] ${src.passage.title}" + if (src.passage.section.isNotEmpty()) " — ${src.passage.section}" else "",
                style = MaterialTheme.typography.labelLarge,
            )
            if (expanded) {
                HorizontalDivider()
                SelectionContainer { Text(src.passage.text, style = MaterialTheme.typography.bodySmall) }
            } else {
                Text(src.evidence, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
    }
}
