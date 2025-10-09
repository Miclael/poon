// 檔名：SmartMentionMemory.kt
// 功能：LINE 自動標記的「智慧記憶 + 位置記憶 + 自適應策略」模組（增強版）。
// 相容性：保留既有 API（recordGroupUsed / recordMentionResult / recordScrollSteps / getEstimatedScrollSteps）。
// 新增：getAdaptiveSearchStrategy()，依據成功率與步數統計回傳 timeout / maxRetries / estimatedSteps。
// 規則：不做模糊比對；僅提供流程「等待/捲動」的參數建議，完全符合精準比對要求。

package com.scancode.myapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val Context.smartStore by preferencesDataStore(name = "smart_mention_memory_enhanced")

object SmartMentionMemory {

    // ========= 統計資料類型 =========
    private val gson = Gson()

    private val GROUP_META_KEY = stringPreferencesKey("group_meta") // Map<String, GroupMeta>
    private fun memberMetaKey(group: String) = stringPreferencesKey("member_meta::$group") // Map<member, MemberMeta>
    private fun stepsKey(group: String) = stringPreferencesKey("member_steps::$group")     // Map<member, StepsStat>

    /** 群組層級統計（使用頻率、最近使用時間） */
    data class GroupMeta(
        var useCount: Int = 0,
        var lastUsedTs: Long = 0L
    )

    /** 成員層級統計（成功/失敗、最近成功/失敗時間） */
    data class MemberMeta(
        var success: Int = 0,
        var fail: Int = 0,
        var lastSuccessTs: Long = 0L,
        var lastFailTs: Long = 0L
    )

    /** 位置記憶：候選清單捲動步數統計（EWMA） */
    data class StepsStat(
        var avg: Double = 0.0, // 指數移動平均
        var count: Int = 0,    // 累計樣本數
        var last: Int = 0      // 最近一次的實際步數
    )

    /** 自適應策略輸出 */
    data class SearchStrategy(
        val timeoutMs: Long,     // 「@」後等待候選名單的時間
        val maxRetries: Int,     // 在候選容器的最大捲動步數上限
        val estimatedSteps: Int, // 預估應先捲的步數（加速到常見位置）
        val stepIncrement: Int = 1 // 預留欄位；現階段維持 1
    )

    // ========= 既有 API（相容保留） =========

    /** 記錄群組被使用（顯示懸浮窗或執行標記） */
    suspend fun recordGroupUsed(context: Context, group: String, now: Long = System.currentTimeMillis()) {
        val prefs = context.smartStore.data.first()
        val json = prefs[GROUP_META_KEY] ?: "{}"
        val type = object : TypeToken<MutableMap<String, GroupMeta>>() {}.type
        val map: MutableMap<String, GroupMeta> = gson.fromJson(json, type) ?: mutableMapOf()

        val meta = map[group] ?: GroupMeta()
        meta.useCount += 1
        meta.lastUsedTs = now
        map[group] = meta

        context.smartStore.edit { it[GROUP_META_KEY] = gson.toJson(map) }
    }

    /** 記錄單一成員本次標記是否成功（成功/失敗次數與最近時間） */
    suspend fun recordMentionResult(
        context: Context,
        group: String,
        member: String,
        success: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        val key = memberMetaKey(group)
        val prefs = context.smartStore.data.first()
        val json = prefs[key] ?: "{}"
        val type = object : TypeToken<MutableMap<String, MemberMeta>>() {}.type
        val map: MutableMap<String, MemberMeta> = gson.fromJson(json, type) ?: mutableMapOf()

        val meta = map[member] ?: MemberMeta()
        if (success) {
            meta.success += 1
            meta.lastSuccessTs = now
        } else {
            meta.fail += 1
            meta.lastFailTs = now
        }
        map[member] = meta
        context.smartStore.edit { it[key] = gson.toJson(map) }
    }

    /** 成功點選成員後，記錄本次實際捲動步數，用 EWMA 更新平均值 */
    suspend fun recordScrollSteps(context: Context, group: String, member: String, steps: Int) {
        if (steps < 0) return
        val key = stepsKey(group)
        val prefs = context.smartStore.data.first()
        val json = prefs[key] ?: "{}"
        val type = object : TypeToken<MutableMap<String, StepsStat>>() {}.type
        val map: MutableMap<String, StepsStat> = gson.fromJson(json, type) ?: mutableMapOf()

        val stat = map[member] ?: StepsStat()
        val alpha = 0.4  // 新樣本權重（可微調：0.3~0.5）
        stat.avg = if (stat.count == 0) steps.toDouble() else (1 - alpha) * stat.avg + alpha * steps
        stat.count += 1
        stat.last = steps
        map[member] = stat

        context.smartStore.edit { it[key] = gson.toJson(map) }
    }

    /** 取得預估的捲動步數（四捨五入 avg；若沒有資料則回傳 0） */
    suspend fun getEstimatedScrollSteps(context: Context, group: String, member: String): Int {
        val key = stepsKey(group)
        val prefs = context.smartStore.data.first()
        val json = prefs[key] ?: "{}"
        val type = object : TypeToken<Map<String, StepsStat>>() {}.type
        val map: Map<String, StepsStat> = gson.fromJson(json, type) ?: emptyMap()
        val avg = map[member]?.avg ?: 0.0
        return avg.roundToInt().coerceAtLeast(0)
    }

    // ========= 新增 API：自適應策略 =========
    /**
     * 回傳該「群組+成員」的搜尋策略（等待/最大捲動/預估步數）。
     * 設計原則：
     *  - 成功率高 + 樣本多：縮短 timeout，維持或略降 maxRetries。
     *  - 成功率低 / 樣本少：拉長 timeout，增加 maxRetries。
     *  - 位置記憶（avg）直接作為 estimatedSteps（上限保護）。
     */
    suspend fun getAdaptiveSearchStrategy(
        context: Context,
        group: String,
        member: String
    ): SearchStrategy {
        // 讀取步數統計
        val stepsAvg = runCatching { getEstimatedScrollSteps(context, group, member) }.getOrNull() ?: 0

        // 讀取成功率（若尚無資料，視為 0.5）
        val (succ, fail) = getMemberSuccessFail(context, group, member)
        val total = (succ + fail).coerceAtLeast(0)
        val successRate = if (total == 0) 0.5 else succ.toDouble() / total

        // 既有基準（與你的程式一致）
        val BASE_TIMEOUT = 900L
        val BASE_MAX_STEPS = 160

        // 超參：邏輯權重（可依實測微調）
        val MIN_TIMEOUT = 600L
        val MAX_TIMEOUT = 1200L
        val MIN_MAX_STEPS = 120
        val MAX_MAX_STEPS = 220

        // 依據成功率與樣本量估計等待時間
        val timeout = when {
            total >= 6 && successRate >= 0.8 -> (BASE_TIMEOUT * 0.85).toLong()
            total >= 6 && successRate <= 0.4 -> (BASE_TIMEOUT * 1.15).toLong()
            total == 0 -> BASE_TIMEOUT
            else -> BASE_TIMEOUT
        }.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

        // 依據成功率與樣本量調整最大捲動步數
        val maxRetries = when {
            total >= 6 && successRate >= 0.8 -> (BASE_MAX_STEPS * 0.9).roundToInt()
            total >= 6 && successRate <= 0.4 -> (BASE_MAX_STEPS * 1.15).roundToInt()
            total == 0 -> BASE_MAX_STEPS
            else -> BASE_MAX_STEPS
        }.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS)

        // 預估步數：取 avg；若沒有資料則 0；並做上限保護
        val estimated = stepsAvg.coerceIn(0, BASE_MAX_STEPS)

        return SearchStrategy(
            timeoutMs = timeout,
            maxRetries = maxRetries,
            estimatedSteps = estimated,
            stepIncrement = 1
        )
    }

    // ========= 查詢工具（除錯或 UI 顯示可用；不影響流程） =========
    suspend fun getGroupMeta(context: Context): Map<String, GroupMeta> {
        val prefs = context.smartStore.data.first()
        val json = prefs[GROUP_META_KEY] ?: "{}"
        val type = object : TypeToken<Map<String, GroupMeta>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    suspend fun getMemberMeta(context: Context, group: String): Map<String, MemberMeta> {
        val key = memberMetaKey(group)
        val prefs = context.smartStore.data.first()
        val json = prefs[key] ?: "{}"
        val type = object : TypeToken<Map<String, MemberMeta>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    // ========= 內部：原始資料讀取 =========
    private suspend fun getMemberSuccessFail(context: Context, group: String, member: String): Pair<Int, Int> {
        val key = memberMetaKey(group)
        val prefs = context.smartStore.data.first()
        val json = prefs[key] ?: "{}"
        val type = object : TypeToken<Map<String, MemberMeta>>() {}.type
        val map: Map<String, MemberMeta> = gson.fromJson(json, type) ?: emptyMap()
        val meta = map[member]
        return Pair(meta?.success ?: 0, meta?.fail ?: 0)
    }
}
