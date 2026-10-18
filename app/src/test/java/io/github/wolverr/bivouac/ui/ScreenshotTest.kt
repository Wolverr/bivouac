package io.github.wolverr.bivouac.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.wolverr.bivouac.llm.CatalogItem
import io.github.wolverr.bivouac.llm.CompletionStats
import io.github.wolverr.bivouac.llm.LibFile
import io.github.wolverr.bivouac.llm.ModelInfo
import io.github.wolverr.bivouac.places.GeoPoint
import io.github.wolverr.bivouac.places.Place
import io.github.wolverr.bivouac.places.PlaceIntent
import io.github.wolverr.bivouac.research.Passage
import io.github.wolverr.bivouac.research.Source
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens with sample data to build/screenshots/ (no phone, no model):
 *   ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, tab: Int = 0, content: @Composable (androidx.compose.ui.Modifier) -> Unit) {
        compose.setContent { BivouacTheme(dark) { AppFrame(tab, onTab = {}, content = content) } }
        compose.mainClock.advanceTimeBy(1200)  // let the rainbow bar move a little
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Composable
    private fun Ask(s: AskState, m: androidx.compose.ui.Modifier) = AskContent(
        s, model = "Qwen3.5-4B-Q4_K_M", library = true, evalStatus = null,
        onInput = {}, onAsk = {}, onExample = {}, onStop = {}, onNewChat = {}, onRetry = {}, onThinkHarder = {}, modifier = m,
    )

    @Test fun askEmpty() = shot("ask-empty") { Ask(AskState(), it) }
    @Test fun askEmptyDark() = shot("ask-empty-dark", dark = true) { Ask(AskState(), it) }
    @Test fun askAnswer() = shot("ask-answer") { Ask(AskState(turns = listOf(HTTPS)), it) }
    @Test fun askAnswerDark() = shot("ask-answer-dark", dark = true) { Ask(AskState(turns = listOf(HTTPS)), it) }
    @Test fun askReading() = shot("ask-reading") {
        Ask(AskState(running = true, turns = listOf(TurnUi(SNAKE, prefill = 312 to 649, sources = SNAKE_SOURCES))), it)
    }
    @Test fun askPlaces() = shot("ask-places") { Ask(AskState(turns = listOf(FLORENCE)), it) }

    @Test fun library() = shot("library", tab = 1) {
        LibraryContent(LIBRARY, "Redmi Note 15 Pro · MediaTek MT6789 · 7.6 GB RAM · fast cores 6,7", 7.6, CATALOG, LibraryActions(), it)
    }
    @Test fun libraryDark() = shot("library-dark", dark = true, tab = 1) {
        LibraryContent(LIBRARY, "Redmi Note 15 Pro · MediaTek MT6789 · 7.6 GB RAM · fast cores 6,7", 7.6, CATALOG, LibraryActions(), it)
    }

    private companion object {
        const val SNAKE = "What should I do after a snake bite?"

        fun src(n: Int, title: String, section: String, evidence: String) =
            Source(n, Passage(chunk = n.toLong(), article = n.toLong(), title = title, section = section, text = evidence), evidence)

        val SNAKE_SOURCES = listOf(
            src(1, "Snakebite", "", "A snakebite is an injury caused by the bite of a snake. The most common symptom of any snakebite is overwhelming fear."),
            src(2, "Snakebite", "Treatment", "Keep the bitten limb still and below the heart, remove rings and watches, and get to hospital as fast as possible; do not cut the wound or try to suck out the venom."),
        )

        val HTTPS = TurnUi(
            question = "How does HTTPS keep my connection private?",
            answer = """
                HTTPS is ordinary HTTP sent through **TLS**, which encrypts everything between your browser and the website [1].

                - **Certificate:** the server shows a certificate signed by a certificate authority, so the browser can check it is really talking to that site [2].
                - **Key exchange:** both sides agree on a shared secret, usually with Diffie–Hellman, without ever sending it over the network [1].
                - **Encryption:** pages, cookies and passwords travel encrypted with that key, so someone on the same Wi-Fi only sees which server you connect to [3].

                It does *not* hide which site you visit, and it can't protect you from a site that is itself malicious [3].
            """.trimIndent(),
            sources = listOf(
                src(1, "Transport Layer Security", "Description", "Client-server applications use the TLS protocol to communicate across a network in a way designed to prevent eavesdropping and tampering."),
                src(2, "Public key certificate", "TLS/SSL server certificate", "In TLS, a server is required to present a certificate as part of the initial connection setup. A client connecting to that server will perform the certification path validation algorithm."),
                src(3, "HTTPS", "Security", "HTTPS encrypts the URL path, query string, headers and cookies, but the host name and the server's IP address remain visible to an observer."),
            ),
            searchMs = 182,
            usedLibrary = true,
            stats = CompletionStats(n_prefill = 649, t_prefill_ms = 48_400.0, n_gen = 214, t_gen_ms = 59_000.0, t_first_token_ms = 48_900.0),
        )

        val FLORENCE = TurnUi(
            question = "Vegetarian food near the Duomo in Florence",
            answer = "Closest first: **Il Giardino Verde** is a vegetarian restaurant about 300 m away [P1], and **Bottega dell'Orto** a vegan café near the market [P2]. Florence's cooking is rich in bean and vegetable dishes such as *ribollita* [1].",
            placeSearch = PlaceIntent(listOf("vegetarian", "vegan"), listOf("restaurant", "cafe"), "the Duomo in Florence"),
            placeCenter = GeoPoint(43.7731, 11.2560, "Florence Cathedral"),
            places = listOf(
                Place("Il Giardino Verde", null, "vegetarian_restaurant", 0.0, 0.0, "Via dei Servi", "Florence", null, 0.9, 310.0),
                Place("Bottega dell'Orto", null, "vegan_cafe", 0.0, 0.0, "Via Faenza", "Florence", "+39 055 000000", 0.8, 720.0),
            ),
            sources = listOf(src(1, "Tuscan cuisine", "Soups", "Ribollita is a hearty Tuscan bread soup made with cannellini beans, cavolo nero and vegetables.")),
        )

        val LIBRARY = LibraryState(
            models = listOf(LibFile("m4", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_000_000)),
            packs = listOf(LibFile("w", "wiki.db", 18_401_787_904), LibFile("p", "places.db", 3_120_000_000)),
            loadedModel = "m4", openPack = "w", openPlaces = "p",
            modelInfo = ModelInfo(desc = "qwen35 4B Q4_K - Medium", size_bytes = 2_740_000_000, n_params = 4_210_000_000, n_ctx = 4096, threads = 2, batch_threads = 8, system = "CPU : NEON = 1 | ARM_FMA = 1 | DOTPROD = 1 | REPACK = 1"),
            loadNote = "Auto: 4,096 context, weights repacked (3.9 GB of 7.6 GB).",
            packInfo = "Wikipedia: 6.4 million articles, 18.4 GB",
            placesInfo = "Places: 41 million points of interest, 3.1 GB",
        )

        val CATALOG = listOf(
            CatalogItem("qwen-4b", "model", "Qwen3.5 4B (balanced)", "Qwen3.5-4B-Q4_K_M.gguf", "https://x", 2_740_000_000, 7.0, "Apache-2.0", "Reads sources and answers with citations."),
            CatalogItem("qwen-9b", "model", "Qwen3.5 9B (best answers)", "Qwen3.5-9B-Q4_K_M.gguf", "https://x", 5_680_000_000, 11.0, "Apache-2.0", "Most accurate; needs a 12 GB phone."),
        )
    }
}
