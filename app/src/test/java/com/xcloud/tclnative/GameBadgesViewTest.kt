package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.xcloud.tclnative.ui.dashboard.GameBadgesView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameBadgesViewTest {
    @Test fun productFlagsControlTheImageBadgesAndRebindingDoesNotLeaveStaleMembership() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view=GameBadgesView(c.get())
            val pass=view.getChildAt(0) as ImageView;val series=view.getChildAt(1) as ImageView
            view.bind(true,true,1f)
            assertEquals(View.VISIBLE,view.visibility)
            assertEquals(View.VISIBLE,pass.visibility);assertEquals(View.VISIBLE,series.visibility)
            assertTrue(pass.drawable is BitmapDrawable);assertTrue(series.drawable is BitmapDrawable)
            val passBitmap=(pass.drawable as BitmapDrawable).bitmap
            val seriesBitmap=(series.drawable as BitmapDrawable).bitmap
            assertTrue(passBitmap.width<=512 && seriesBitmap.width<=512)
            assertEquals("Disponível no Game Pass",pass.contentDescription)
            assertEquals("Otimizado para Xbox Series X|S",series.contentDescription)
            val bothOffset=(series.layoutParams as FrameLayout.LayoutParams).leftMargin
            view.bind(false,true,1f)
            assertEquals(View.GONE,pass.visibility);assertEquals(View.VISIBLE,series.visibility)
            assertTrue((series.layoutParams as FrameLayout.LayoutParams).leftMargin<bothOffset)
            view.bind(true,false,1f)
            assertEquals(View.VISIBLE,pass.visibility);assertEquals(View.GONE,series.visibility)
            view.bind(false,false,1f);assertEquals(View.GONE,view.visibility)
            view.bind(true,true,1f)
            assertEquals(bothOffset,(series.layoutParams as FrameLayout.LayoutParams).leftMargin)
            assertSame(passBitmap,(pass.drawable as BitmapDrawable).bitmap)
            assertSame(seriesBitmap,(series.drawable as BitmapDrawable).bitmap)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun bothBadgesFitThePosterStripWithoutCroppingAndKeepTheirAspectRatio() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view=GameBadgesView(c.get())
            for(scale in listOf(.5f,1f,1.5f,3f)) {
                val width=(202*scale).toInt();val height=(52*scale).toInt()
                view.bind(true,true,scale)
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                val pass=view.getChildAt(0) as ImageView;val series=view.getChildAt(1) as ImageView
                assertTrue(pass.left>=0 && pass.top>=0 && pass.right<=series.left)
                assertTrue(series.top>=0 && series.right<=width && series.bottom<=height)
                assertTrue("Compact poster badges",series.right<=130*scale && pass.height<=30*scale && series.height<=30*scale)
                assertEquals(ImageView.ScaleType.MATRIX,pass.scaleType)
                assertEquals(ImageView.ScaleType.FIT_CENTER,series.scaleType)
            }
            view.bind(true,true,1f)
            view.measure(View.MeasureSpec.makeMeasureSpec(202,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(52,View.MeasureSpec.EXACTLY));view.layout(0,0,202,52)
            val b=Bitmap.createBitmap(202,52,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            assertEquals("Artwork remains visible outside each white logo",0,android.graphics.Color.alpha(b.getPixel(201,51)))
            File("build/reports/dashboard/catalog-badges.png").apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        } finally { c.pause().stop().destroy() }
    }
}
