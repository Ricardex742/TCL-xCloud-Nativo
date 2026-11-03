package com.xcloud.tclnative.home

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.xcloud.tclnative.R
import com.xcloud.tclnative.signaling.GameTitle

/** Uses the launcher's supported APIs: pinned tiles, or the Android TV favorites channel. */
class HomeShortcuts(private val context: Context) {
    private val prefs = context.getSharedPreferences("home_shortcuts", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("favorites_enabled", false)
    val supportsChannel: Boolean get() = Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val supportsPin: Boolean get() = Build.VERSION.SDK_INT >= 26 &&
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    fun enable(value: Boolean) { check(prefs.edit().putBoolean("favorites_enabled", value).commit()) }

    fun pin(game: GameTitle, bitmap: Bitmap?): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !supportsPin) return false
        val shortcut = ShortcutInfo.Builder(context, "game-" + game.titleId)
            .setShortLabel(game.name.take(80)).setLongLabel("Jogar ${game.name}")
            .setIcon(bitmap?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.drawable.brand_logo))
            .setIntent(GameLaunchLink.intent(context, game.titleId)).build()
        return context.getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut, null)
    }

    /** Call on IO. The TV provider exposes only rows owned by this application. */
    fun sync(games: List<GameTitle>): Long? {
        if (Build.VERSION.SDK_INT < 26 || !supportsChannel) return null
        return syncChannel(if (enabled) games.filter { GameLaunchLink.valid(it.titleId) } else emptyList())
    }

    fun requestVisible(channel: Long) {
        if (Build.VERSION.SDK_INT >= 26) TvContract.requestChannelBrowsable(context, channel)
    }

    @RequiresApi(26)
    private fun syncChannel(games: List<GameTitle>): Long? {
        val resolver = context.contentResolver
        var channel: Long? = null
        resolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "internal_provider_id"), null, null, null)?.use { rows ->
            while (rows.moveToNext()) if (rows.getString(1) == CHANNEL_KEY) channel = rows.getLong(0)
        }
        if (!enabled) {
            channel?.let { resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, it), null, null) }
            return null
        }
        if (channel == null) {
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Favoritos xCLOUD")
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_KEY)
                put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.toUri(Intent.URI_INTENT_SCHEME))
            }
            channel = resolver.insert(TvContract.Channels.CONTENT_URI, values)?.let(ContentUris::parseId)
                ?: throw java.io.IOException("A TV não criou o canal de favoritos.")
            // A channel logo is required by Android TV launchers before browsing is enabled.
            resolver.openOutputStream(TvContract.buildChannelLogoUri(channel!!))?.use { output ->
                context.resources.openRawResource(R.drawable.brand_logo).use { it.copyTo(output) }
            }
        }
        val channelId = channel!!
        val existing = mutableMapOf<String, Long>()
        resolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId), arrayOf("_id", "internal_provider_id"), null, null, null)?.use { rows ->
            while (rows.moveToNext()) existing[rows.getString(1)] = rows.getLong(0)
        }
        games.forEachIndexed { index, game ->
            val values = programValues(context, channelId, game, games.size - index)
            val row = existing.remove(game.titleId)
            if (row == null) {
                checkNotNull(resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)) { "A TV não criou o atalho do jogo." }
            } else resolver.update(TvContract.buildPreviewProgramUri(row), values, null, null)
        }
        existing.values.forEach { resolver.delete(TvContract.buildPreviewProgramUri(it), null, null) }
        return channelId
    }

    companion object {
        private const val CHANNEL_KEY = "xcloud-favorites-v1"
        @RequiresApi(26)
        fun programValues(context: Context, channel: Long, game: GameTitle, weight: Int): ContentValues = ContentValues().apply {
            put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channel)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, game.titleId)
            put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CLIP)
            put(TvContract.PreviewPrograms.COLUMN_TITLE, game.name)
            put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, "Jogar na nuvem com xCLOUD")
            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, GameLaunchLink.intent(context, game.titleId).toUri(Intent.URI_INTENT_SCHEME))
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, game.posterUrl ?: game.tileUrl
                ?: Uri.parse("android.resource://${context.packageName}/${R.drawable.brand_logo}").toString())
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_2_3)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight)
            put(TvContract.PreviewPrograms.COLUMN_LIVE, 0)
        }
    }
}
