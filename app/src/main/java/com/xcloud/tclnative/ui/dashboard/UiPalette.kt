package com.xcloud.tclnative.ui.dashboard

import android.graphics.Color
import com.xcloud.tclnative.settings.UiTheme

/** Original green colors remain exact; the other palettes tint only these interface roles. */
internal enum class UiColor(val original: Int) {
    ACCENT(0xff54de69.toInt()), MUTED(0xffa6b8ab.toInt()), SURFACE(0xff202a24.toInt()), BORDER(0xff3a5944.toInt()),
    PANEL_TOP(0xff202c24.toInt()), PANEL_BOTTOM(0xff151d18.toInt()),
    BACKGROUND_START(0xff1b211d.toInt()), BACKGROUND_MID(0xff18261e.toInt()), BACKGROUND_END(0xff102c1a.toInt()),
    BACKGROUND_GLOW(0x443c994d), BACKGROUND_RIBBON(0xff244832.toInt()),
    AVATAR(0xff53655a.toInt()), TAB_BAR(0xff27352c.toInt()), CATALOG_NOTICE(0xffcdd9d1.toInt()),
    HEADER_ICON(0xff334139.toInt()), PROFILE_FOCUS(0x66325540), SUBSCRIPTION(0xff256038.toInt()),
    COVER_TOP(0xff3c5144.toInt()), COVER_BOTTOM(0xff141d17.toInt()), FAVORITE_BACKGROUND(0xc0142018.toInt()),
    QUEUE_SHADE_TOP(0xf5151d17.toInt()), QUEUE_SHADE_MID(0xcf121d16.toInt()), QUEUE_SHADE_END(0xa6163821.toInt()),
    QUEUE_POSTER(0xff304036.toInt()), QUEUE_PANEL(0xe61b2920.toInt()), QUEUE_TEXT(0xffbfd1c4.toInt()),
    QUEUE_BORDER(0xff3c5b46.toInt()), QUEUE_MUTED(0xff8f9f94.toInt()),
    QUEUE_ACTIVE(0xb4286539.toInt()), QUEUE_INACTIVE(0x881b2b21.toInt()), QUEUE_STEP_BORDER(0xff3b5142.toInt()),
    SEARCH_ACTIVE(0xff284c35.toInt()), SEARCH_FILL(0xff203329.toInt()), SEARCH_BORDER(0xff5c8768.toInt()),
    EDIT_SELECTION(0x6654de69), EDIT_FILL(0xff101a13.toInt()),
    TRANSITION_SHADE_TOP(0x66121b15), TRANSITION_SHADE_MID(0x33121b15), TRANSITION_SHADE_END(0xf5101b14.toInt()),
    LOGO_BACKGROUND(0xff08140d.toInt()), WINDOW_BACKGROUND(0xff121b15.toInt())
}

internal class UiPalette(val theme: UiTheme) {
    private val consoleColors=when(theme) {
            UiTheme.XBOX_360 -> intArrayOf(0xff252d28.toInt(),0xff3a443c.toInt(),0xff1b3020.toInt(),0x447b995a,0xff344936.toInt(),0xff252d26.toInt(),0xff212a23.toInt(),0xff151d17.toInt())
            UiTheme.XBOX_360_NXE -> intArrayOf(0xff202822.toInt(),0xff364334.toInt(),0xff162d21.toInt(),0x44678b54,0xff344936.toInt(),0xff252d26.toInt(),0xff212a23.toInt(),0xff151d17.toInt())
            UiTheme.XBOX_360_METRO -> intArrayOf(0xff252d28.toInt(),0xff3a443c.toInt(),0xff1b3020.toInt(),0x447b995a,0xff344936.toInt(),0xff252d26.toInt(),0xff212a23.toInt(),0xff151d17.toInt())
            UiTheme.XBOX_ONE -> intArrayOf(0xff111812.toInt(),0xff15251a.toInt(),0xff0b2113.toInt(),0x443c8d40,0xff1c3624.toInt(),0xff1c2a20.toInt(),0xff17251c.toInt(),0xff101a14.toInt())
            UiTheme.XBOX_SERIES -> intArrayOf(0xff0a1214.toInt(),0xff112127.toInt(),0xff082b2b.toInt(),0x443a9b81,0xff173e37.toInt(),0xff1a292b.toInt(),0xff142426.toInt(),0xff0c181b.toInt())
            UiTheme.XBOX_ORIGINAL -> intArrayOf(0xff080e05.toInt(),0xff15270b.toInt(),0xff07250a.toInt(),0x444e921a,0xff254018.toInt(),0xff1c2a13.toInt(),0xff16240f.toInt(),0xff0b1508.toInt())
            else -> null
    }
    private val colors=UiColor.entries.associateWith { role ->
        val base=role.original
        when {
            theme==UiTheme.GREEN -> base
            consoleColor(role)!=null -> consoleColor(role)!!
            (base and 0xffffff) == (UiColor.ACCENT.original and 0xffffff) ->
                (base and 0xff000000.toInt()) or (theme.accent and 0xffffff)
            else -> {
                val hsv=FloatArray(3);Color.colorToHSV(base,hsv)
                hsv[0]=theme.hue;hsv[1]=(hsv[1]*theme.saturation).coerceIn(0f,1f)
                Color.HSVToColor(Color.alpha(base),hsv)
            }
        }
    }
    private fun consoleColor(role: UiColor): Int? {
        val colors=consoleColors ?: return null
        return when(role) {
            UiColor.BACKGROUND_START -> colors[0];UiColor.BACKGROUND_MID -> colors[1];UiColor.BACKGROUND_END -> colors[2]
            UiColor.BACKGROUND_GLOW -> colors[3];UiColor.BACKGROUND_RIBBON -> colors[4];UiColor.SURFACE -> colors[5]
            UiColor.PANEL_TOP -> colors[6];UiColor.PANEL_BOTTOM -> colors[7]
            else -> null
        }
    }
    operator fun get(role: UiColor): Int=colors.getValue(role)
    fun replacement(color: Int,next: UiPalette): Int =
        listOf(UiColor.ACCENT,UiColor.MUTED,UiColor.CATALOG_NOTICE,UiColor.QUEUE_TEXT,UiColor.QUEUE_MUTED,UiColor.EDIT_SELECTION)
            .firstOrNull { colors[it]==color }?.let { next[it] } ?: color
}
