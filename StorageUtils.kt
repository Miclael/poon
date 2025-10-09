package com.scancode.myapp

import android.content.Context

fun saveDefectList(context: Context, defectList: List<String>) {
    context.getSharedPreferences("defect", Context.MODE_PRIVATE)
        .edit()
        .putString("defect_list", defectList.joinToString("|"))
        .apply()
}

fun loadDefectList(context: Context): List<String> {
    val str = context.getSharedPreferences("defect", Context.MODE_PRIVATE)
        .getString("defect_list", "") ?: ""
    return if (str.isBlank()) emptyList() else str.split("|")
}

@Deprecated("已改用 DataStore 的 RecordStore，請勿再呼叫；僅供相容搬遷用")
fun saveMessageList(context: Context, msgList: List<String>) {
    context.getSharedPreferences("messages", Context.MODE_PRIVATE)
        .edit()
        .putString("message_list", msgList.joinToString("\u0001"))
        .apply()
}

@Deprecated("已改用 DataStore 的 RecordStore，請勿再呼叫；僅供相容搬遷用")
fun loadMessageList(context: Context): List<String> {
    val str = context.getSharedPreferences("messages", Context.MODE_PRIVATE)
        .getString("message_list", "") ?: ""
    return if (str.isBlank()) emptyList() else str.split("\u0001")
}
