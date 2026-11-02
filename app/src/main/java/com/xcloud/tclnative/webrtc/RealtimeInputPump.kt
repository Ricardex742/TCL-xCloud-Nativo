package com.xcloud.tclnative.webrtc

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Preserve short button presses while coalescing analog motion into its latest position. */
class GamepadInputBuffer {
    private var latest = GamepadState()
    private val edges = ArrayDeque<Int>()
    var coalescedEdges = 0L
        private set
    @Synchronized fun update(state: GamepadState): Boolean {
        val edge = state.buttons != latest.buttons
        latest = state
        if (edge) {
            if (edges.size >= 8) { coalescedEdges += edges.size; edges.clear() }
            edges.addLast(state.buttons)
        }
        return edge
    }
    @Synchronized fun hasEdges() = edges.isNotEmpty()
    @Synchronized fun next() = if (edges.isEmpty()) latest else latest.copy(buttons = edges.removeFirst())
    @Synchronized fun discardBacklog() {
        coalescedEdges += edges.size
        edges.clear()
    }
}

data class InputMetrics(val bufferedBytes: Long = 0, val maxSchedulerDelayMs: Double = 0.0,
                        val backpressureSkips: Long = 0, val coalescedEdges: Long = 0, val sentState: GamepadState = GamepadState())

/** Native sends run independently from decoder/default-coroutine CPU load. Never catch up by replaying old ticks. */
class RealtimeInputPump(
    private val bufferedAmount: () -> Long,
    private val send: (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val prioritize: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val buffer = GamepadInputBuffer()
    @Volatile private var thread: Thread? = null
    @Volatile var metrics = InputMetrics()
        private set
    fun update(state: GamepadState) {
        if (buffer.update(state)) thread?.let(LockSupport::unpark)
    }
    fun start() {
        check(running.compareAndSet(false, true))
        thread = Thread({
            try {
                prioritize()
                val period = 1_000_000_000L / INPUT_HZ
                val started = System.nanoTime()
                var deadline = started
                var sequence = 0
                var skipped = 0L
                var maxDelay = 0.0
                var windowStarted = started
                while (running.get()) {
                    val now = System.nanoTime()
                    if (now < deadline && !buffer.hasEdges()) {
                        LockSupport.parkNanos(deadline - now)
                        continue
                    }
                    maxDelay = maxOf(maxDelay, (now - deadline).coerceAtLeast(0) / 1_000_000.0)
                    val queued = bufferedAmount()
                    if (queued >= MAX_BUFFERED_BYTES) {
                        skipped++
                        buffer.discardBacklog()
                    } else {
                        val state = buffer.next()
                        if (!send(GamepadPacket.encode(state, sequence++, (now - started) / 1_000_000.0))) {
                            if (running.get()) onFailure()
                            break
                        }
                        metrics = metrics.copy(sentState = state)
                    }
                    metrics = metrics.copy(bufferedBytes = queued, maxSchedulerDelayMs = maxDelay,
                        backpressureSkips = skipped, coalescedEdges = buffer.coalescedEdges)
                    if (now - windowStarted >= 1_000_000_000) { maxDelay = 0.0; windowStarted = now }
                    // Button edges can wake this thread early; regular reports remain at 250Hz.
                    deadline = if (now >= deadline) now + period else deadline
                }
            } catch (error: Exception) {
                if (running.get()) onFailure()
            } finally {
                running.set(false)
            }
        }, "xCloud-Input").apply { isDaemon = true; start() }
    }
    fun close() {
        running.set(false)
        thread?.let { LockSupport.unpark(it); if (it !== Thread.currentThread()) it.join(250) }
    }
    companion object {
        const val INPUT_HZ = 250
        // At most two reports queued locally before suppressing additional state snapshots.
        const val MAX_BUFFERED_BYTES = 2 * 38L
    }
}
