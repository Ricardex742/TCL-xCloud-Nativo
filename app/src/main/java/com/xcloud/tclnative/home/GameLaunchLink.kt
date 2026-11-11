package com.xcloud.tclnative.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.MainActivity

/** A shortcut contains only a catalog ID. Credentials and service URLs never leave the app. */
object GameLaunchLink {
    private val validId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    fun valid(id: String?) = id?.matches(validId) == true
    fun intent(context: Context, titleId: String): Intent {
        require(valid(titleId))
        return Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("xcloud").authority("play").appendPath(titleId).build(),
            context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    fun parse(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "xcloud" || uri.authority != "play" || uri.query != null || uri.fragment != null) return null
        return uri.pathSegments.singleOrNull()?.takeIf(::valid)
    }
}

/** Survives login/activity recreation; consuming it once prevents a cancelled queue from restarting. */
class PendingGameLaunch(savedId: String? = null) {
    var titleId: String? = savedId?.takeIf(GameLaunchLink::valid)
        private set
    fun receive(intent: Intent?): Boolean {
        val id = GameLaunchLink.parse(intent) ?: return false
        titleId = id
        return true
    }
    fun consume(catalog: List<GameTitle>): GameTitle? {
        val id = titleId ?: return null
        titleId = null
        return catalog.firstOrNull { it.titleId.equals(id, true) }
    }
}
