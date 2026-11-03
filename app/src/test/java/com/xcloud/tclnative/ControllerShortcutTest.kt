package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test

class ControllerShortcutTest {
    @Test fun eitherOrderOpensMenuOnceAndNeitherMenuButtonReachesTheGame() {
        for ((first, second) in listOf(4 to 8, 8 to 4)) {
            val shortcut = ControllerShortcut()
            assertEquals(ShortcutUpdate(0), shortcut.change(first, true, 0))
            assertEquals(ShortcutUpdate(0, openMenu = true), shortcut.change(second, true, 50))
            assertEquals(ShortcutUpdate(0), shortcut.change(second, true, 100)) // repeat
            assertEquals(ShortcutUpdate(0), shortcut.flush(500))
            assertEquals(ShortcutUpdate(0), shortcut.change(first, false, 600))
            assertEquals(ShortcutUpdate(0), shortcut.change(second, false, 700))
            shortcut.change(second, true, 800)
            assertTrue(shortcut.change(first, true, 850).openMenu)
        }
    }
    @Test fun aQuickSingleTapPreservesBothWireEdgesAndOtherGameplayButtons() {
        val shortcut = ControllerShortcut()
        shortcut.change(4, true, 0)
        val release = shortcut.change(4, false, 50)
        val queue = GamepadInputBuffer()
        queue.update(GamepadState(buttons = 16))
        queue.next()
        queue.update(GamepadState(buttons = 16 or release.pulse))
        queue.update(GamepadState(buttons = 16 or release.forwardedButtons))
        assertEquals(20, queue.next().buttons)
        assertEquals(16, queue.next().buttons)
        assertFalse(release.openMenu)
        assertNull(shortcut.flushAtMs)
    }
    @Test fun aHeldMenuOrViewIsDeliveredOnceTheChordWindowExpires() {
        for (button in listOf(4, 8)) {
            val shortcut = ControllerShortcut()
            shortcut.change(button, true, 1000)
            assertEquals(0, shortcut.flush(1149).forwardedButtons)
            assertEquals(button, shortcut.flush(1150).forwardedButtons)
            assertEquals(ShortcutUpdate(0), shortcut.change(button, false, 2000))
        }
    }
    @Test fun holdingStartThenPressingSelectLaterKeepsNormalMenuBehavior() {
        val shortcut = ControllerShortcut()
        shortcut.change(4, true, 0)
        assertEquals(4, shortcut.flush(150).forwardedButtons)
        assertEquals(ShortcutUpdate(12), shortcut.change(8, true, 300))
        assertEquals(ShortcutUpdate(4), shortcut.change(8, false, 350))
        assertEquals(ShortcutUpdate(0), shortcut.change(4, false, 400))
    }
    @Test fun focusLossClearsPendingAndConsumedKeysForTheNextSession() {
        val shortcut = ControllerShortcut()
        shortcut.change(4, true, 0)
        shortcut.reset()
        assertEquals(ShortcutUpdate(0), shortcut.flush(500))
        shortcut.change(8, true, 1000)
        assertTrue(shortcut.change(4, true, 1050).openMenu)
        shortcut.reset()
        shortcut.change(4, true, 2000)
        assertEquals(4, shortcut.flush(2200).forwardedButtons)
    }
}
