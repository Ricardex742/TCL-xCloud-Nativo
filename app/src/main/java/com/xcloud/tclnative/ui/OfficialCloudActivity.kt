package com.xcloud.tclnative.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.xcloud.tclnative.ui.dashboard.ConsoleAction
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.web.OfficialCloudPolicy

/** Optional mobile engine. The official SDK receives DOM PointerEvents directly from Chromium. */
class OfficialCloudActivity: Activity() {
    private lateinit var root: FrameLayout
    private lateinit var browser: WebView
    private var popup: WebView?=null
    private var customView: View?=null
    private var customCallback: WebChromeClient.CustomViewCallback?=null
    private var progress: ProgressBar?=null
    private var menu: android.app.Dialog?=null
    private var menuButton: View?=null
    private var initialUrl=""
    private var alive=true
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        initialUrl=OfficialCloudPolicy.launchUrl(intent.getStringExtra("game_name") ?: "Game",intent.getStringExtra("product_id"))
            ?: OfficialCloudPolicy.searchUrl(intent.getStringExtra("game_name") ?: "")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        try { browser=newBrowser(); root.addView(browser,FrameLayout.LayoutParams(-1,-1)) }
        catch(_: RuntimeException) { error("O Android System WebView não está disponível. Atualize o WebView/Chrome ou use o motor nativo."); return }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        root.addView(progress,FrameLayout.LayoutParams(-1,dp(3),Gravity.TOP))
        val button=DashboardTheme.button(this,"⋯") { showMenu() }.apply {
            contentDescription="Menu do cliente oficial"; setPadding(0,0,0,0); alpha=.8f
        }
        menuButton=button
        root.addView(button,FrameLayout.LayoutParams(dp(48),dp(40),Gravity.TOP or Gravity.END).apply { topMargin=dp(8); marginEnd=dp(10) })
        immersive()
        // Do not restore arbitrary URLs or page forms from Activity extras or saved state.
        browser.loadUrl(initialUrl)
    }
    private fun dp(size: Int)=(size*resources.displayMetrics.density).toInt()
    @Suppress("SetJavaScriptEnabled")
    private fun newBrowser(): WebView=WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled=true; domStorageEnabled=true
            mediaPlaybackRequiresUserGesture=false
            useWideViewPort=true; loadWithOverviewMode=true
            setSupportZoom(false); setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically=false
            allowFileAccess=false; allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            offscreenPreRaster=false
            userAgentString=OfficialCloudPolicy.mobileUserAgent(userAgentString)
        }
        if(Build.VERSION.SDK_INT>=26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
        webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                val url=request.url.toString()
                if(!request.isForMainFrame) return request.url.scheme!="https"
                if(OfficialCloudPolicy.allowed(url)) return false
                if(request.isForMainFrame && request.hasGesture() && request.url.scheme=="https") external(url)
                return true
            }
            override fun onReceivedError(view: WebView,request: WebResourceRequest,failure: WebResourceError) {
                if(request.isForMainFrame && view===browser && alive && !isFinishing) error("Não foi possível carregar o cliente oficial. Verifique a conexão e o Android System WebView/Chrome.")
            }
            override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                if(view===popup) closePopup() else {
                    (view.parent as? ViewGroup)?.removeView(view); view.destroy()
                    if(alive && !isFinishing) error("O cliente web foi encerrado pelo sistema. Em aparelhos com pouca memória, prefira o motor nativo.")
                }
                return true
            }
        }
        webChromeClient=object: WebChromeClient() {
            override fun onProgressChanged(view: WebView,value: Int) {
                if(view===browser) this@OfficialCloudActivity.progress?.let { bar ->
                    bar.progress=value; bar.visibility=if(value==100) View.GONE else View.VISIBLE
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: Message): Boolean {
                if(!isUserGesture || popup!=null || !OfficialCloudPolicy.allowed(view.url ?: "")) return false
                val child=newBrowser(); popup=child
                root.addView(child,FrameLayout.LayoutParams(-1,-1))
                menuButton?.bringToFront()
                (resultMsg.obj as WebView.WebViewTransport).webView=child
                resultMsg.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView) { if(window===popup) closePopup() }
            override fun onShowCustomView(view: View,callback: CustomViewCallback) {
                if(customView!=null) { callback.onCustomViewHidden(); return }
                customView=view; customCallback=callback; root.addView(view,FrameLayout.LayoutParams(-1,-1)); menuButton?.bringToFront(); immersive()
            }
            override fun onHideCustomView() { hideCustomView() }
        }
    }
    private fun hideCustomView() {
        customView?.let { root.removeView(it) }; customView=null
        val callback=customCallback; customCallback=null; callback?.onCustomViewHidden(); immersive()
    }
    private fun closePopup() {
        popup?.let { root.removeView(it); it.stopLoading(); it.destroy() }; popup=null
        runCatching { CookieManager.getInstance().flush() }
    }
    private fun external(url: String) {
        if(!url.startsWith("https://")) return
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch(_: android.content.ActivityNotFoundException) { error("Nenhum navegador está disponível neste aparelho.") }
    }
    private fun error(message: String) {
        if(!alive || isFinishing || menu?.isShowing==true) return
        menu=ConsoleDialogs.message(this,"Cliente oficial Xbox",message,listOf(
            ConsoleAction("Abrir no navegador") { external(initialUrl) },ConsoleAction("Voltar ao catálogo") { finish() }))
    }
    private fun showMenu() {
        if(menu?.isShowing==true) return
        val actions=mutableListOf(ConsoleAction("Voltar ao jogo") {})
        if(popup!=null) actions+=ConsoleAction("Fechar janela de login") { closePopup() }
        actions+=ConsoleAction("Sobre este modo") {
            menu=ConsoleDialogs.message(this,"Toque oficial Xbox",
                "O cliente web do Xbox cuida do login, fila, vídeo e toque. O login pode ser solicitado separadamente.\n\nOs ajustes do nosso motor nativo, HUDs, coop e filtros de imagem se aplicam ao modo nativo. Este modo usa as opções oferecidas pelo site.\n\nPara encerrar a sessão cloud, use Sair do jogo no menu do próprio Xbox. Fechar esta tela interrompe o cliente local.")
        }
        if(::browser.isInitialized && browser.parent!=null) {
            if(browser.canGoBack()) actions+=ConsoleAction("Página anterior") { browser.goBack() }
            actions+=ConsoleAction("Recarregar página") { browser.reload() }
        }
        actions+=ConsoleAction("Abrir no navegador") { external(initialUrl) }
        actions+=ConsoleAction("Voltar ao catálogo") { finish() }
        menu=ConsoleDialogs.menu(this,"Cliente oficial Xbox",actions)
    }
    private fun immersive() {
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if(hasFocus) immersive() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_MENU) {
            if(event.repeatCount==0) { if(customView!=null) hideCustomView() else showMenu() }
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onResume() { super.onResume(); if(::browser.isInitialized && browser.parent!=null) browser.onResume(); popup?.onResume() }
    override fun onPause() { if(::browser.isInitialized && browser.parent!=null) browser.onPause(); popup?.onPause(); runCatching { CookieManager.getInstance().flush() }; super.onPause() }
    override fun onDestroy() {
        alive=false; menu?.dismiss(); hideCustomView(); closePopup()
        if(::browser.isInitialized && browser.parent!=null) { root.removeView(browser); browser.stopLoading(); browser.loadUrl("about:blank"); browser.destroy() }
        runCatching { CookieManager.getInstance().flush() }
        super.onDestroy()
    }
}
