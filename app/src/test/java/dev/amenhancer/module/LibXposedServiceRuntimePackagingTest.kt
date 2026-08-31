package dev.amenhancer.module

import org.junit.Assert.assertNotNull
import org.junit.Test

class LibXposedServiceRuntimePackagingTest {
    @Test
    fun `libxposed service listener is available at application runtime`() {
        val listenerClass = Class.forName(
            "io.github.libxposed.service.XposedServiceHelper\$OnServiceListener",
        )

        assertNotNull(listenerClass)
    }
}
