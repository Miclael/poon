package com.scancode.myapp

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private const val TAG = "AutoMention"

// 專用 DataStore
private val Context.homeScanMessageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_scan_message_store"
)

object HomeScanMessageStore {

    private val KEY_LATEST_MESSAGE = stringPreferencesKey("latest_message")

    /** 寫入最新首頁訊息（可為空，貼字前會再檢查）。 */
    suspend fun setLatest(context: Context, message: String) {
        context.homeScanMessageDataStore.edit { it[KEY_LATEST_MESSAGE] = message }
        Log.d(TAG, "home_message_set(len=${message.length})")
    }

    /** 讀取最新首頁訊息；若尚未寫入則回空字串。 */
    suspend fun getLatest(context: Context): String {
        val prefs = context.homeScanMessageDataStore.data.firstOrNull()
        val v = prefs?.get(KEY_LATEST_MESSAGE) ?: ""
        Log.d(TAG, "home_message_get(len=${v.length})")
        return v
    }

    /** Flow 監聽（如需即時顯示）。 */
    fun latestFlow(context: Context): Flow<String> =
        context.homeScanMessageDataStore.data.map { it[KEY_LATEST_MESSAGE] ?: "" }
}
