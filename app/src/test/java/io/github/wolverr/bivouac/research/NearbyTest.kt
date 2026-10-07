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
    fun sectionSaysHowFar() {
        assertEquals("12 km from Interlaken", ResearchPipeline.nearbySection(11.6, "Interlaken"))
        assertEquals("under 1 km from Füssen", ResearchPipeline.nearbySection(0.4, "Füssen"))
    }
}
