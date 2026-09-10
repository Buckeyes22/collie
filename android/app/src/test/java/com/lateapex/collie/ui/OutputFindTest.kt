package com.lateapex.collie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFindTest {
    @Test
    fun searchIsLiteralCaseInsensitiveAndNonOverlapping() {
        assertEquals(
            listOf(OutputFind.Match(0, 3), OutputFind.Match(4, 7)),
            OutputFind.matches("A.B a.b aXb", "a.b"),
        )
        assertEquals(listOf(OutputFind.Match(0, 2), OutputFind.Match(2, 4)), OutputFind.matches("aaaa", "aa"))
    }

    @Test
    fun blankSearchAndInvalidLimitReturnNoMatches() {
        assertTrue(OutputFind.matches("output", "  ").isEmpty())
        assertTrue(OutputFind.matches("output", "out", limit = 0).isEmpty())
    }

    @Test
    fun resultLimitAndNavigationWrapInBothDirections() {
        assertEquals(2, OutputFind.matches("x x x", "x", limit = 2).size)
        assertEquals(0, OutputFind.step(3, 2, 1))
        assertEquals(2, OutputFind.step(3, 0, -1))
        assertEquals(-1, OutputFind.step(0, 0, 1))
    }
}
