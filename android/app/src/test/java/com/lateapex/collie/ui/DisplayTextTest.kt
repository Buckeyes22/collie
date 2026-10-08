package com.lateapex.collie.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextTest {
    @Test
    fun abbreviatesOnlyTheReportedHome() {
        assertEquals("~", DisplayText.abbreviateHome("/home/operator", "/home/operator"))
        assertEquals("~/git/app", DisplayText.abbreviateHome("/home/operator/git/app", "/home/operator"))
        assertEquals("/mnt/work/app", DisplayText.abbreviateHome("/mnt/work/app", "/home/operator"))
        assertEquals("/home/operators/repo", DisplayText.abbreviateHome("/home/operators/repo", "/home/operator"))
        assertEquals("~/src/collie", DisplayText.abbreviateHome("/srv/operator/src/collie", "/srv/operator"))
        assertEquals("/srv/operators/repo", DisplayText.abbreviateHome("/srv/operators/repo", "/srv/operator"))
    }

    @Test
    fun anUnknownHomeLeavesThePathAlone() {
        assertEquals("/home/operator/git/app", DisplayText.abbreviateHome("/home/operator/git/app", ""))
    }
}
