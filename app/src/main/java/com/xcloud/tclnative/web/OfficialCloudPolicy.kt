package com.xcloud.tclnative.web

import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** The page owns its Microsoft login; no native bearer token, JS bridge or session injection. */
object OfficialCloudPolicy {
    private val accounts=setOf("login.live.com","account.live.com","signup.live.com","consent.live.com",
        "login.microsoftonline.com","login.microsoft.com","account.microsoft.com","login.windows.net",
        "sisu.xboxlive.com","sisu.xbox.com","account.xbox.com")
    fun launchUrl(name: String,productId: String?): String? {
        if(productId==null || !productId.matches(Regex("[A-Za-z0-9]{12}"))) return null
        val slug=Normalizer.normalize(name,Normalizer.Form.NFD).replace(Regex("\\p{M}"),"")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"-").trim('-').take(100).ifBlank { "game" }
        return "https://www.xbox.com/pt-BR/play/launch/$slug/${productId.uppercase(Locale.ROOT)}"
    }
    fun searchUrl(name: String)="https://www.xbox.com/pt-BR/play/search?q="+URLEncoder.encode(name,"UTF-8")
    fun allowed(url: String): Boolean = runCatching {
        val uri=URI(url)
        if(uri.scheme!="https" || uri.rawUserInfo!=null || uri.port !in setOf(-1,443)) return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        host in accounts || host=="www.xbox.com" || host=="xbox.com"
    }.getOrDefault(false)
    fun xboxOrigin(url: String?): Boolean = url!=null && runCatching {
        val uri=URI(url)
        uri.scheme=="https" && uri.rawUserInfo==null && uri.port in setOf(-1,443) && uri.host in setOf("www.xbox.com","xbox.com")
    }.getOrDefault(false)
    /** Keep the real installed Chromium version and Android/mobile identity. */
    fun mobileUserAgent(original: String)=original.replace("; wv","").replace("Version/4.0 ","")
}
