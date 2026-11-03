package com.xcloud.tclnative.webrtc

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Preserve short button presses while coalescing analog motion into its latest position. */
class GamepadInputBuffer {
    private var latest = GamepadState()
    private data class Edge(val buttons: Int,var leftTrigger: Float,var rightTrigger: Float)
    private val edges = ArrayDeque<Edge>()
    private var motionPending=false
    var coalescedEdges = 0L
        private set
    @Synchronized fun update(state: GamepadState): Boolean {
        val edge = state.buttons != latest.buttons || (state.leftTrigger>0)!=(latest.leftTrigger>0) || (state.rightTrigger>0)!=(latest.rightTrigger>0)
        val motion=state.rightX!=latest.rightX || state.rightY!=latest.rightY || state.leftTrigger!=latest.leftTrigger || state.rightTrigger!=latest.rightTrigger
        motionPending=motionPending || motion
        // Strength changes within the same held press are analog motion, not a replay queue.
        if(state.leftTrigger>0 && latest.leftTrigger>0) {
            val pending=edges.descendingIterator()
            while(pending.hasNext()) { val e=pending.next(); if(e.leftTrigger==0f) break; e.leftTrigger=state.leftTrigger }
        }
        if(state.rightTrigger>0 && latest.rightTrigger>0) {
            val pending=edges.descendingIterator()
            while(pending.hasNext()) { val e=pending.next(); if(e.rightTrigger==0f) break; e.rightTrigger=state.rightTrigger }
        }
        latest = state
        if (edge) {
            if (edges.size >= 8) { coalescedEdges += edges.size; edges.clear() }
            edges.addLast(Edge(state.buttons,state.leftTrigger,state.rightTrigger))
        }
        return edge || motion
    }
    @Synchronized fun hasEdges() = edges.isNotEmpty()
    @Synchronized fun hasUrgent() = edges.isNotEmpty() || motionPending
    @Synchronized fun next(): GamepadState {
        motionPending=false
        val edge=edges.pollFirst() ?: return latest
        return latest.copy(buttons=edge.buttons,leftTrigger=edge.leftTrigger,rightTrigger=edge.rightTrigger)
    }
    @Synchronized fun discardBacklog() {
        coalescedEdges += edges.size
        edges.clear()
        motionPending=false
    }
}

data class InputMetrics(val bufferedBytes: Long = 0, val maxSchedulerDelayMs: Double = 0.0,
                        val backpressureSkips: Long = 0, val coalescedEdges: Long = 0, val sentState: GamepadState = GamepadState())

/** Native sends run independently from decoder/default-coroutine CPU load. Never catch up by replaying old ticks. */
class RealtimeInputPump(
    private val bufferedAmount: () -> Long,
    private val send: (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val prioritize: () -> Unit = {},
    initialGamepads: Set<Int> = setOf(0),
    private val touch: NativeTouchBuffer = NativeTouchBuffer(),
    private val inputHz: Int = INPUT_HZ
) {
    init { require(inputHz in 60..INPUT_HZ) }
    private val running = AtomicBoolean(false)
    private val buffers = Array(4) { GamepadInputBuffer() }
    private val active = initialGamepads.toMutableSet()
    private val lock = Any()
    @Volatile var touchWidth=1920
    @Volatile var touchHeight=1080
    @Volatile private var thread: Thread? = null
    @Volatile var metrics = InputMetrics()
        private set
    fun update(state: GamepadState, index: Int = 0) {
        require(index in 0..3)
        synchronized(lock) { active.add(index); if (buffers[index].update(state)) thread?.let(LockSupport::unpark) }
    }
    fun remove(index: Int) { synchronized(lock) { active.remove(index); buffers[index].update(GamepadState()); buffers[index].discardBacklog() } }
    fun pointers(events: List<NativePointer>) { touch.add(events); thread?.let(LockSupport::unpark) }
    fun releaseTouch() { touch.release(); thread?.let(LockSupport::unpark) }
    fun start() {
        check(running.compareAndSet(false, true))
        thread = Thread({
            try {
                prioritize()
                val period = 1_000_000_000L / inputHz
                val started = System.nanoTime()
                var deadline = started
                var sequence = 1 // Client metadata has already used sequence 0.
                var skipped = 0L
                var maxDelay = 0.0
                var windowStarted = started
                var backpressured = false
                while (running.get()) {
                    val now = System.nanoTime()
                    val hasEdges=synchronized(lock) { active.any { buffers[it].hasUrgent() } }
                    if (now < deadline && (backpressured || (!hasEdges && !touch.pending()))) {
                        LockSupport.parkNanos(deadline - now)
                        continue
                    }
                    maxDelay = maxOf(maxDelay, (now - deadline).coerceAtLeast(0) / 1_000_000.0)
                    val queued = bufferedAmount()
                    val gamepadBytes=synchronized(lock) { if(active.isEmpty()) 0 else 1+23*active.size }
                    val contactCount=touch.nextCount()
                    val maximum=maxOf(MAX_BUFFERED_BYTES,2L*(14+gamepadBytes+(if(contactCount==0) 0 else 2+20*contactCount)))
                    if (queued >= maximum) {
                        backpressured=true
                        skipped++
                        synchronized(lock) { active.forEach { buffers[it].discardBacklog() } }
                    } else {
                        backpressured=false
                        val states=synchronized(lock) { active.associateWith { buffers[it].next() } }
                        val pointers=touch.next()
                        if ((states.isNotEmpty() || pointers.isNotEmpty()) && !send(GamepadPacket.encode(states, sequence++, (now - started) / 1_000_000.0,pointers,touchWidth,touchHeight))) {
                            if (running.get()) onFailure()
                            break
                        }
                        metrics = metrics.copy(sentState = states[0] ?: GamepadState())
                    }
                    metrics = metrics.copy(bufferedBytes = queued, maxSchedulerDelayMs = maxDelay,
                        backpressureSkips = skipped, coalescedEdges = synchronized(lock) { buffers.sumOf { it.coalescedEdges } })
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
