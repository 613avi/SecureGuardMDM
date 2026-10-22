package com.secureguard.mdm.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** פקודות מרחוק למסך הקיוסק של האתר (רענון, ניקוי נתוני גלישה). */
object KioskWebEvents {
    enum class Event { RELOAD, CLEAR_DATA }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
