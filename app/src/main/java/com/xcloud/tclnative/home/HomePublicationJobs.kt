package com.xcloud.tclnative.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Metadata refreshes may coalesce with each other, but must never cancel a user's visibility request. */
class HomePublicationJobs(private val scope: CoroutineScope,private val debounceMs: Long = 350) {
    private val lock=Mutex()
    private var automatic: Job?=null
    private var requested: Job?=null
    fun schedule(userRequest: Boolean,action: suspend () -> Unit) {
        if(userRequest) {
            requested?.cancel()
            requested=scope.launch { lock.withLock { action() } }
        } else {
            automatic?.cancel()
            automatic=scope.launch { delay(debounceMs); lock.withLock { action() } }
        }
    }
    fun cancel() { automatic?.cancel(); requested?.cancel() }
}
