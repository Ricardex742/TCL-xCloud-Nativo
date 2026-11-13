package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import com.xcloud.tclnative.settings.RecreatedBackground
import com.xcloud.tclnative.settings.RecreationFamily
import kotlin.math.*

/** App-authored procedural artwork. No console animation files, bitmap scrolling or per-frame bitmaps. */
internal class RecreatedWallpaperArt(val design: RecreatedBackground): MotionWallpaperArt() {
    override var phase=0f
    var anchorX=.5f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val box=RectF()
    private val matrix=Matrix()
    private val font=Typeface.create("sans-serif-light",Typeface.NORMAL)
    private val meshPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val meshVertices=FloatArray((18+1)*(12+1)*2)
    private var wash: Shader?=null
    private var glow: Shader?=null
    private var metal: Shader?=null
    private var w=0f;private var h=0f
    private val seed=(design.id.hashCode().toLong() and 0xffffffffL).toInt()
    private val x=FloatArray(160);private val y=FloatArray(160);private val sizes=FloatArray(160)
    init {
        var value=seed.toLong() and 0xffffffffL
        fun random(): Float { value=(value*1664525L+1013904223L) and 0xffffffffL;return value/4294967296f }
        repeat(x.size) { x[it]=random();y[it]=random();sizes[it]=random() }
    }
    override fun onBoundsChange(bounds: Rect) {
        w=bounds.width().toFloat();h=bounds.height().toFloat()
        if(w<=0 || h<=0) return
        wash=LinearGradient(0f,0f,w,h,intArrayOf(design.dark,design.primary,design.dark),floatArrayOf(0f,.65f,1f),Shader.TileMode.CLAMP)
        glow=RadialGradient(0f,0f,1f,intArrayOf(design.light,design.primary,Color.TRANSPARENT),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        metal=LinearGradient(0f,-1f,0f,1f,intArrayOf(design.dark,design.light,design.primary,design.light,design.dark),null,Shader.TileMode.CLAMP)
    }
    override fun drawArtwork(canvas: Canvas,bitmap: Bitmap,width: Int,height: Int) {
        if(!animationEnabled || (design.family!=RecreationFamily.VAPOR && design.family!=RecreationFamily.OCEAN)) {
            super.drawArtwork(canvas,bitmap,width,height);return
        }
        val columns=if(lowDetail) 9 else 18;val rows=if(lowDetail) 6 else 12
        val fit=maxOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val dw=bitmap.width*fit;val dh=bitmap.height*fit;val left=(width-dw)/2;val top=(height-dh)/2
        val t=(phase-floor(phase))*2f*PI.toFloat();val amplitude=height*.007f
        var at=0
        for(y in 0..rows) for(x in 0..columns) {
            val u=x.toFloat()/columns;val v=y.toFloat()/rows
            val mask=sin(u*PI).toFloat()*sin(v*PI).toFloat()*if(design.family==RecreationFamily.OCEAN) ((v-.45f)/.55f).coerceIn(0f,1f) else 1f
            meshVertices[at++]=left+u*dw+sin(v*12.56637f+t)*amplitude*mask
            meshVertices[at++]=top+v*dh+cos(u*12.56637f-t*2)*amplitude*.7f*mask
        }
        canvas.drawBitmapMesh(bitmap,columns,rows,meshVertices,0,null,0,meshPaint)
    }
    override fun draw(canvas: Canvas) {
        if(w<=0 || h<=0) return
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.clipRect(0f,0f,w,h)
        val t=(phase-floor(phase))*2f*PI.toFloat()
        paint.style=Paint.Style.FILL;paint.shader=null;paint.alpha=255
        if(hasArtwork) { paint.color=Color.BLACK;paint.alpha=40;canvas.drawRect(0f,0f,w,h,paint) }
        else { paint.shader=wash;canvas.drawRect(0f,0f,w,h,paint);paint.shader=null }
        when(design.family) {
            RecreationFamily.CORE -> core(canvas,t)
            RecreationFamily.ANNIVERSARY -> anniversary(canvas,t)
            RecreationFamily.VAPOR,RecreationFamily.NEBULA -> vapor(canvas,t)
            RecreationFamily.STARMAP -> starmap(canvas,t)
            RecreationFamily.OCEAN,RecreationFamily.SKULL -> ocean(canvas,t)
            RecreationFamily.EMBER -> embers(canvas,t)
            RecreationFamily.TACTICAL -> tactical(canvas,t)
            RecreationFamily.SNOW -> particles(canvas,t,1)
            RecreationFamily.MAGIC -> magic(canvas,t)
            RecreationFamily.ENERGY -> energy(canvas,t)
            RecreationFamily.FIREFLIES -> particles(canvas,t,2)
            RecreationFamily.RIBBONS -> ribbons(canvas,t)
            RecreationFamily.PARTICLES -> particles(canvas,t,0)
            RecreationFamily.DUALITY -> duality(canvas,t)
            RecreationFamily.PIXELS -> pixels(canvas,t)
            RecreationFamily.COMMUNITY -> community(canvas,t)
        }
        paint.shader=null;paint.style=Paint.Style.FILL;paint.color=Color.BLACK;paint.alpha=hasArtwork.let { if(it) 15 else 38 }
        canvas.drawRect(0f,0f,w,h,paint);paint.alpha=255;canvas.restoreToCount(save)
    }
    private fun halo(c: Canvas,cx: Float,cy: Float,r: Float,alpha: Int) {
        matrix.reset();matrix.setScale(r,r);matrix.postTranslate(cx,cy);glow?.setLocalMatrix(matrix)
        paint.style=Paint.Style.FILL;paint.shader=glow;paint.alpha=alpha
        c.drawCircle(cx,cy,r,paint);paint.shader=null
    }
    private fun mesh(c: Canvas,t: Float) {
        paint.color=design.light;paint.alpha=32;paint.style=Paint.Style.STROKE;paint.strokeWidth=(h/700f).coerceAtLeast(.7f)
        val rows=if(lowDetail) 7 else 11;val cols=if(lowDetail) 11 else 17
        for(row in 0..rows) {
            path.rewind()
            for(col in 0..cols) {
                val px=col*w/cols;val py=row*h/rows+sin(t+col*.65f+row*.5f)*h*.018f
                if(col==0) path.moveTo(px,py) else path.lineTo(px,py)
            };c.drawPath(path,paint)
        }
        for(col in 0..cols) {
            path.rewind()
            for(row in 0..rows) {
                val px=col*w/cols+sin(t+row*.6f)*w*.013f;val py=row*h/rows
                if(row==0) path.moveTo(px,py) else path.lineTo(px,py)
            };c.drawPath(path,paint)
        };paint.style=Paint.Style.FILL
    }
    private fun core(c: Canvas,t: Float) {
        mesh(c,t);val cx=w*anchorX;val cy=h*.44f;val radius=minOf(w*.2f,h*.25f)
        halo(c,cx,cy,radius*2.1f,100)
        repeat(5) { layer ->
            val r=radius*(1.20f-layer*.09f);path.rewind()
            val count=if(lowDetail) 40 else 76
            for(i in 0..count) {
                val a=i*2f*PI.toFloat()/count
                val shape=r*(1f+.11f*sin(a*3+t+layer*.3f)+.055f*cos(a*5-t*2)+.025f*sin(a*7+t*3))
                val px=cx+cos(a)*shape;val py=cy+sin(a)*shape
                if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
            };path.close()
            if(layer==4) {
                matrix.reset();matrix.setScale(radius*1.4f,radius*1.4f);matrix.postTranslate(cx+sin(t)*radius*.14f,cy-cos(t)*radius*.12f)
                glow?.setLocalMatrix(matrix);paint.shader=glow;paint.alpha=255
            } else { paint.shader=null;paint.color=if(layer%2==0) design.primary else design.light;paint.alpha=36+layer*10 }
            c.drawPath(path,paint)
        };paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=radius*.018f;paint.color=design.light;paint.alpha=112
        repeat(3) { i ->
            val save=c.save();c.rotate(sin(t+i)*22f,cx,cy)
            box.set(cx-radius*1.34f,cy-radius*(.94f+i*.12f),cx+radius*1.34f,cy+radius*(.94f+i*.12f))
            c.drawArc(box,25f+sin(t+i)*25f,235f,false,paint);c.restoreToCount(save)
        };paint.style=Paint.Style.FILL
    }
    private fun anniversary(c: Canvas,t: Float) {
        halo(c,w*.51f,h*.43f,h*(.63f+sin(t)*.025f),100);particles(c,t,0)
        if(hasArtwork) return
        val cx=w*.35f;val cy=h*.43f;val r=h*.115f
        halo(c,cx,cy,r*1.8f,120)
        paint.shader=null;paint.color=design.light;paint.alpha=245;paint.style=Paint.Style.STROKE;paint.strokeWidth=r*.085f
        c.drawCircle(cx,cy,r,paint)
        path.rewind();path.moveTo(cx-r*.72f,cy-r*.68f);path.cubicTo(cx-r*.05f,cy-r*.33f,cx+r*.36f,cy+r*.23f,cx+r*.72f,cy+r*.7f)
        path.moveTo(cx+r*.72f,cy-r*.68f);path.cubicTo(cx+r*.05f,cy-r*.33f,cx-r*.36f,cy+r*.23f,cx-r*.72f,cy+r*.7f)
        paint.strokeWidth=r*.18f;c.drawPath(path,paint);paint.style=Paint.Style.FILL
        matrix.reset();matrix.setScale(h*.2f,h*.2f);matrix.postTranslate(0f,cy);metal?.setLocalMatrix(matrix)
        paint.shader=metal;paint.alpha=255;paint.typeface=font;paint.textSize=h*.32f
        c.drawText("25",w*.43f,cy+h*.112f,paint);paint.shader=null
    }
    private fun vapor(c: Canvas,t: Float) {
        val lines=if(lowDetail) 6 else 12
        repeat(lines) { i ->
            path.rewind();val base=h*(i-2f)/(lines-4f)
            path.moveTo(-w*.1f,base)
            path.cubicTo(w*(.15f+.2f*sin(t+i*.4f)),base-h*(.6f+.2f*cos(t+i)),
                w*(.7f+.13f*cos(t-i)),base+h*(.6f+.15f*sin(t-i)),w*1.1f,base+sin(t+i)*h*.18f)
            paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=h*(.045f+(i%3)*.016f)
            paint.color=if(i%3==0) design.light else design.primary;paint.alpha=if(hasArtwork) 15 else if(i%3==0) 78 else 160;c.drawPath(path,paint)
            paint.strokeWidth=h*.004f;paint.color=design.light;paint.alpha=if(hasArtwork) 20 else 105;c.drawPath(path,paint)
        };paint.style=Paint.Style.FILL
        if(design.family==RecreationFamily.NEBULA) particles(c,t,0)
    }
    private fun starmap(c: Canvas,t: Float) {
        particles(c,t,0);val cx=w*.57f;val cy=h*.43f
        paint.color=design.light;paint.alpha=55;paint.style=Paint.Style.STROKE;paint.strokeWidth=1f
        repeat(5) { i ->
            val save=c.save();c.rotate(i*29f+sin(t)*6f,cx,cy)
            box.set(cx-w*(.08f+i*.044f),cy-h*(.10f+i*.04f),cx+w*(.08f+i*.044f),cy+h*(.10f+i*.04f))
            c.drawOval(box,paint);c.restoreToCount(save)
        };paint.style=Paint.Style.FILL
        repeat(5) { i ->
            val a=t*(1+i%2)+i*1.8f
            halo(c,cx+cos(a)*w*(.10f+i*.035f),cy+sin(a)*h*(.13f+i*.035f),h*.014f,220)
        }
    }
    private fun particles(c: Canvas,t: Float,kind: Int) {
        val count=if(lowDetail) 45 else 125
        paint.shader=null;paint.style=Paint.Style.FILL
        repeat(count) { i ->
            val a=t*(1+i%3)+x[i]*6.283185f
            val px=if(kind==1) (x[i]+sin(a)*.025f)*w else x[i]*w+sin(a)*w*.04f
            val py=if(kind==1) ((y[i]+t/6.283185f*(1+i%2))%1f)*h else y[i]*h+cos(a+y[i])*h*.035f
            val pulse=(.5f+.5f*sin(a+y[i]*5f))
            paint.color=if(kind==1) Color.WHITE else design.light
            paint.alpha=(if(kind==2) 40+190*pulse else 28+140*pulse).toInt()
            val r=(.6f+sizes[i]*(if(kind==2) 3.5f else 2.4f))*h/720f
            c.drawCircle(px,py,r.coerceAtLeast(.6f),paint)
        }
    }
    private fun ocean(c: Canvas,t: Float) {
        val bands=if(lowDetail) 4 else 7
        repeat(bands) { band ->
            path.rewind();val base=h*(.53f+band*.066f);path.moveTo(-20f,h)
            for(i in 0..36) {
                val px=i*w/36f;val py=base+sin(i*.31f+t*(1+band%2)+band)*h*(.012f+band*.004f)+cos(i*.6f-t)*h*.006f
                path.lineTo(px,py)
            };path.lineTo(w+20,h);path.close();paint.color=if(band%3==0) design.light else design.primary;paint.alpha=if(hasArtwork) 12+band*3 else 40+band*12;c.drawPath(path,paint)
        }
        halo(c,w*.75f,h*.25f,h*.21f,100)
        if(design.family==RecreationFamily.SKULL && !hasArtwork) {
            val cx=w*.5f;val cy=h*.37f;val r=h*.12f
            halo(c,cx,cy,r*1.8f,120);paint.color=design.light;paint.alpha=190
            box.set(cx-r,cy-r,cx+r,cy+r*.65f);c.drawOval(box,paint)
            box.set(cx-r*.48f,cy,cx+r*.48f,cy+r*1.05f);c.drawRoundRect(box,r*.08f,r*.08f,paint)
            paint.color=design.dark;paint.alpha=255;c.drawCircle(cx-r*.37f,cy-r*.08f,r*.23f,paint);c.drawCircle(cx+r*.37f,cy-r*.08f,r*.23f,paint)
            paint.color=design.light;paint.alpha=(100+80*sin(t)).toInt();c.drawCircle(cx-r*.37f,cy-r*.08f,r*.08f,paint);c.drawCircle(cx+r*.37f,cy-r*.08f,r*.08f,paint)
        }
    }
    private fun embers(c: Canvas,t: Float) {
        repeat(if(lowDetail) 4 else 8) { i ->
            val px=w*(.05f+i*.13f);path.rewind();path.moveTo(px-w*.12f,h*1.05f)
            path.cubicTo(px-w*.06f,h*.6f,px+sin(t+i)*w*.13f,h*.38f,px+cos(t+i)*w*.1f,h*(.30f+.17f*sin(t+i)))
            path.cubicTo(px+w*.08f,h*.66f,px+w*.13f,h*.8f,px+w*.2f,h*1.1f);path.close()
            paint.color=i.let { if(it%2==0) design.primary else design.light };paint.alpha=34;c.drawPath(path,paint)
        }
        val count=if(lowDetail) 40 else 100
        repeat(count) { i ->
            val progress=(y[i]+t/6.283185f*(1+i%2))%1f
            val px=x[i]*w+sin(t*2+i)*w*.025f;val py=h*(1.1f-progress*1.3f)
            paint.color=design.light;paint.alpha=(sin(progress*PI).coerceAtLeast(0.0)*185).toInt()
            box.set(px,py,px+h*.002f,py+h*(.004f+sizes[i]*.012f));c.drawOval(box,paint)
        }
    }
    private fun tactical(c: Canvas,t: Float) {
        mesh(c,t);paint.style=Paint.Style.STROKE;paint.color=design.light;paint.alpha=55;paint.strokeWidth=1.2f
        val cx=w*.68f;val cy=h*.38f;val r=h*.26f
        repeat(3) { c.drawCircle(cx,cy,r*(it+1)/3,paint) }
        paint.style=Paint.Style.FILL;paint.alpha=22;box.set(cx-r,cy-r,cx+r,cy+r);c.drawArc(box,t*57.29578f,40f,true,paint)
        repeat(8) { i ->
            val px=x[i]*w;val py=y[i]*h*.7f
            paint.alpha=(60+45*sin(t+i)).toInt();paint.strokeWidth=1.2f
            c.drawLine(px-6,py,px+6,py,paint);c.drawLine(px,py-6,px,py+6,paint)
        };embers(c,t)
    }
    private fun magic(c: Canvas,t: Float) {
        halo(c,w*.61f,h*.4f,h*.43f,95)
        paint.style=Paint.Style.STROKE;paint.color=design.light;paint.strokeWidth=h*.002f
        repeat(4) { i ->
            val save=c.save();val cx=w*.61f;val cy=h*.4f;c.rotate((if(i%2==0) 1 else -1)*t*57.29578f,cx,cy)
            val r=h*(.13f+i*.055f);box.set(cx-r,cy-r,cx+r,cy+r);paint.alpha=80-i*12
            c.drawArc(box,i*22f,260f-i*20,false,paint)
            repeat(8) { n -> val a=n*PI.toFloat()/4;c.drawLine(cx+cos(a)*r*.94f,cy+sin(a)*r*.94f,cx+cos(a)*r*1.05f,cy+sin(a)*r*1.05f,paint) }
            c.restoreToCount(save)
        };paint.style=Paint.Style.FILL;particles(c,t,2)
    }
    private fun energy(c: Canvas,t: Float) {
        repeat(if(lowDetail) 12 else 28) { i ->
            val u=(x[i]+t/6.283185f*(1+i%2))%1f;val px=u*w*1.4f-w*.2f;val py=y[i]*h
            paint.color=if(i%3==0) design.light else design.primary;paint.alpha=(70+70*sin(t+i)).toInt();paint.strokeWidth=h*(.001f+sizes[i]*.004f)
            c.drawLine(px,py,px+w*(.08f+sizes[i]*.17f),py-h*.12f,paint)
        };halo(c,w*.7f,h*.32f,h*.4f,60)
    }
    private fun ribbons(c: Canvas,t: Float) {
        repeat(7) { i ->
            path.rewind();val offset=i*h*.11f;path.moveTo(-w*.1f,h*.85f+offset)
            path.cubicTo(w*.2f,h*(-.25f+.17f*sin(t+i))+offset,w*.6f,h*(1.1f+.16f*cos(t+i))-offset,w*1.15f,h*.15f+offset)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=h*(.05f+i*.006f);paint.color=if(i%2==0) design.primary else design.light;paint.alpha=90;c.drawPath(path,paint)
            paint.strokeWidth=h*.002f;paint.alpha=150;c.drawPath(path,paint)
        };paint.style=Paint.Style.FILL
    }
    private fun duality(c: Canvas,t: Float) {
        val middle=w*(.5f+.08f*sin(t));path.rewind();path.moveTo(middle-w*.1f,0f)
        path.cubicTo(middle+w*.2f,h*.3f,middle-w*.2f,h*.7f,middle+w*.1f,h);path.lineTo(w,h);path.lineTo(w,0f);path.close()
        paint.color=design.light;paint.alpha=if(hasArtwork) 36 else 90;c.drawPath(path,paint)
        energy(c,t);paint.color=design.light;paint.style=Paint.Style.STROKE;paint.strokeWidth=h*.003f;paint.alpha=160
        path.rewind();path.moveTo(middle-w*.1f,0f);path.cubicTo(middle+w*.2f,h*.3f,middle-w*.2f,h*.7f,middle+w*.1f,h);c.drawPath(path,paint);paint.style=Paint.Style.FILL
    }
    private fun pixels(c: Canvas,t: Float) {
        repeat(if(lowDetail) 24 else 65) { i ->
            val a=t*(1+i%2)+i*.4f;val px=x[i]*w+sin(a)*w*.04f;val py=y[i]*h+cos(a)*h*.07f
            val s=h*(.009f+sizes[i]*.028f);paint.color=if(i%3==0) design.light else design.primary;paint.alpha=(40+65*(1+sin(a))).toInt()
            box.set(px,py,px+s,py+s);c.drawRect(box,paint)
            if(i%4==0) { paint.color=design.light;c.drawRect(px+s*.25f,py-s*.25f,px+s*.7f,py+s*.75f,paint) }
        }
    }
    private fun community(c: Canvas,t: Float) {
        if(hasArtwork) { halo(c,w*.72f,h*.2f,h*.24f,45);particles(c,t,2);return }
        val cx=w*.5f;val cy=h*.40f;val r=h*.21f;halo(c,cx,cy,r*1.8f,90)
        val save=c.save();path.rewind();path.addCircle(cx,cy,r,Path.Direction.CW);c.clipPath(path)
        repeat(9) { i ->
            path.rewind();val base=cy-r+i*r*.27f;path.moveTo(cx-r*1.3f,base)
            path.cubicTo(cx-r*.3f,base+sin(t+i)*r*.6f,cx+r*.4f,base+cos(t+i)*r*.65f,cx+r*1.3f,base)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=r*.30f
            paint.color=if(i%3==0) design.light else if(i%3==1) design.primary else Color.WHITE;paint.alpha=150;c.drawPath(path,paint)
        };c.restoreToCount(save);paint.style=Paint.Style.STROKE;paint.strokeWidth=r*.035f;paint.color=design.light;paint.alpha=190;c.drawCircle(cx,cy,r,paint)
        paint.strokeWidth=r*.13f;paint.color=design.dark;paint.alpha=200
        c.drawLine(cx-r*.63f,cy-r*.63f,cx+r*.63f,cy+r*.63f,paint);c.drawLine(cx+r*.63f,cy-r*.63f,cx-r*.63f,cy+r*.63f,paint)
        paint.style=Paint.Style.FILL;particles(c,t,2)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}
