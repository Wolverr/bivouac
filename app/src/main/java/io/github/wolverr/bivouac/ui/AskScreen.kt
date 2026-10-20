package io.github.wolverr.bivouac.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wolverr.bivouac.places.Place
import io.github.wolverr.bivouac.research.CrisisResources
import io.github.wolverr.bivouac.research.Evidence
import io.github.wolverr.bivouac.research.ResearchPipeline
import io.github.wolverr.bivouac.research.Source
import java.util.Locale

/** Questions on the empty screen: one per thing Bivouac is good at. Tapping one asks it. */
private val EXAMPLES = listOf(
    "How does HTTPS keep my connection private?",
    "What should I do after a snake bite?",
    "Good hikes near Interlaken",
    "Why did the Roman Empire split in two?",
)

@Composable
fun AskScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.ask.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val evalStatus by vm.eval.collectAsStateWithLifecycle()
    AskContent(
        s,
        // The file name ("Qwen3.5-4B-Q4_K_M") reads better than llama.cpp's "qwen35 4B Q4_K - Medium".
        model = lib.models.find { it.id == lib.loadedModel }?.name?.removeSuffix(".gguf") ?: lib.modelInfo?.desc
            ?: lib.busy?.takeIf { it.startsWith("Loading") }?.let { "Loading the model…" },
        library = lib.openPack != null,
        evalStatus = evalStatus,
        onInput = vm::setInput,
        onAsk = vm::askQuestion,
        onExample = { q -> vm.setInput(q); vm.askQuestion() },
        onStop = vm::stop,
        onNewChat = vm::newChat,
        onRetry = vm::retryLast,
        onThinkHarder = vm::thinkHarder,
        modifier = modifier,
        modelLoading = lib.busy?.startsWith("Loading") == true,
    )
}

/** The Ask screen from state alone (no view model), so it can be rendered in tests. */
@Composable
fun AskContent(
    s: AskState,
    model: String?,
    library: Boolean,
    evalStatus: String?,
    onInput: (String) -> Unit,
    onAsk: () -> Unit,
    onExample: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onRetry: () -> Unit,
    onThinkHarder: () -> Unit,
    modifier: Modifier = Modifier,
    /** The model is still loading (at start): questions wait for it, and the screen says so. */
    modelLoading: Boolean = false,
) {
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val ask = {
        focus.clearFocus()  // hide the keyboard so the answer is visible
        onAsk()
    }

    // Bring a new question to the top, then leave scrolling to the reader: following every
    // streamed word snapped the list back to the turn's top whenever they scrolled down.
    LaunchedEffect(s.turns.size) {
        if (s.turns.isNotEmpty()) list.animateScrollToItem(s.turns.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BivouacMark(34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Bivouac", style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(model ?: "No model loaded", if (library) "Wikipedia" else "no library", "offline")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (s.turns.isNotEmpty()) TextButton(onClick = onNewChat, enabled = !s.running) { Text("New chat") }
        }
        RainbowRule()
        if (modelLoading) RainbowProgress(null)

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (s.turns.isEmpty()) item { Welcome(onExample) }
            itemsIndexed(s.turns) { i, turn ->
                val isLast = i == s.turns.lastIndex
                TurnView(
                    turn,
                    running = s.running && isLast,
                    onRetry = onRetry,
                    // Only the newest finished answer, and not one that already thought harder.
                    onThinkHarder = onThinkHarder.takeIf { isLast && !s.running && !turn.thoughtHarder && turn.answer.isNotEmpty() },
                )
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
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when {
                            modelLoading -> "Ask (model loading…)"
                            s.turns.isEmpty() -> "Ask anything"
                            else -> "Follow up…"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ask() }),
                maxLines = 4,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            if (s.running) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = { ask() }, enabled = s.input.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Ask")
                }
            }
        }
    }
}

/**
 * Joins the hard line breaks of wrapped source text (EIPs wrap their markdown at ~80 columns:
 * "a list of\naccounts"), keeping paragraphs and list rows. Display only: the model sees the text as is.
 */
private val WRAPPED = Regex("""(?<=[\p{L}\p{N},;)"*])\n(?=[\p{Ll}("*])""")

internal fun unwrapLines(text: String): String = WRAPPED.replace(text, " ")

@Composable
private fun Welcome(onExample: (String) -> Unit) {
    val rb = LocalRainbow.current
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BivouacMark(112.dp)
        Text("Ask anything, offline.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Answers are looked up in the Wikipedia stored on this phone, with numbered sources you can check.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.height(8.dp))
        EXAMPLES.forEachIndexed { i, q ->
            Surface(
                onClick = { onExample(q) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(rb.pastel(i + 1)))
                    Spacer(Modifier.width(12.dp))
                    Text(q, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                "No internet permission: nothing you ask leaves this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TurnView(t: TurnUi, running: Boolean, onRetry: () -> Unit, onThinkHarder: (() -> Unit)? = null) {
    val rb = LocalRainbow.current
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                t.question,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        if (t.crisis) CrisisCard()
        if (t.placeSearch != null) PlacesBlock(t, running, onRetry)

        val prefill = t.prefill
        val thinking = t.thinking
        if (running && t.answer.isEmpty()) {
            // The model reads for 30-45 s on an 8 GB phone: meanwhile, the sentence that answers most of the question.
            val excerpt = remember(t.sources) {
                Evidence.bestExcerpt(t.sources.map { it.evidence }, t.question, t.sources.map { it.passage.section })?.let { (i, s) -> t.sources[i] to s }
            }
            excerpt?.let { (src, sentence) -> ExcerptCard(src, sentence) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        thinking != null -> "Thinking it through… ${thinking.first}/${thinking.second}"
                        prefill != null && prefill.second > 0 && prefill.first < prefill.second ->
                            "Reading the sources… ${prefill.first}/${prefill.second} tokens"
                        else -> t.status ?: "Working…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RainbowProgress(
                    when {
                        thinking != null -> thinking.first.toFloat() / thinking.second
                        prefill != null && prefill.second > 0 -> prefill.first.toFloat() / prefill.second
                        else -> null
                    },
                )
            }
        }

        if (t.answer.isNotEmpty()) {
            SelectionContainer {
                Text(
                    renderAnswer(t.answer) { label ->
                        // [3] wears source 3's color, like a highlighter; [P2] (a place) the accent.
                        val n = label.toIntOrNull()
                        if (n == null) SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)
                        else SpanStyle(color = rb.ink(n), background = rb.pastel(n).copy(alpha = 0.28f), fontWeight = FontWeight.SemiBold)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        t.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        onThinkHarder?.let {
            // On slow phones calculations are not thought through unasked (minutes of writing), but a
            // 4B with 1,024 thinking tokens gets most of them right (4.7 -> 8.3 of 10, paired, 10 questions).
            OutlinedButton(onClick = it, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (t.calculation) "This looks like a calculation: tap to check it step by step (takes a few minutes)"
                    else "Think harder (slower, better at reasoning)",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (t.sources.isNotEmpty()) {
            Text("Sources", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            t.sources.forEach { SourceCard(it) }
        }
        t.stats?.let { st ->
            Text(
                String.format(
                    Locale.US,
                    "%s%d tok read (%d cached) %.1f tok/s · %d tok written %.1f tok/s%s · first token %.1fs",
                    t.searchMs?.let { "search $it ms · " } ?: "",
                    st.n_prefill, st.n_reused, st.prefillTps, st.n_gen, st.genTps,
                    if (st.n_think > 0) " (${st.n_think} thinking)" else "", st.t_first_token_ms / 1000,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PlacesBlock(t: TurnUi, running: Boolean, onRetry: () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) onRetry()
    }
    val center = t.placeCenter
    when {
        t.needsLocationPermission -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("Near ${center.label}", style = MaterialTheme.typography.labelLarge)
            }
            t.places.forEachIndexed { i, p -> PlaceRow(i + 1, p) }
        }
    }
}

@Composable
private fun PlaceRow(n: Int, p: Place) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("P$n", MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(p.displayName, style = MaterialTheme.typography.labelLarge)
                Text(
                    listOfNotNull(p.categoryLabel, ResearchPipeline.formatDistance(p.distanceM), p.street, p.phone).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CrisisCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "If you're in danger, please reach out now",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            CrisisResources.lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ExcerptCard(src: Source, sentence: String) {
    val rb = LocalRainbow.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = rb.pastel(src.n).copy(alpha = 0.22f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(src.n)
                Spacer(Modifier.width(8.dp))
                Text(
                    "While the answer is written: from ${src.passage.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(sentence, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SourceCard(src: Source) {
    var expanded by remember(src.passage.chunk) { mutableStateOf(false) }
    val rb = LocalRainbow.current
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // The stripe matches this source's [n] in the answer.
            Box(Modifier.width(5.dp).fillMaxHeight().background(rb.pastel(src.n)))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(src.n)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        src.passage.title + if (src.passage.section.isNotEmpty()) " — ${src.passage.section}" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (expanded) {
                    SelectionContainer { Text(unwrapLines(src.passage.text), style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(
                        unwrapLines(src.evidence), style = MaterialTheme.typography.bodySmall, maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
