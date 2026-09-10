package com.lateapex.collie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneKeyQueueTest {
    @Test
    fun idlePressFiresImmediatelyWithoutLeavingQueuedState() {
        val queue = PaneKeyQueue()

        assertEquals(listOf("Space"), queue.press(listOf("Space")))
        assertFalse(queue.composing)
        assertTrue(queue.staged.isEmpty())
    }

    @Test
    fun modifiersComposeInCanonicalOrderAndOnceModifiersAreSpent() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.SHIFT)
        queue.cycle(PaneKeyModifier.CTRL)

        assertNull(queue.press(listOf("F7")))

        assertEquals(listOf("ctrl+shift+F7"), queue.staged)
        assertEquals(PaneModifierMode.OFF, queue.mode(PaneKeyModifier.SHIFT))
        assertEquals(PaneModifierMode.OFF, queue.mode(PaneKeyModifier.CTRL))
    }

    @Test
    fun lockedModifierSurvivesStagingAndSending() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.ALT)
        queue.cycle(PaneKeyModifier.ALT)

        queue.press(listOf("1"))
        assertEquals(listOf("alt+1"), queue.take())

        assertEquals(PaneModifierMode.LOCKED, queue.mode(PaneKeyModifier.ALT))
        assertTrue(queue.composing)
    }

    @Test
    fun presetChordPassesThroughAndQueuePreservesOrder() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.CTRL)

        queue.press(listOf("ctrl+c", "Down", "Enter"))

        assertEquals(listOf("ctrl+c", "ctrl+Down", "ctrl+Enter"), queue.take())
    }

    @Test
    fun clearDropsKeysAndEveryModifierMode() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.CTRL)
        queue.cycle(PaneKeyModifier.CTRL)
        queue.press(listOf("x"))

        queue.clear()

        assertFalse(queue.composing)
        assertTrue(queue.staged.isEmpty())
        PaneKeyModifier.entries.forEach {
            assertEquals(PaneModifierMode.OFF, queue.mode(it))
        }
    }

    @Test
    fun baseInputNormalizesTheLastPrintableCharacterAndSpendsOnceModifiers() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.SHIFT)
        queue.cycle(PaneKeyModifier.CTRL)

        assertTrue(queue.pushBase("aG"))
        assertEquals(listOf("ctrl+shift+g"), queue.staged)
        assertEquals(PaneModifierMode.OFF, queue.mode(PaneKeyModifier.SHIFT))
        assertEquals(PaneModifierMode.OFF, queue.mode(PaneKeyModifier.CTRL))
        assertFalse(queue.pushBase(" "))
        assertFalse(queue.pushBase("\u0080"))
        assertEquals(listOf("ctrl+shift+g"), queue.staged)
    }

    @Test
    fun queuedKeysCanBeRemovedIndividuallyWithoutChangingModifierState() {
        val queue = PaneKeyQueue()
        queue.cycle(PaneKeyModifier.ALT)
        queue.cycle(PaneKeyModifier.ALT)
        queue.press(listOf("1", "2", "3"))

        assertTrue(queue.removeAt(1))
        assertEquals(listOf("alt+1", "alt+3"), queue.staged)
        assertEquals(PaneModifierMode.LOCKED, queue.mode(PaneKeyModifier.ALT))
        assertFalse(queue.removeAt(8))
    }
}
