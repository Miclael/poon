package com.scancode.myapp

import android.content.Context
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// 🔧 Extension property：Context.lineMonitorDataStore
val Context.lineMonitorDataStore: DataStore<Preferences> by preferencesDataStore(name = "line_monitor")

// ✅ 所有需要的 DataStore Keys 定義
val KEY_GROUPS = stringSetPreferencesKey("groups")
val KEY_ENABLED = stringSetPreferencesKey("enabled")
val KEY_MONITOR_RUNNING = booleanPreferencesKey("monitor_running")
val KEY_MESSAGES_JSON = stringPreferencesKey("messages_json")