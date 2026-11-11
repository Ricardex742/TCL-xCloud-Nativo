package com.xcloud.tclnative.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Offline UI resources. It never changes the system locale or the Xbox session language. */
object UiText {
    private data class LanguageResources(val language: AppLanguage,val resources: Resources?,val packageName: String)
    @Volatile private var state=LanguageResources(AppLanguage.PORTUGUESE,null,"")
    val language get()=state.language
    val locale get()=language.locale
    private val ids=ConcurrentHashMap<String,Int>()
    private val templates=ConcurrentHashMap<String,String>()
    private val staticMessages=ConcurrentHashMap<String,Message>()
    private val placeholder=Regex("\\{([0-9]+)\\}")
    private class Identity(val value: CharSequence) {
        override fun hashCode()=System.identityHashCode(value)
        override fun equals(other: Any?)=other is Identity && value===other.value
    }
    private val emitted=object: LinkedHashMap<Identity,Message>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Identity,Message>?)=size>2048
    }
    internal interface Argument { fun render(): String }
    private class Literal(val value: String): Argument { override fun render()=value }
    internal class Message(val source: String,val args: List<Argument>,val uppercase: Boolean=false): Argument {
        @Volatile private var cached: Pair<AppLanguage,String>?=null
        override fun render(): String {
            val snapshot=state
            if(args.isEmpty()) cached?.takeIf { it.first==snapshot.language }?.let { return it.second }
            val translated=template(source,snapshot)
            val result=placeholder.replace(translated) { match -> args.getOrNull(match.groupValues[1].toInt())?.render() ?: match.value }
            val rendered=if(uppercase) result.uppercase(snapshot.language.locale) else result
            return String(rendered.toCharArray()).also { if(args.isEmpty()) cached=snapshot.language to it }
        }
    }
    fun install(context: Context) { apply(context,AppLanguageStore(context).read()) }
    fun apply(context: Context,selected: AppLanguage) {
        val application=context.applicationContext
        val config=Configuration(context.resources.configuration).apply { setLocales(LocaleList(selected.locale)) }
        state=LanguageResources(selected,application.createConfigurationContext(config).resources,application.packageName)
    }
    internal fun resourceName(source: String)="ui_"+MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun template(source: String,snapshot: LanguageResources): String=templates.getOrPut(snapshot.language.tag+'\u0000'+source) {
        if(snapshot.language==AppLanguage.PORTUGUESE || snapshot.resources==null) source else {
            val id=ids.getOrPut(source) { snapshot.resources.getIdentifier(resourceName(source),"string",snapshot.packageName) }
            if(id==0) source else snapshot.resources.getString(id)
        }
    }
    private fun remember(message: Message): String {
        // A distinct String identity prevents titles, account names and typed text from being mistaken for UI labels.
        val value=message.render()
        synchronized(emitted) { emitted[Identity(value)]=message }
        return value
    }
    internal fun message(value: CharSequence?): Message?=if(value==null) null else synchronized(emitted) { emitted[Identity(value)] }
    fun nested(value: Any?): Any=message(value as? CharSequence) ?: Literal(value?.toString() ?: "null")
    fun text(source: String,vararg values: Any?): String {
        val message=if(values.isEmpty()) staticMessages.getOrPut(source) { Message(source,emptyList()) }
            else Message(source,values.map { it as? Argument ?: Literal(it?.toString() ?: "null") })
        return remember(message)
    }
    fun join(vararg values: Any?): String = remember(Message(values.indices.joinToString("") { "{$it}" },
        values.map { nested(it) as Argument }))
    fun uppercase(value: String): String {
        val original=message(value) ?: return value.uppercase(locale)
        return remember(Message(original.source,original.args,true))
    }
    internal fun render(message: Message)=remember(message)
    fun refresh(root: View) {
        if(root is LocalizedTextView) root.refreshLanguage()
        if(root is TextView) message(root.hint)?.let { root.hint=render(it) }
        message(root.contentDescription)?.let { root.contentDescription=render(it) }
        if(root is LocaleAwareView) root.refreshLanguage()
        root.invalidate()
        if(root is ViewGroup) repeat(root.childCount) { refresh(root.getChildAt(it)) }
    }
    internal fun retainSpans(previous: CharSequence,translated: String): CharSequence {
        if(previous !is Spanned) return translated
        return SpannableString(translated).apply {
            previous.getSpans(0,previous.length,ForegroundColorSpan::class.java).forEach { span ->
                val start=previous.getSpanStart(span)
                val end=previous.getSpanEnd(span)
                val translatedStart=if(end==previous.length && start==previous.indexOf('\n')+1 && start>0)
                    (translated.indexOf('\n')+1).coerceAtLeast(0) else start
                val translatedEnd=if(end==previous.length) length else end.coerceAtMost(length)
                if(translatedStart<translatedEnd) setSpan(ForegroundColorSpan(span.foregroundColor),translatedStart,translatedEnd,previous.getSpanFlags(span))
            }
        }
    }
}

interface LocaleAwareView { fun refreshLanguage() }

/** Keeps the message that produced a label; changing languages never translates arbitrary user text. */
open class LocalizedTextView(context: Context): TextView(context) {
    private var translation: UiText.Message?=null
    private var refreshing=false
    override fun setText(value: CharSequence?,type: BufferType?) {
        if(!refreshing) translation=UiText.message(value) ?: translation?.takeIf { value?.toString()==text?.toString() }
        super.setText(value,type)
    }
    fun refreshLanguage() {
        val message=translation ?: return
        refreshing=true
        try { text=UiText.retainSpans(text,UiText.render(message)) } finally { refreshing=false }
    }
}
