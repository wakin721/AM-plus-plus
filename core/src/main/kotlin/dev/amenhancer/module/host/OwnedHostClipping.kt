package dev.amenhancer.module.host

/** Overflow owns clipping only; ancestor window visibility and layout remain native. */
class OwnedHostClipping(
    readChildren: () -> Boolean,
    writeChildren: (Boolean) -> Unit,
    readPadding: () -> Boolean,
    writePadding: (Boolean) -> Unit,
) : AutoCloseable {
    private val children = OwnedHostProperty(readChildren, writeChildren)
    private val padding = OwnedHostProperty(readPadding, writePadding)

    fun allowOverflow() {
        children.set(false)
        padding.set(false)
    }

    override fun close() {
        children.close()
        padding.close()
    }
}
