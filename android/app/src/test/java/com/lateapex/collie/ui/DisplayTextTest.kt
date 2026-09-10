package com.lateapex.collie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextTest {
    @Test
    fun abbreviatesChrisHomeWithoutTouchingOtherPaths() {
        assertEquals("~", DisplayText.abbreviateHome("/home/chris"))
        assertEquals("~/git/prometheus", DisplayText.abbreviateHome("/home/chris/git/prometheus"))
        assertEquals("/mnt/work/prometheus", DisplayText.abbreviateHome("/mnt/work/prometheus"))
        assertEquals("/home/christine/repo", DisplayText.abbreviateHome("/home/christine/repo"))
        assertEquals("~/src/collie", DisplayText.abbreviateHome("/srv/operator/src/collie", "/srv/operator"))
        assertEquals("/srv/operators/repo", DisplayText.abbreviateHome("/srv/operators/repo", "/srv/operator"))
    }
}
