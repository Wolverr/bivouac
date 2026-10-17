package io.github.wolverr.bivouac.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEval(intent)
    }

    /**
     * adb shell am start -n <pkg>/.ui.MainActivity --es eval questions.jsonl --es model <file>.gguf [--ez think true]
     * adb shell am start -n <pkg>/.ui.MainActivity --ez bench true --es model <file>.gguf   (result in logcat bivouac-eval)
     * Both take --ez repack true|false to override the memory planner's weight-repacking choice (measurements).
     */
    private fun handleEval(intent: Intent?) {
        val model = intent?.getStringExtra("model") ?: return
        val repack = if (intent.hasExtra("repack")) intent.getBooleanExtra("repack", false) else null
        if (intent.getBooleanExtra("bench", false)) {
            vm.startBench(model, repack)
            return
        }
        val questions = intent.getStringExtra("eval") ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // a long run must not sleep
        vm.startEval(questions, model, thinkHarder = intent.getBooleanExtra("think", false), forceRepack = repack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleEval(intent)
        setContent {
            BivouacTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                AppFrame(tab, onTab = { tab = it }) { m ->
                    when (tab) {
                        0 -> AskScreen(vm, m)
                        else -> LibraryScreen(vm, m)
                    }
                }
            }
        }
    }
}

/** Bottom tabs around a screen; also used by the screenshot tests. */
@Composable
fun AppFrame(tab: Int, onTab: (Int) -> Unit, content: @Composable (Modifier) -> Unit) {
    val rb = LocalRainbow.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    val colors = NavigationBarItemDefaults.colors(
                        indicatorColor = rb.pastel(if (tab == 0) 5 else 6).copy(alpha = 0.55f),
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    )
                    NavigationBarItem(
                        selected = tab == 0, onClick = { onTab(0) }, colors = colors,
                        icon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Ask") },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { onTab(1) }, colors = colors,
                        icon = { Icon(Icons.Outlined.Storage, null) }, label = { Text("Library") },
                    )
                }
            }
        },
    ) { padding -> content(Modifier.padding(padding)) }
}
