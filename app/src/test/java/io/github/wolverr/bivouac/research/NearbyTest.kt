package io.github.wolverr.bivouac.research

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyTest {
    @Test
    fun stemsMatchOtherForms() {
        assertEquals("hik", ResearchPipeline.stem("hikes"))
        assertEquals("hik", ResearchPipeline.stem("hiking"))
        assertEquals("lak", ResearchPipeline.stem("lakes"))
        assertEquals("templ", ResearchPipeline.stem("temples"))
        assertEquals("castl", ResearchPipeline.stem("castles"))
        assertEquals("waterfall", ResearchPipeline.stem("waterfalls"))
        assertEquals("walk", ResearchPipeline.stem("walks"))
        assertEquals("bus", ResearchPipeline.stem("bus"))
    }

    @Test
    fun sectionSaysHowFarAndWhichWay() {
        assertEquals("12 km southeast of Interlaken", ResearchPipeline.nearbySection(11.6, "Interlaken", "southeast"))
        assertEquals("under 1 km from Füssen", ResearchPipeline.nearbySection(0.4, "Füssen", "north"))
    }

    @Test
    fun firstSentenceSkipsPronunciation() {
        assertEquals(
            "Scuderia Toro Rosso SpA was an Italian Formula One racing team.",
            ResearchPipeline.firstSentence("Scuderia Toro Rosso SpA (Italian pronunciation: [skudeˈriːa]; lit. Red Bull Team) was an Italian Formula One racing team. It raced from 2006."),
        )
    }

    @Test
    fun compassPoints() {
        assertEquals("north", ResearchPipeline.compass(10.0, 0.5))
        assertEquals("west", ResearchPipeline.compass(1.0, -50.0))
        assertEquals("southeast", ResearchPipeline.compass(-10.0, 10.0))
        assertEquals("northwest", ResearchPipeline.compass(10.0, -10.0))
    }
}
