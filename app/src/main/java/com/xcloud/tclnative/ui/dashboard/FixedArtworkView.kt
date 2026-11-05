package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** These views have fixed parent-supplied bounds; changing a cover must not remeasure the entire grid. */
internal class FixedArtworkView(context: Context): ImageView(context) {
    private var changingImage=false
    override fun setImageBitmap(bitmap: Bitmap?) {
        val previous=changingImage; changingImage=true
        try { super.setImageBitmap(bitmap) } finally { changingImage=previous }
    }
    override fun setImageDrawable(drawable: Drawable?) {
        val previous=changingImage; changingImage=true
        try { super.setImageDrawable(drawable) } finally { changingImage=previous }
    }
    override fun requestLayout() { if(!changingImage) super.requestLayout() }
}
