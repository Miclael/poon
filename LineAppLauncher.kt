// 檔名：LineAppLauncher.kt
// 功能：最穩健的啟動 LINE（正式版 / Lite / 明確 MainActivity / 深連結 / Play 商店）

package com.scancode.myapp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast

object LineAppLauncher {

    private const val TAG = "LineAppLauncher"
    private const val PKG_LINE = "jp.naver.line.android"
    private const val PKG_LINE_LITE = "com.linecorp.linelite"

    /**
     * 嘗試啟動 LINE。成功回傳 true，否則 false（已嘗試導向商店）。
     */
    fun launch(context: Context): Boolean {
        // 0) 有裝嗎？（藉由 queries 宣告才能查到）
        val hasLine = isInstalled(context, PKG_LINE)
        val hasLite = isInstalled(context, PKG_LINE_LITE)

        // 1) 先走 getLaunchIntentForPackage（最快）
        getLaunchIntent(context, PKG_LINE)?.let { return start(context, it, "已啟動 LINE") }
        getLaunchIntent(context, PKG_LINE_LITE)?.let { return start(context, it, "已啟動 LINE Lite") }

        // 2) 明確找出該套件的 LAUNCHER Activity，再手動組 Component（有些機型 getLaunchIntent 取不到）
        getMainActivityIntent(context, PKG_LINE)?.let { return start(context, it, "已啟動 LINE（明確主 Activity）") }
        getMainActivityIntent(context, PKG_LINE_LITE)?.let { return start(context, it, "已啟動 LINE Lite（明確主 Activity）") }

        // 3) 已安裝 → 試 line:// 深連結
        if (hasLine || hasLite) {
            val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("line://nv/chat"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (deepLink.resolveActivity(context.packageManager) != null) {
                return start(context, deepLink, "以深連結開啟 LINE")
            }
        }

        // 4) 最後備援：導向商店頁（若真的沒裝）
        return openStore(context, if (hasLite) PKG_LINE_LITE else PKG_LINE)
    }

    private fun start(context: Context, intent: Intent, toast: String): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startActivity 失敗: ${e.message}", e)
            false
        }
    }

    private fun getLaunchIntent(context: Context, pkg: String): Intent? {
        return try {
            context.packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Throwable) {
            Log.w(TAG, "getLaunchIntentForPackage 失敗: $pkg, ${e.message}")
            null
        }
    }

    /** 明確尋找套件的 MAIN/LAUNCHER Activity，手動組 ComponentName 來啟動。 */
    private fun getMainActivityIntent(context: Context, pkg: String): Intent? {
        return try {
            val pm = context.packageManager
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = pkg
            }
            val resolveInfos = pm.queryIntentActivities(queryIntent, 0)
            val activityInfo = resolveInfos.firstOrNull()?.activityInfo ?: return null
            val component = ComponentName(activityInfo.packageName, activityInfo.name)
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component?.let { setComponent(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getMainActivityIntent 失敗: $pkg, ${e.message}")
            null
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun openStore(context: Context, pkg: String): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(market)
            Toast.makeText(context, "未安裝，已導向商店頁", Toast.LENGTH_LONG).show()
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(web)
                Toast.makeText(context, "未安裝，已開啟商店網頁", Toast.LENGTH_LONG).show()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "開啟商店失敗: ${e.message}", e)
                false
            }
        }
    }
}
