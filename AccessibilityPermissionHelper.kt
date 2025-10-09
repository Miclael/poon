// 檔名：AccessibilityPermissionHelper.kt
// 功能：權限檢查與快速導引。

package com.scancode.myapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityPermissionHelper {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return isEnabledByAccessibilityManager(context) ||
                isEnabledInSecureSettings(context) ||
                AutoInputService.isServiceAvailable()
    }

    private fun isEnabledByAccessibilityManager(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!am.isEnabled) return false
        val targetPkg = context.packageName
        val targetCls = AutoInputService::class.java.name
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { info ->
            val svc = info.resolveInfo?.serviceInfo
            svc != null && svc.packageName == targetPkg &&
                    (svc.name == targetCls || svc.name.endsWith(".AutoInputService"))
        }
    }

    private fun isEnabledInSecureSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(context, AutoInputService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
