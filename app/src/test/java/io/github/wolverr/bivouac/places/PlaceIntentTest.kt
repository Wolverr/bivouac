package io.github.wolverr.bivouac.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceIntentTest {
    private val nouns = setOf("restaurant", "pharmacy", "museum", "cafe", "hotel", "atm", "toilet", "station", "drugstore", "supermarket")

    private fun parse(q: String) = PlaceIntent.parse(q, nouns)

    @Test
    fun vitaliksExample() {
        val i = parse("Vegetarian restaurants near the Duomo in Florence?")
        assertNotNull(i)
        assertEquals("Duomo in Florence", i!!.location)
        assertTrue(i.categoryWords.containsAll(listOf("vegetarian", "vegan", "restaurant")))
    }

    @Test
    fun nearMeUsesTheDevice() {
        val i = parse("where is the nearest pharmacy")
        assertNotNull(i)
        assertTrue(i!!.nearMe)
        assertTrue("pharmacy" in i.categoryWords)
    }

    @Test
    fun cityQueries() {
        assertEquals("Berlin", parse("museums in Berlin")!!.location)
        assertEquals("Shinjuku Station in Tokyo", parse("Where can I find a pharmacy near Shinjuku Station in Tokyo?")!!.location)
        assertTrue(parse("coffee near me")!!.categoryWords.contains("cafe"))
    }

    @Test
    fun ordinaryQuestionsAreNotPlaceQueries() {
        assertNull(parse("What is the capital of Kazakhstan?"))
        assertNull(parse("Explain how a heat pump works"))
        assertNull(parse("Why did the Bronze Age collapse happen?"))
        // A place noun without any location isn't a place search.
        assertNull(parse("How do restaurants make money?"))
        // Questions about what places are or mean need Wikipedia, not the map.
        assertNull(parse("What are the must-see museums in Berlin and what is each known for?"))
        assertNull(parse("What is the history of art in France?"))
    }

    @Test
    fun explanationsAndSightsAreNotMapSearches() {
        val n = nouns + setOf("airport", "metro", "water", "castle", "waterfall")
        assertNull(PlaceIntent.parse("How do I get from the airport to the city centre in Tokyo?", n))
        assertNull(PlaceIntent.parse("How does the metro work in Paris for a tourist?", n))
        assertNull(PlaceIntent.parse("Can you drink the tap water in Vietnam?", n))
        assertNull(PlaceIntent.parse("What castles are near Füssen?", n))
        assertNull(PlaceIntent.parse("Are there any waterfalls near Reykjavík?", n))
        // Still map searches: the way to something, and sights near me.
        assertNotNull(PlaceIntent.parse("How do I get to the nearest pharmacy?", n))
        assertNotNull(PlaceIntent.parse("castles near me", n))
        assertNotNull(PlaceIntent.parse("Where can I find a pharmacy near Shinjuku Station in Tokyo?", n))
    }

    @Test
    fun supermarketsAndDiets() {
        val nouns = nouns + "grocery"
        val i = PlaceIntent.parse("supermarket in Genova", nouns)
        assertEquals("Genova", i!!.location)
        assertTrue("grocery" in i.nounWords)
        assertTrue(PlaceIntent.parse("vegan restaurants in Lisbon", nouns)!!.categoryWords.any { it in PlaceIntent.DIET_WORDS })
        assertTrue(PlaceIntent.parse("pharmacy in Lisbon", nouns)!!.categoryWords.none { it in PlaceIntent.DIET_WORDS })
    }
}
