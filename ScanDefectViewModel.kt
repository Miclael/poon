package com.scancode.myapp

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ScanDefectViewModel : ViewModel() {
    val partNumber = MutableStateFlow("")
    val orderNumber = MutableStateFlow("")
    val defectOptions = MutableStateFlow<List<String>>(emptyList())
    val selectedDefect = MutableStateFlow("")
    val singleReport = MutableStateFlow("")
    val batchReport = MutableStateFlow("")

    val defectMessages = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())
    val savedMessages = MutableStateFlow<List<String>>(emptyList())
}   // 如有需要可加這行
    // ...其他欄位都可依需求再加

