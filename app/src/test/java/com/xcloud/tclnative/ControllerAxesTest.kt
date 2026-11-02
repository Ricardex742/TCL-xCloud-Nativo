package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControllerAxesTest {
    @Test fun cameraIgnoresLargeHardwareFlatAndPreservesTinyPositiveAndNegativeMotion() {
        val range = ControllerAxisRange(-1f, 1f, .4f)
        assertEquals(.001f, ControllerAxes.stick(.001f, range), .000001f)
        assertEquals(-.001f, ControllerAxes.stick(-.001f, range), .000001f)
        assertEquals(0f, ControllerAxes.stick(0f, range), 0f)
    }
    @Test fun bothTriggersIgnoreTheDriverFlatAndPreserveFirstPressureWithoutAnExtraCurve() {
        val range = ControllerAxisRange(0f, 1f, .25f)
        assertEquals(.001f, ControllerAxes.trigger(.001f, range), .000001f)
        assertEquals(.05f, ControllerAxes.trigger(.05f, range), .000001f)
        assertEquals(0f, ControllerAxes.trigger(0f, range), 0f)
        assertEquals(1f, ControllerAxes.trigger(1f, range), 0f)
    }
    @Test fun onlyTheLeftStickKeepsTheOptionalDriverDeadzone() {
        val range = ControllerAxisRange(-1f, 1f, .15f)
        assertEquals(0f, ControllerAxes.stick(.1f, range, useHardwareDeadzone = true), 0f)
        assertEquals(.1f, ControllerAxes.stick(.1f, range, useHardwareDeadzone = false), 0f)
        assertEquals(.2f, ControllerAxes.stick(.2f, range, useHardwareDeadzone = true), .00001f)
    }
    @Test fun declaredSignedTriggerRangesAreMappedFromRestToFullWithoutAThreshold() {
        val range = ControllerAxisRange(-1f, 1f, .5f)
        assertEquals(0f, ControllerAxes.trigger(-1f, range), 0f)
        assertEquals(.001f, ControllerAxes.trigger(-.998f, range), .000001f)
        assertEquals(.5f, ControllerAxes.trigger(0f, range), 0f)
        assertEquals(1f, ControllerAxes.trigger(1f, range), 0f)
    }
    @Test fun rawUnsignedAndAsymmetricStickRangesAreNormalizedWithoutClippingSmallCameraMoves() {
        assertEquals(0f, ControllerAxes.stick(127.5f, ControllerAxisRange(0f, 255f)), 0f)
        assertEquals(1f, ControllerAxes.stick(255f, ControllerAxisRange(0f, 255f)), 0f)
        assertEquals(-1f, ControllerAxes.stick(0f, ControllerAxisRange(0f, 255f)), 0f)
        assertEquals(-.5f, ControllerAxes.stick(-.4f, ControllerAxisRange(-.8f, 1f)), .00001f)
    }
    @Test fun tinyCameraAndTriggerMovementsActuallySurviveTheTransmittedPacket() {
        val state = GamepadState(rightX = ControllerAxes.stick(.001f, ControllerAxisRange(-1f, 1f, .5f)),
            rightY = ControllerAxes.stick(-.001f, ControllerAxisRange(-1f, 1f, .5f)),
            leftTrigger = ControllerAxes.trigger(.001f, ControllerAxisRange(0f, 1f, .5f)),
            rightTrigger = ControllerAxes.trigger(.001f, ControllerAxisRange(0f, 1f, .5f)))
        val wire = ByteBuffer.wrap(GamepadPacket.encode(state, 1, 0.0)).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(wire.getShort(22).toInt() > 0)
        assertTrue(wire.getShort(24).toInt() > 0) // Y is inverted exactly once by the packet encoder.
        assertTrue((wire.getShort(26).toInt() and 65535) > 0)
        assertTrue((wire.getShort(28).toInt() and 65535) > 0)
    }
    @Test fun invalidValuesAreNeutralAndPhysicalLimitsRemainClamped() {
        assertEquals(0f, ControllerAxes.stick(Float.NaN), 0f)
        assertEquals(0f, ControllerAxes.trigger(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, ControllerAxes.stick(5f), 0f)
        assertEquals(-1f, ControllerAxes.stick(-5f), 0f)
        assertEquals(1f, ControllerAxes.trigger(5f), 0f)
        assertEquals(0f, ControllerAxes.trigger(-5f), 0f)
    }
    @Test fun responsiveInputUsesEightMillisecondSlotsAndKeepsLatestAxesDuringButtonTransitions() {
        assertEquals(120, RealtimeInputPump.INPUT_HZ)
        val queue = GamepadInputBuffer()
        queue.update(GamepadState(buttons = 16, rightX = .1f, rightTrigger = .1f))
        queue.update(GamepadState(buttons = 16, rightX = .8f, rightTrigger = .9f))
        val next = queue.next()
        assertEquals(16, next.buttons)
        assertEquals(.8f, next.rightX, 0f)
        assertEquals(.9f, next.rightTrigger, 0f)
    }
    @Test fun lowLatencyPlayoutHasABoundedWindowAndCanBeRestoredToAdaptive() {
        val trial = VideoLatencyPolicy.fieldTrials(true)
        assertEquals("WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:40/", trial)
        assertEquals("", VideoLatencyPolicy.fieldTrials(false))
        assertFalse(trial.contains("max_ms:0"))
    }
}
