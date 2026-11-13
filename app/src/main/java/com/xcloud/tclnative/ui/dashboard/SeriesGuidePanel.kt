package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*
import kotlinx.coroutines.*

internal object SeriesGuidePanel {
    fun enabled(context: Context)=DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_SERIES &&
        SeriesPersonalizationStore(context).read().guide!=SeriesGuideSide.OFF
    fun show(context: Context,title: String,profile: XboxProfile?,actions: List<ConsoleAction>,back: () -> Unit={}): ConsoleDialog {
        return ConsoleDialog(context,title,ConsolePanelPurpose.GUIDE).apply {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val posters=PosterLoader(context,scope)
            profile?.let {
                val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
                val avatar=FrameLayout(context).apply { background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(0xff254a38.toInt()) };clipToOutline=true }
                avatar.addView(text(it.gamertag.take(1),24,true).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
                val image=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                avatar.addView(image,FrameLayout.LayoutParams(-1,-1))
                posters.load(image,it.avatarUrl,96,96)
                row.addView(avatar,LinearLayout.LayoutParams(px(49),px(49)).apply { marginEnd=px(13) })
                val labels=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
                labels.addView(text(it.gamertag.ifBlank { it.displayName },23,true))
                it.subscription?.takeIf(String::isNotBlank)?.let { value ->
                    labels.addView(text(if(value=="FREE") value else "GAME PASS $value",16,color=DashboardTheme.muted))
                }
                row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                body.addView(row,LinearLayout.LayoutParams(-1,px(76)).apply { bottomMargin=px(9) })
            }
            actions.forEach { addMenuItem(it.label,it.action) }
            addCleanup { posters.close();scope.cancel() }
            addAction(UiText.text("Voltar"),back);setOnCancelListener { back() };show()
        }
    }
}
