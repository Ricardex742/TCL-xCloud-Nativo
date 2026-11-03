package com.xcloud.tclnative.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.xcloud.tclnative.touch.HudIcon

/** Small vector action icons, drawn in one Android canvas pass with no downloaded skins. */
internal class HudIcons {
    private val path=Path()
    fun draw(canvas: Canvas,paint: Paint,icon: HudIcon,x: Float,y: Float,size: Float) {
        canvas.save(); canvas.translate(x,y); canvas.scale(size,size)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=.13f; paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND
        fun line(x1: Float,y1: Float,x2: Float,y2: Float)=canvas.drawLine(x1,y1,x2,y2,paint)
        fun lines(vararg points: Float) { path.reset(); path.moveTo(points[0],points[1]); var i=2; while(i<points.size) { path.lineTo(points[i],points[i+1]); i+=2 }; canvas.drawPath(path,paint) }
        fun arrow() { lines(-.65f,.0f,.65f,0f,.15f,-.45f); line(.65f,0f,.15f,.45f) }
        when(icon) {
            HudIcon.FIRE -> { canvas.rotate(35f); lines(-.28f,.65f,-.28f,-.1f,0f,-.65f,.28f,-.1f,.28f,.65f,-.28f,.65f); line(-.28f,.25f,.28f,.25f) }
            HudIcon.AIM -> { canvas.drawCircle(0f,0f,.48f,paint); line(-.85f,0f,-.28f,0f); line(.28f,0f,.85f,0f); line(0f,-.85f,0f,-.28f); line(0f,.28f,0f,.85f) }
            HudIcon.JUMP,HudIcon.SPRINT,HudIcon.DASH -> {
                canvas.drawCircle(.15f,-.6f,.16f,paint); lines(-.5f,-.2f,-.1f,-.4f,.2f,-.05f,.6f,-.35f)
                lines(-.1f,-.4f,-.32f,.12f,.15f,.35f,.55f,.62f); lines(-.32f,.12f,-.6f,.55f,-.85f,.55f)
                if(icon!=HudIcon.JUMP) { line(-.85f,-.25f,-.55f,-.25f); line(-.95f,.05f,-.7f,.05f) }
            }
            HudIcon.CROUCH -> { canvas.drawCircle(.25f,-.55f,.17f,paint); lines(.15f,-.3f,-.25f,.05f,.35f,.3f,.65f,.6f); lines(-.25f,.05f,-.6f,.45f,-.15f,.65f); line(-.1f,-.2f,.6f,-.1f) }
            HudIcon.RELOAD,HudIcon.REWIND -> {
                canvas.drawArc(-.65f,-.65f,.65f,.65f,35f,280f,false,paint); lines(-.2f,-.7f,.4f,-.7f,.4f,-.15f)
                if(icon==HudIcon.REWIND) { lines(.15f,-.25f,-.25f,0f,.15f,.25f) }
            }
            HudIcon.USE -> { canvas.drawRoundRect(-.5f,-.65f,.5f,.65f,.10f,.1f,paint); line(.1f,0f,.4f,0f) }
            HudIcon.WEAPON -> { lines(-.7f,-.1f,.55f,-.1f,.7f,-.3f,.65f,.2f,.05f,.2f,-.15f,.65f,-.4f,.65f,-.35f,.2f,-.7f,.2f,-.7f,-.1f) }
            HudIcon.MELEE -> { lines(-.7f,.7f,.2f,-.25f); lines(-.1f,-.55f,.5f,-.65f,.7f,-.1f); line(.2f,-.25f,.55f,-.6f) }
            HudIcon.BUILD,HudIcon.WALL,HudIcon.FLOOR -> {
                canvas.drawRect(-.65f,-.55f,.65f,.55f,paint)
                line(-.65f,0f,.65f,0f); line(0f,-.55f,0f,0f); line(-.3f,0f,-.3f,.55f); line(.35f,0f,.35f,.55f)
                if(icon==HudIcon.FLOOR) line(-.65f,.72f,.65f,.72f)
            }
            HudIcon.STAIRS -> lines(-.7f,.65f,-.7f,.2f,-.25f,.2f,-.25f,-.2f,.2f,-.2f,.2f,-.6f,.7f,-.6f)
            HudIcon.ROOF -> { lines(-.75f,.45f,0f,-.6f,.75f,.45f,-.75f,.45f); line(0f,-.6f,0f,.45f) }
            HudIcon.GRENADE -> { canvas.drawOval(-.4f,-.2f,.4f,.7f,paint); lines(-.15f,-.2f,-.15f,-.55f,.3f,-.55f,.45f,-.1f); line(.3f,-.55f,.55f,-.7f) }
            HudIcon.LEFT,HudIcon.RIGHT -> { if(icon==HudIcon.LEFT) canvas.rotate(180f); lines(-.4f,-.65f,.3f,0f,-.4f,.65f) }
            HudIcon.ACCELERATE,HudIcon.GEAR_UP -> { lines(-.55f,.25f,0f,-.35f,.55f,.25f); lines(-.55f,.65f,0f,.05f,.55f,.65f) }
            HudIcon.BRAKE -> { line(-.55f,-.1f,.55f,-.1f); line(-.55f,.25f,.55f,.25f); line(-.55f,.60f,.55f,.60f) }
            HudIcon.GEAR_DOWN -> { lines(-.55f,-.65f,0f,-.05f,.55f,-.65f); lines(-.55f,-.25f,0f,.35f,.55f,-.25f) }
            HudIcon.HANDBRAKE -> { canvas.drawCircle(0f,0f,.6f,paint); line(0f,-.3f,0f,.2f); canvas.drawPoint(0f,.4f,paint) }
            HudIcon.CAMERA -> { canvas.drawRoundRect(-.7f,-.4f,.7f,.5f,.12f,.12f,paint); canvas.drawCircle(0f,.05f,.26f,paint); lines(-.35f,-.4f,-.2f,-.65f,.2f,-.65f,.35f,-.4f) }
            HudIcon.PASS,HudIcon.SHOOT,HudIcon.THROUGH,HudIcon.CROSS -> {
                canvas.drawCircle(-.35f,.3f,.28f,paint)
                canvas.save(); canvas.translate(.18f,-.28f); canvas.scale(.65f,.65f); canvas.rotate(if(icon==HudIcon.CROSS) -55f else if(icon==HudIcon.THROUGH) -20f else 0f); arrow(); canvas.restore()
                if(icon==HudIcon.SHOOT) { line(-.9f,.1f,-.75f,.1f); line(-.95f,.4f,-.8f,.4f) }
            }
            HudIcon.SWITCH -> { canvas.save(); canvas.translate(0f,-.3f); arrow(); canvas.restore(); canvas.save(); canvas.rotate(180f); canvas.translate(0f,-.3f); arrow(); canvas.restore() }
            HudIcon.SHIELD -> lines(-.6f,-.5f,0f,-.7f,.6f,-.5f,.5f,.25f,0f,.7f,-.5f,.25f,-.6f,-.5f)
            HudIcon.ATTACK -> { lines(-.65f,.65f,.55f,-.55f,.7f,-.7f,.55f,-.25f,-.25f,.55f); line(-.5f,.15f,-.1f,.55f) }
            HudIcon.SPECIAL -> lines(-.1f,-.8f,-.65f,.05f,-.1f,.05f,-.25f,.8f,.65f,-.2f,.1f,-.2f,-.1f,-.8f)
            HudIcon.CAST -> { lines(0f,-.75f,.6f,0f,0f,.75f,-.6f,0f,0f,-.75f); line(-.25f,0f,.25f,0f); line(0f,-.3f,0f,.3f) }
            HudIcon.PAUSE -> { line(-.24f,-.6f,-.24f,.6f); line(.24f,-.6f,.24f,.6f) }
            HudIcon.MAP -> { lines(-.65f,-.5f,-.2f,-.65f,.2f,-.45f,.65f,-.6f,.65f,.5f,.2f,.65f,-.2f,.45f,-.65f,.6f,-.65f,-.5f); line(-.2f,-.65f,-.2f,.45f); line(.2f,-.45f,.2f,.65f) }
            HudIcon.TEXT -> Unit
        }
        canvas.restore()
    }
}
