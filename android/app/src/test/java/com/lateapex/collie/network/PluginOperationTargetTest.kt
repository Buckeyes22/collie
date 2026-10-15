package com.lateapex.collie.network

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginOperationTargetTest {
    private fun operation(capability: String, target: String) = HostPluginConfigOperation(1, "fixture-request", "succeeded", capability, target, 1, 2)
    @Test fun actualBridgeTargetsAreAcceptedOnlyForTheirDeclaredCapability() {
        listOf("install", "link", "uninstall").forEach {
            assertTrue(operation("pluginOwnerOperations", "plugin:example.collie-pixel-test:$it").valid())
            assertFalse(operation("plugins", "plugin:example.collie-pixel-test:$it").valid())
        }
        assertTrue(operation("plugins", "plugin:example.collie-pixel-test:config-user-dirs").valid())
        assertTrue(operation("configuration", "plugin:example.collie-pixel-test:config:nested/settings.json").valid())
        assertFalse(operation("pluginOwnerOperations", "plugin:example.collie-pixel-test:config-user-dirs").valid())
        assertFalse(operation("plugins", "plugin:example.collie-pixel-test:config:settings.json").valid())
    }
    @Test fun unrelatedAndMalformedTargetsRemainInvalid() {
        listOf("a".repeat(64), "plugin:bad/id:link", "plugin::link", "plugin:example:erase", "plugin:" + "a".repeat(121) + ":link").forEach {
            assertFalse(it, operation("pluginOwnerOperations", it).valid())
        }
        listOf("/etc/config", "../config", "nested/../config", "nested//config", "nested/./config", "a".repeat(513)).forEach {
            assertFalse(it, operation("configuration", "plugin:example:config:$it").valid())
        }
    }
}
