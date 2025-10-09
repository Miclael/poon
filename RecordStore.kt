package com.scancode.myapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 檔名：RecordStore.kt
// 功能：記錄功能的永久儲存（DataStore + JSON），含一次性從舊版搬資料

private val Context.recordDataStore by preferencesDataStore(name = "record_store")

data class Record(val ts: Long, val text: String) {
    fun timeStr(): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).format(Date(ts))
}

object RecordStore {
    private val gson = Gson()
    private val KEY = stringPreferencesKey("records_json")
    private const val MAX_RECORDS = 500

    suspend fun load(context: Context): List<Record> {
        val prefs = context.recordDataStore.data.first()
        val json = prefs[KEY] ?: "[]"
        return runCatching {
            gson.fromJson<List<Record>>(json, object : TypeToken<List<Record>>() {}.type)
        }.getOrElse { emptyList() }
    }

    suspend fun save(context: Context, list: List<Record>) {
        context.recordDataStore.edit { it[KEY] = gson.toJson(list.take(MAX_RECORDS)) }
    }

    suspend fun append(context: Context, text: String, ts: Long = System.currentTimeMillis()) {
        val cur = load(context)
        val upd = (listOf(Record(ts, text)) + cur).take(MAX_RECORDS)
        save(context, upd)
    }

    suspend fun deleteAt(context: Context, index: Int) {
        val cur = load(context)
        if (index in cur.indices) {
            val next = cur.toMutableList().also { it.removeAt(index) }
            save(context, next)
        }
    }

    suspend fun clear(context: Context) {
        save(context, emptyList())
    }

    /** 一次性舊資料搬家：從 SharedPreferences 的 loadMessageList 搬到 DataStore（若目前為空）。 */
    suspend fun importLegacyIfEmpty(context: Context) {
        val cur = load(context)
        if (cur.isNotEmpty()) return

        // 舊版格式：每筆 "時間|內容"
        val legacy = runCatching { loadMessageList(context) }.getOrElse { emptyList() }
        if (legacy.isEmpty()) return

        val mapped = legacy.map { s ->
            val parts = s.split("|", limit = 2)
            val timeStr = parts.getOrNull(0)?.trim().orEmpty()
            val content = parts.getOrNull(1)?.trim().orEmpty()
            val ts = runCatching {
                SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN).parse(timeStr)?.time
            }.getOrNull() ?: System.currentTimeMillis()
            Record(ts, content)
        }
        save(context, mapped)

        // 能呼叫到舊方法就清空之（若無也不會崩）
        runCatching { saveMessageList(context, emptyList()) }
    }
}
