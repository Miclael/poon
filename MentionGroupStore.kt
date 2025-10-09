// 檔名：MentionGroupStore.kt
// 功能：負責儲存與讀取標記群組及成員的資料

package com.scancode.myapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val Context.dataStore by preferencesDataStore(name = "mention_group_store")

object MentionGroupStore {
    private val gson = Gson()

    private val GROUP_NAMES_KEY = stringPreferencesKey("mention_groups")

    private fun memberKey(groupName: String) = stringPreferencesKey("members_$groupName")

    // ✅ 對外公開用的名稱 getAllGroups（給 MentionScreen 用）
    suspend fun getAllGroups(context: Context): List<String> {
        return loadGroupNames(context)
    }

    // 載入所有群組名稱（內部名稱）
    suspend fun loadGroupNames(context: Context): List<String> {
        val prefs = context.dataStore.data.first()
        val json = prefs[GROUP_NAMES_KEY] ?: "[]"
        return gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
    }

    suspend fun addGroup(context: Context, groupName: String) {
        val groups = loadGroupNames(context).toMutableList()
        if (!groups.contains(groupName)) {
            groups.add(groupName)
            context.dataStore.edit { prefs ->
                prefs[GROUP_NAMES_KEY] = gson.toJson(groups)
            }
        }
    }

    suspend fun removeGroup(context: Context, groupName: String) {
        val groups = loadGroupNames(context).toMutableList()
        groups.remove(groupName)
        context.dataStore.edit { prefs ->
            prefs[GROUP_NAMES_KEY] = gson.toJson(groups)
            prefs.remove(memberKey(groupName))
        }
    }

    suspend fun loadMembers(context: Context, groupName: String): List<String> {
        val prefs = context.dataStore.data.first()
        val json = prefs[memberKey(groupName)] ?: "[]"
        return gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
    }

    suspend fun addMember(context: Context, groupName: String, member: String) {
        val members = loadMembers(context, groupName).toMutableList()
        if (!members.contains(member)) {
            members.add(member)
            context.dataStore.edit { prefs ->
                prefs[memberKey(groupName)] = gson.toJson(members)
            }
        }
    }

    suspend fun removeMember(context: Context, groupName: String, member: String) {
        val members = loadMembers(context, groupName).toMutableList()
        members.remove(member)
        context.dataStore.edit { prefs ->
            prefs[memberKey(groupName)] = gson.toJson(members)
        }
    }
}
