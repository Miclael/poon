package com.scancode.myapp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object LineNotificationBus {
    // 帶 replay 的總線，避免 UI 晚收集漏掉最近幾則訊息
    val messageFlow = MutableSharedFlow<String>(
        replay = 32,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
