package com.xcloud.tclnative.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Stop starting catalog requests while streaming; cancellation still works while waiting. */
class CatalogWorkGate {
    private val open=MutableStateFlow(true)
    fun pause() { open.value=false }
    fun resume() { open.value=true }
    suspend fun await() { open.first { it } }
}
