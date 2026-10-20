package io.github.wolverr.bivouac.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnwrapLinesTest {
    @Test
    fun joinsWrappedLines() {
        assertEquals(
            "expand the EIP-7702 transaction with a list of accounts the caller wishes",
            unwrapLines("expand the EIP-7702 transaction with a list of\naccounts the caller wishes"),
        )
    }

    @Test
    fun keepsParagraphsAndRows() {
        val text = "Status: Final.\n\nA related issue.\nPergamon Museum: art\nBode Museum: sculpture"
        assertEquals(text, unwrapLines(text))
    }
}
