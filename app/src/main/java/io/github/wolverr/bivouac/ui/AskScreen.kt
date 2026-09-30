package io.github.wolverr.bivouac.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun AskScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.ask.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bivouac", style = MaterialTheme.typography.headlineSmall)
        Text(
            models.loadedInfo?.desc?.let { "Model: $it" } ?: "No model loaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = s.question,
            onValueChange = vm::setQuestion,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask anything — works offline") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.askQuestion() }),
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::askQuestion, enabled = !s.running) { Text("Ask") }
            if (s.running) OutlinedButton(onClick = vm::stop) { Text("Stop") }
        }

        val prefill = s.prefill
        if (s.running && s.answer.isEmpty() && prefill != null && prefill.second > 0) {
            Text("Reading prompt ${prefill.first}/${prefill.second} tokens", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { prefill.first.toFloat() / prefill.second },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (s.running && s.answer.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.answer.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                SelectionContainer {
                    Text(
                        s.answer,
                        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        s.stats?.let { st ->
            Text(
                String.format(
                    Locale.US,
                    "prompt %d tok (%d cached) · %.1f tok/s  ·  answer %d tok · %.1f tok/s  ·  first token %.1fs",
                    st.n_prefill, st.n_reused, st.prefillTps, st.n_gen, st.genTps, st.t_first_token_ms / 1000,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
