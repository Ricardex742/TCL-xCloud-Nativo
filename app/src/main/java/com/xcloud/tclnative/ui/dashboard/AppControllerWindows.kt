package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.view.Window
import java.lang.ref.WeakReference

/** Only windows owned by this Activity. Never inject into Android or another application. */
internal object AppControllerWindows {
    private data class Entry(val activity: WeakReference<Activity>,val window: WeakReference<Window>)
    private val entries=mutableListOf<Entry>()
    private val usb=java.util.WeakHashMap<Activity,WeakReference<com.xcloud.tclnative.webrtc.UsbDirectController>>()
    fun usb(activity: Activity,value: com.xcloud.tclnative.webrtc.UsbDirectController) { usb[activity]=WeakReference(value) }
    fun usbActive(activity: Activity?)=activity?.let { usb[it]?.get()?.source!=null }==true
    fun suppressed(activity: Activity?,deviceId: Int)=activity?.let { usb[it]?.get()?.ownsAndroid(deviceId) }==true
    fun attach(activity: Activity,window: Window) { detach(window);entries+=Entry(WeakReference(activity),WeakReference(window)) }
    fun detach(window: Window) { entries.removeAll { it.window.get()==null || it.window.get()===window } }
    fun clear(activity: Activity) { entries.removeAll { it.activity.get()==null || it.activity.get()===activity };usb.remove(activity) }
    fun current(activity: Activity): Window? {
        entries.removeAll { it.window.get()==null || it.activity.get()==null }
        return entries.asReversed().firstOrNull { it.activity.get()===activity && it.window.get()?.decorView?.hasWindowFocus()==true }
            ?.window?.get() ?: activity.window.takeIf { it.decorView.hasWindowFocus() }
    }
}
