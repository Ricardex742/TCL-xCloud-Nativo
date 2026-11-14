package com.xcloud.tclnative.settings

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Public console-size images from Xbox's wallpaper catalog, not native console animation packages. */
object OfficialXboxWallpapers {
    const val SOURCE="https://www.xbox.com/en-US/wallpapers"
    private val assetPath=Regex("/assets/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}\\.(png|jpe?g)",RegexOption.IGNORE_CASE)
    @Volatile private var cached: List<SeriesWallpaper>?=null
    fun trustedAsset(value: String): Boolean=try {
        val uri=URI(value)
        value.length<=4096 && uri.scheme=="https" && uri.host=="assets.xboxservices.com" &&
            uri.rawUserInfo==null && uri.rawFragment==null && uri.port in setOf(-1,443) && assetPath.matches(uri.rawPath ?: "")
    } catch(_: Exception) { false }
    fun entry(name: String,url: String,preview: String?=null): SeriesWallpaper? {
        if(name.isBlank() || name.length>240 || !trustedAsset(url) || preview?.let { !trustedAsset(it) }==true) return null
        val uri=URI(url)
        val identity="https://${uri.host}${uri.rawPath}"
        val digest=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex="0123456789abcdef"
        val id="official:"+buildString(24) {
            repeat(12) { index ->
                val byte=digest[index].toInt() and 255
                append(hex[byte ushr 4]);append(hex[byte and 15])
            }
        }
        return SeriesWallpaper(id,name,artUrl=url,category=WallpaperCategory.OFFICIAL,previewUrl=preview)
    }
    fun read(context: Context): List<SeriesWallpaper> = cached ?: synchronized(this) {
        cached ?: try {
            val raw=context.assets.open("wallpapers/xbox-official.json").use { stream ->
                val bytes=stream.readBytes();check(bytes.size<=1024*1024);bytes.toString(Charsets.UTF_8)
            }
            parse(raw).also { cached=it }
        } catch(_: Exception) { emptyList() }
    }
    internal fun parse(raw: String): List<SeriesWallpaper> {
        val data=JSONObject(raw)
        require(data.getInt("schema")==1 && data.getString("source")==SOURCE && data.getString("mediaType")=="static-image")
        val items=data.getJSONArray("items");require(items.length()<=1024)
        return (0 until items.length()).mapNotNull { i ->
            val item=items.getJSONObject(i)
            entry(item.getString("name"),item.getString("image"),item.optString("preview").takeIf { it.isNotBlank() })
                ?.takeIf { it.id==item.getString("id") }
        }.distinctBy { it.id }
    }
}
