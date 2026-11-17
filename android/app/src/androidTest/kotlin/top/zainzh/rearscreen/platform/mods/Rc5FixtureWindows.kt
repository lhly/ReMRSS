package top.zainzh.rearscreen.platform.mods

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.accessibility.AccessibilityWindowInfo

/** Test-only access to interactive accessibility windows across explicitly selected displays. */
internal object Rc5FixtureWindows {
    fun enableInteractiveWindows(automation: UiAutomation): AutoCloseable {
        val serviceInfo = automation.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.setServiceInfo(serviceInfo)
        return AutoCloseable {
            serviceInfo.flags = originalFlags
            automation.setServiceInfo(serviceInfo)
        }
    }

    fun onDisplay(automation: UiAutomation, displayId: Int): List<AccessibilityWindowInfo> =
        automation.getWindowsOnAllDisplays()[displayId].orEmpty()
}
