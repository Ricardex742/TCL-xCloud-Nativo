package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.*
import android.widget.*
import com.xcloud.tclnative.auth.BrowserAuthorization
import com.xcloud.tclnative.i18n.UiText

/** Microsoft-hosted login only; no password fields, JS bridges or intercepted credential requests. */
internal class MicrosoftLoginDialog(activity: Activity,private val authorization: BrowserAuthorization,
                                    private val authorized: (String) -> Unit,private val cancelled: () -> Unit,
                                    private val failed: (String) -> Unit): Dialog(activity) {
    private var finished=false
    private var released=false
    private val browser=WebView(activity)
    private val address=TextView(activity).apply {
        setTextColor(Color.DKGRAY);textSize=13f;gravity=Gravity.CENTER_VERTICAL
        setPadding(12,0,12,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE
    }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setOwnerActivity(activity)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE) }
        val toolbar=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val back=Button(activity).apply { text=UiText.text("Voltar");setOnClickListener { cancel() } }
        toolbar.addView(back,LinearLayout.LayoutParams(-2,-1));toolbar.addView(address,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(toolbar,LinearLayout.LayoutParams(-1,(48*activity.resources.displayMetrics.density).toInt()))
        root.addView(browser,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        browser.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true
            allowFileAccess=false;allowContentAccess=false;setGeolocationEnabled(false)
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
            if(Build.VERSION.SDK_INT>=26) safeBrowsingEnabled=true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false)
        browser.webChromeClient=object: WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        browser.webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean = if(request.isForMainFrame) navigate(request.url.toString()) else false
            @Deprecated("Compatibility callback")
            override fun shouldOverrideUrlLoading(view: WebView,url: String)=navigate(url)
            override fun onPageStarted(view: WebView,url: String,favicon: android.graphics.Bitmap?) {
                if(navigate(url) && !finished) view.stopLoading()
            }
            override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) {
                if(request.isForMainFrame && !finished) finishError(UiText.text("Não foi possível abrir o login da Microsoft. Tente novamente ou use o QR Code."))
            }
        }
        setOnCancelListener { if(!finished) { finished=true;cancelled() } }
        setOnDismissListener { release() }
    }
    private fun navigate(url: String): Boolean {
        if(finished) return true
        return when(val result=authorization.redirect(url,System.currentTimeMillis())) {
            is BrowserAuthorization.Redirect.Authorized -> { finished=true;dismiss();authorized(result.code);true }
            BrowserAuthorization.Redirect.Denied -> { finished=true;dismiss();cancelled();true }
            BrowserAuthorization.Redirect.Invalid -> { finishError(UiText.text("O login expirou ou não pôde ser validado. Tente novamente."));true }
            BrowserAuthorization.Redirect.Continue -> {
                val uri=android.net.Uri.parse(url)
                val allowed=uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null
                if(allowed) address.text=uri.host
                !allowed
            }
        }
    }
    private fun finishError(message: String) { finished=true;dismiss();failed(message) }
    override fun show() {
        super.show();window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        browser.loadUrl(authorization.url)
    }
    fun close() { finished=true;dismiss() }
    private fun release() {
        if(released) return
        released=true
        browser.stopLoading();(browser.parent as? ViewGroup)?.removeView(browser)
        browser.webChromeClient=null;browser.webViewClient=WebViewClient();browser.destroy()
    }
}
