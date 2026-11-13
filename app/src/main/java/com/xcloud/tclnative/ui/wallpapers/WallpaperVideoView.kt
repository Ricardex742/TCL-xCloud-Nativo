package com.xcloud.tclnative.ui.wallpapers

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import java.io.File

/** Silent local video loop behind the dashboard; no AudioFocus request or streaming decoder reuse. */
internal class WallpaperVideoView(context: Context): TextureView(context), TextureView.SurfaceTextureListener {
    var onPlaybackState: ((WallpaperVideoController.State) -> Unit)?=null
    private var output: Surface?=null
    private var file: File?=null
    private var presented=false
    private var closed=false
    private var videoWidth=0;private var videoHeight=0
    private val transformMatrix=Matrix()
    private val playback=WallpaperVideoController(factory={
        AndroidVideoEngine(requireNotNull(output)) { w,h -> videoWidth=w;videoHeight=h;fit() }
    },changed={ onPlaybackState?.invoke(it) })
    init {
        isOpaque=false;isFocusable=false;isClickable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener=this
    }
    fun show(file: File?, enabled: Boolean) {
        if(closed) return
        this.file=file;presented=enabled;refresh()
    }
    private fun refresh() { playback.update(file,presented && !closed,output!=null) }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) {
        playback.update(null,false,false);output?.release();output=Surface(texture);fit();refresh()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width: Int,height: Int) { fit() }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        playback.update(null,false,false);output?.release();output=null;return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);fit() }
    private fun fit() {
        if(width<=0 || height<=0 || videoWidth<=0 || videoHeight<=0) return
        val scale=maxOf(width.toFloat()/videoWidth,height.toFloat()/videoHeight)
        val sx=videoWidth*scale/width;val sy=videoHeight*scale/height
        transformMatrix.reset();transformMatrix.setScale(sx,sy,width/2f,height/2f);setTransform(transformMatrix)
    }
    override fun onDetachedFromWindow() {
        playback.update(null,false,false);super.onDetachedFromWindow()
    }
    fun close() {
        if(closed) return
        closed=true;presented=false;file=null;playback.close();output?.release();output=null;onPlaybackState=null
    }
    private class AndroidVideoEngine(private val surface: Surface,private val size: (Int,Int) -> Unit): WallpaperVideoEngine {
        private val player=MediaPlayer()
        private var released=false
        override fun prepare(file: File,prepared: () -> Unit,frame: () -> Unit,failed: () -> Unit) {
            player.setSurface(surface);player.setVolume(0f,0f);player.isLooping=true
            player.setOnPreparedListener { if(!released) { size(it.videoWidth,it.videoHeight);prepared() } }
            player.setOnVideoSizeChangedListener { _,w,h -> if(!released) size(w,h) }
            player.setOnInfoListener { _,what,_ ->
                if(!released && what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) frame()
                false
            }
            player.setOnErrorListener { _,_,_ -> if(!released) failed();true }
            player.setDataSource(file.absolutePath);player.prepareAsync()
        }
        override fun start() { if(!released) player.start() }
        override fun release() {
            if(released) return
            released=true
            player.setOnPreparedListener(null);player.setOnVideoSizeChangedListener(null)
            player.setOnInfoListener(null);player.setOnErrorListener(null);player.release()
        }
    }
}
