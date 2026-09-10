package com.lateapex.collie.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructiveInputTest {
    @Test
    fun flagsTheWebClientsReviewedDestructiveCorpus() {
        val positives = listOf(
            "rm -rf /" to "rm -r",
            "rm -fr build" to "rm -r",
            "rm --recursive ./tmp" to "rm -r",
            "sudo systemctl restart nginx" to "sudo",
            "git push --force origin main" to "git push --force",
            "git push -f" to "git push --force",
            "npm publish --force" to "--force",
            "dd if=/dev/zero of=/dev/sda" to "dd if=",
            "mkfs.ext4 /dev/sdb1" to "mkfs",
            ":> /etc/passwd" to "system path",
            "echo x > /dev/sda" to "system path",
            "cat > /" to "system path",
        )
        positives.forEach { (input, reason) ->
            assertTrue("Expected '$input' to include '$reason'", DestructiveInput.reason(input)?.contains(reason) == true)
        }
    }

    @Test
    fun leavesTheWebClientsReviewedInnocentCorpusAlone() {
        listOf(
            "assume the tests pass",
            "let's play sudoku",
            "the deploy was forced through",
            "rm file.txt",
            "rm -i old.log",
            "ls -la",
            "git push origin main",
            "git status",
            "echo hello > /tmp/out.txt",
            "cat README.md",
            "print the sum of the array",
            "run the format check",
        ).forEach { assertNull(it, DestructiveInput.reason(it)) }
    }
}
