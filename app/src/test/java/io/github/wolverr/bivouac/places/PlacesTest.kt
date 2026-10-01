package io.github.wolverr.bivouac.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {
    @Test
    fun parsesCoordinatesLines() {
        assertEquals(41.89028 to 12.49222,
            Places.parseCoordinates("Location: Rome\nCoordinates: 41°53′25″N 12°29′32″E / 41.89028°N 12.49222°E"))
        assertEquals(41.40369 to 2.17433,
            Places.parseCoordinates("Geographic coordinates: 41°24′13″N 2°10′28″E / 41.40369°N 2.17433°E"))
        assertEquals(-33.8568 to 151.2153, Places.parseCoordinates("Coordinates: 33.8568°S 151.2153°E"))
        assertNull(Places.parseCoordinates("Location: Shibuya, Tokyo, Japan"))
    }

    @Test
    fun parsesKnownPlaces() {
        val known = Places.parseKnownPlaces("rome\t41.89333\t12.48278\tRome\nbad line\nvienna\t48.20830\t16.37250\tVienna\n")
        assertEquals(setOf("rome", "vienna"), known.keys)
        assertEquals(GeoPoint(41.89333, 12.48278, "Rome"), known["rome"])
    }

    @Test
    fun locationAreaIsTheMostSpecificPart() {
        assertEquals("Shibuya", Places.locationArea("Type: Scramble crossing\nLocation: Shibuya, Tokyo, Japan"))
        assertNull(Places.locationArea("Country: United States\nCity: New York"))
    }
}
