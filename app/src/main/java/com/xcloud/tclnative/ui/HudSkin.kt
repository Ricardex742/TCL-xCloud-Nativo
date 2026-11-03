package com.xcloud.tclnative.ui

import android.graphics.*
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.webrtc.*
import kotlin.math.*

/** Original vector skins inspired by the visual language of each genre; no downloaded game assets. */
internal class HudSkin {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER }
    private val path=Path()
    private val icons=HudIcons()
    private val tactical=RadialGradient(-.25f,-.4f,1.6f,intArrayOf(0xff54616a.toInt(),0xff111820.toInt()),null,Shader.TileMode.CLAMP)
    private val blue=LinearGradient(0f,-1f,0f,1f,0xff318bc3.toInt(),0xff10335f.toInt(),Shader.TileMode.CLAMP)
    private val teal=LinearGradient(0f,-1f,0f,1f,0xff26717a.toInt(),0xff082b36.toInt(),Shader.TileMode.CLAMP)
    private val metal=LinearGradient(-1f,-1f,1f,1f,intArrayOf(0xfff1f6fa.toInt(),0xff4a555e.toInt(),0xff141e26.toInt()),floatArrayOf(0f,.2f,1f),Shader.TileMode.CLAMP)
    private val obsidian=RadialGradient(-.2f,-.5f,1.6f,intArrayOf(0xff413940.toInt(),0xff17151f.toInt()),null,Shader.TileMode.CLAMP)
    private val white=0xfff6f7ec.toInt()
    private val gold=0xffe8c16c.toInt()
    private fun circle(r: Float)=path.apply { reset(); addCircle(0f,0f,r,Path.Direction.CW) }
    private fun outline(c: TouchControl,tactical: Boolean): Path {
        path.reset()
        when {
            c.id=="football-mode" -> path.addRoundRect(-1.8f,-.48f,1.8f,.48f,.18f,.18f,Path.Direction.CW)
            c.shape==HudShape.PEDAL -> path.addRoundRect(-.72f,-1f,.72f,1f,.17f,.17f,Path.Direction.CW)
            c.shape==HudShape.SQUARE || (tactical && c.id in setOf("map","pause")) -> {
                val cut=.22f
                path.moveTo(-1f+cut,-1f); path.lineTo(1f,-1f); path.lineTo(1f,1f-cut)
                path.lineTo(1f-cut,1f); path.lineTo(-1f,1f); path.lineTo(-1f,-1f+cut); path.close()
            }
            else -> path.addCircle(0f,0f,1f,Path.Direction.CW)
        }
        return path
    }
    private fun text(canvas: Canvas,value: String,y: Float,size: Float,maxWidth: Float=1.7f,color: Int=white) {
        val alpha=p.alpha
        // Measure at a useful pixel size: hinting sub-pixel text in unit-space distorts its metrics.
        val unit=64f
        canvas.save(); canvas.scale(1f/unit,1f/unit)
        p.style=Paint.Style.FILL; p.shader=null; p.color=color; p.alpha=alpha; p.textSize=size*unit
        val width=p.measureText(value)
        if(width>maxWidth*unit) p.textSize*=maxWidth*unit/width
        canvas.drawText(value,0f,y*unit-(p.ascent()+p.descent())/2,p)
        canvas.restore()
    }
    fun draw(canvas: Canvas,c: TouchControl,preset: HudPreset,state: GamepadState,active: Boolean,selected: Boolean,
             opacity: Int,editor: Boolean,gesture: String?) {
        val family=preset.family
        val cod=family==HudPreset.CALL_OF_DUTY || family==HudPreset.SHOOTER
        val fort=family==HudPreset.FORTNITE
        val football=preset.football
        val race=family==HudPreset.FORZA || family==HudPreset.RACING
        val hades=family==HudPreset.HADES || family==HudPreset.ACTION
        val alpha=(255*opacity.coerceIn(20,100)/100f).toInt()
        val accent=when {
            football -> when(c.id) {
                "shoot","defend-tackle" -> 0xffefb65d.toInt()
                "pass","defend-switch" -> 0xff70d5e5.toInt()
                "through","defend-slide" -> 0xffd7ca70.toInt()
                else -> 0xff8cddaa.toInt()
            }
            cod -> 0xffffcb58.toInt(); fort -> 0xff82e3ff.toInt(); race -> 0xffe2faff.toInt()
            hades -> gold; else -> 0xff67ed97.toInt()
        }
        p.typeface=when {
            hades -> Typeface.create("serif",Typeface.BOLD)
            football -> Typeface.create("sans-serif",Typeface.BOLD)
            race -> Typeface.create("sans-serif-condensed",Typeface.BOLD)
            else -> Typeface.create("sans-serif-condensed",Typeface.BOLD)
        }
        canvas.save(); canvas.translate(c.x,c.y); canvas.scale(c.radius,c.radius)
        p.style=Paint.Style.FILL; p.color=0xff050c12.toInt(); p.alpha=(alpha*.72f).toInt()
        canvas.save(); canvas.translate(.015f,.065f); canvas.drawPath(outline(c,cod),p); canvas.restore()
        p.shader=when { football -> teal; fort -> blue; race -> metal; hades -> obsidian; else -> tactical }
        p.alpha=(alpha*.88f).toInt(); canvas.drawPath(outline(c,cod),p); p.shader=null
        if(active || selected) { p.color=accent; p.alpha=(alpha*.40f).toInt(); canvas.drawPath(outline(c,cod),p) }
        p.style=Paint.Style.STROKE; p.strokeWidth=if(selected) .065f else if(football || race) .055f else .026f
        p.color=if(active || selected || football || hades) accent else white; p.alpha=alpha
        canvas.drawPath(outline(c,cod),p)
        if(c.id=="football-mode") {
            text(canvas,c.label+"  ⇄",.0f,.40f,3.1f,if(preset==preset.family) 0xff9ce6c5.toInt() else 0xffffcf89.toInt())
            p.alpha=alpha; return canvas.restore()
        }
        if(cod || race || hades) {
            p.strokeWidth=.016f; p.color=if(hades) gold else white; p.alpha=(alpha*.55f).toInt()
            if(c.shape==HudShape.CIRCLE) canvas.drawCircle(0f,0f,.88f,p)
            if(cod && c.icon in setOf(HudIcon.FIRE,HudIcon.AIM)) {
                p.strokeWidth=.07f; p.color=if(active) accent else white; p.alpha=alpha
                for(angle in listOf(-120f,-30f,60f,150f)) canvas.drawArc(-1.04f,-1.04f,1.04f,1.04f,angle,36f,false,p)
            }
            if(hades) {
                for(i in 0..3) { canvas.save(); canvas.rotate(i*90f); path.reset(); path.moveTo(0f,-1.12f); path.lineTo(.07f,-1f); path.lineTo(0f,-.89f); path.lineTo(-.07f,-1f); path.close(); p.style=Paint.Style.FILL; canvas.drawPath(path,p); canvas.restore() }
            }
        }
        p.shader=null; p.color=white; p.alpha=alpha
        when(c.kind) {
            TouchKind.LEFT_STICK,TouchKind.RIGHT_STICK -> {
                val x=if(c.kind==TouchKind.LEFT_STICK) state.leftX else state.rightX
                val y=if(c.kind==TouchKind.LEFT_STICK) state.leftY else state.rightY
                p.style=Paint.Style.STROKE; p.strokeWidth=.015f; p.alpha=(alpha*.55f).toInt()
                canvas.drawCircle(0f,0f,.56f,p)
                repeat(4) { i -> canvas.save(); canvas.rotate(i*90f); path.reset(); path.moveTo(-.07f,-.77f); path.lineTo(0f,-.86f); path.lineTo(.07f,-.77f); canvas.drawPath(path,p); canvas.restore() }
                p.style=Paint.Style.FILL; p.shader=if(football) metal else tactical; p.alpha=alpha
                canvas.drawCircle(x*.59f,y*.59f,.38f,p); p.shader=null
                p.style=Paint.Style.STROKE; p.strokeWidth=.025f; p.color=white; p.alpha=alpha
                canvas.drawCircle(x*.59f,y*.59f,.38f,p)
                if(!football && c.kind==TouchKind.RIGHT_STICK) { p.strokeWidth=.035f; canvas.drawLine(-.15f,0f,.15f,0f,p); canvas.drawLine(0f,-.15f,0f,.15f,p) }
            }
            TouchKind.DPAD -> {
                p.style=Paint.Style.FILL; path.reset(); path.moveTo(-.22f,-.7f); path.lineTo(.22f,-.7f); path.lineTo(.22f,-.22f); path.lineTo(.7f,-.22f); path.lineTo(.7f,.22f); path.lineTo(.22f,.22f); path.lineTo(.22f,.7f); path.lineTo(-.22f,.7f); path.lineTo(-.22f,.22f); path.lineTo(-.7f,.22f); path.lineTo(-.7f,-.22f); path.lineTo(-.22f,-.22f); path.close(); canvas.drawPath(path,p)
            }
            else -> {
                if(football && c.id !in setOf("map","pause")) {
                    text(canvas,(gesture ?: c.label).uppercase(),.05f,.32f,1.65f)
                } else if(c.icon==HudIcon.TEXT) text(canvas,c.label,0f,.62f)
                else {
                    val iconY=if(c.id in setOf("map","pause") || race && c.shape==HudShape.PEDAL) 0f else -.12f
                    canvas.save(); canvas.translate(0f,iconY)
                    p.color=if(active && hades) accent else white; p.alpha=alpha
                    if(cod || fort) icons.drawSolid(canvas,p,c.icon,0f,0f,.55f) else icons.draw(canvas,p,c.icon,0f,0f,.54f)
                    canvas.restore()
                    if(c.id !in setOf("map","pause") && c.shape!=HudShape.PEDAL) text(canvas,gesture ?: c.label,.63f,.24f,1.62f,if(hades) gold else white)
                }
            }
        }
        if(c.gestures.isNotEmpty()) {
            p.style=Paint.Style.FILL; p.color=accent; p.alpha=alpha
            for(direction in c.gestures.keys) {
                canvas.save(); canvas.rotate(when(direction) { HudSwipe.UP -> 0f; HudSwipe.RIGHT -> 90f; HudSwipe.DOWN -> 180f; HudSwipe.LEFT -> 270f })
                path.reset(); path.moveTo(-.09f,-.87f); path.lineTo(0f,-1.02f); path.lineTo(.09f,-.87f); path.close(); canvas.drawPath(path,p); canvas.restore()
            }
        }
        if(editor && c.action!=null) {
            p.alpha=255
            text(canvas,c.action.label,1.24f,.22f,2.7f)
        }
        p.alpha=255; p.shader=null; canvas.restore()
    }
}
