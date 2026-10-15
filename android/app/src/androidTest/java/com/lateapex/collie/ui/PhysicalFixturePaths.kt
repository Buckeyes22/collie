package com.lateapex.collie.ui

import androidx.test.platform.app.InstrumentationRegistry

/** Explicit host fixture paths; public test sources never embed a workstation account. */
internal object PhysicalFixturePaths {
    val home: String
        get() = requireNotNull(InstrumentationRegistry.getArguments().getString("liveFixtureHome")) {
            "Physical acceptance requires an explicit liveFixtureHome for the owned disposable fixture"
        }.also {
            require(it.matches(Regex("/home/[a-z_][a-z0-9_-]{0,31}"))) {
                "liveFixtureHome must name one bounded Linux user home, without traversal or shell syntax"
            }
        }

    val root: String get() = "$home/collie-completion-20261013"
    val datedRootPattern: Regex get() = Regex("${Regex.escape(home)}/collie-completion-[0-9]{8}")
}
