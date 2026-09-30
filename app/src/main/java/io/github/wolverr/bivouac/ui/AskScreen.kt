package io.github.wolverr.bivouac.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.research.Source
import java.util.Locale

@Composable
fun AskScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.ask.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bivouac", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(
            listOfNotNull(
                lib.modelInfo?.desc?.let { "Model: $it" } ?: "No model loaded",
                if (lib.openPack != null) "Library: Wikipedia" else "No knowledge pack",
            ).joinToString(" · "),
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
        if (s.running && s.answer.isEmpty()) {
            Text(
                if (prefill != null && prefill.second > 0) "Reading ${prefill.first}/${prefill.second} tokens"
                else s.status ?: "Working…",
                style = MaterialTheme.typography.bodySmall,
            )
            if (prefill != null && prefill.second > 0) {
                LinearProgressIndicator(progress = { prefill.first.toFloat() / prefill.second }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.answer.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        withCitations(s.answer, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        s.stats?.let { st ->
            Text(
                String.format(
                    Locale.US,
                    "search %d ms · prompt %d tok (%d cached) %.1f tok/s · answer %d tok %.1f tok/s · first token %.1fs",
                    s.searchMs ?: 0, st.n_prefill, st.n_reused, st.prefillTps, st.n_gen, st.genTps, st.t_first_token_ms / 1000,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (s.sources.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.titleSmall)
            s.sources.forEach { SourceCard(it) }
        }
        Row(Modifier.padding(bottom = 24.dp)) {}
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

private val CITATION = Regex("\\[(\\d{1,2})]")

/** Renders "[3]" citation markers in the accent color. */
private fun withCitations(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in CITATION.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
