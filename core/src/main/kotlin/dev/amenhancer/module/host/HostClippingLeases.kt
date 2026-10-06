package dev.amenhancer.module.host

import java.util.IdentityHashMap

/** Clipping leases follow the current chrome hierarchy, using node identity. */
class HostClippingLeases<K : Any>(private val create: (K) -> OwnedHostClipping) : AutoCloseable {
    private val leases = IdentityHashMap<K, OwnedHostClipping>()
    internal val size: Int get() = leases.size

    fun allowOverflow(node: K) {
        leases.getOrPut(node) { create(node) }.allowOverflow()
    }

    /** Call after restoring the replaced node's other properties. Shared ancestors stay owned. */
    fun retainOnly(nodes: Iterable<K>) {
        val retained = IdentityHashMap<K, Boolean>()
        nodes.forEach { retained[it] = true }
        val entries = leases.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (!retained.containsKey(entry.key)) {
                entry.value.close()
                entries.remove()
            }
        }
    }

    override fun close() {
        leases.values.forEach(AutoCloseable::close)
        leases.clear()
    }
}
