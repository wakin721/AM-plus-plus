package dev.amenhancer.module.host

import org.junit.Assert.*
import org.junit.Test

class HostClippingLeasesTest {
    private class Node {
        var children = true
        var padding = true
        fun lease() = OwnedHostClipping({ children }, { children = it }, { padding }, { padding = it })
        // Host-like equality must not merge different view identities.
        override fun equals(other: Any?) = other is Node
        override fun hashCode() = 1
    }

    @Test fun replacementRestoresOldBranchButKeepsNavigationAndSharedAncestors() {
        val leases = HostClippingLeases<Node> { it.lease() }
        val shared = Node()
        val navigation = Node()
        val oldParent = Node()
        val oldMini = Node()
        val nextMini = Node()
        listOf(navigation, shared, oldParent, oldMini).forEach(leases::allowOverflow)
        // NativeViewState was saved after overflow: its restoration leaves these false.
        oldMini.children = false
        oldMini.padding = false
        leases.retainOnly(listOf(navigation, shared, nextMini))
        assertTrue(oldMini.children && oldMini.padding)
        assertTrue(oldParent.children && oldParent.padding)
        assertFalse(navigation.children || shared.children)
        assertEquals(2, leases.size)
        leases.allowOverflow(nextMini)
        assertEquals(3, leases.size)
        leases.close()
        assertTrue(navigation.children && shared.children && nextMini.children)
        assertEquals(0, leases.size)
    }

    @Test fun repeatedRecreationDropsDetachedNodesAndContentOnlyReplacementKeepsRootLease() {
        val leases = HostClippingLeases<Node> { it.lease() }
        val shared = Node()
        var mini = Node()
        listOf(shared, mini).forEach(leases::allowOverflow)
        repeat(20) {
            val old = mini
            mini = Node()
            leases.retainOnly(listOf(shared, mini))
            assertTrue(old.children && old.padding)
            leases.allowOverflow(mini)
            assertEquals(2, leases.size)
            assertFalse(shared.children)
        }
        leases.retainOnly(listOf(shared, mini))
        assertFalse(mini.children)
        leases.close()
        leases.close()
        assertTrue(shared.children && mini.children)
    }
}
