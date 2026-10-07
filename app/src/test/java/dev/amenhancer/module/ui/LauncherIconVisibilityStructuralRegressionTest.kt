package dev.amenhancer.module.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class LauncherIconVisibilityStructuralRegressionTest {
    @Test fun `artifact has no desktop or standalone settings entry`() {
        val file = sequenceOf(File("app/src/main/AndroidManifest.xml"), File("../app/src/main/AndroidManifest.xml"))
            .first(File::isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        assertEquals(0, document.getElementsByTagName("activity-alias").length)
        val activities = document.getElementsByTagName("activity")
        val namespace = "http://schemas.android.com/apk/res/android"
        val names = (0 until activities.length).map { index ->
            val activity = activities.item(index) as org.w3c.dom.Element
            assertEquals("true", activity.getAttributeNS(namespace, "excludeFromRecents"))
            assertEquals("@android:style/Theme.Translucent.NoTitleBar", activity.getAttributeNS(namespace, "theme"))
            activity.getAttributeNS(namespace, "name")
        }.toSet()
        assertEquals(setOf(".usb.UsbDirectPermissionActivity", ".config.SettingsBridgeBootstrapActivity"), names)
        assertFalse(file.readText().contains("android.intent.category.LAUNCHER"))
        assertFalse(file.readText().contains("android.intent.action.MAIN"))
    }
}
