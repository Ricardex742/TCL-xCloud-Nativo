package com.xcloud.tclnative.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.xcloud.tclnative.R
import com.xcloud.tclnative.signaling.GameTitle
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Invoked only after selecting a favorite. The system installer retains control of installation. */
class GameShortcutInstaller(private val context: Context) {
    fun create(game: GameTitle,cover: Bitmap?): File {
        val folder=File(context.cacheDir,"game-shortcuts").apply { mkdirs() }
        val template=File(folder,"template.apk")
        context.assets.open("game-shortcut/template.apk").use { source -> template.outputStream().use { source.copyTo(it) } }
        val fallback=android.graphics.BitmapFactory.decodeResource(context.resources,R.drawable.brand_logo)
        val art=cover ?: fallback
        fun png(width: Int,height: Int): ByteArray {
            val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image); canvas.drawColor(Color.rgb(18,38,27))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val scale=minOf(width.toFloat()/art.width,height.toFloat()/art.height)
            val w=art.width*scale; val h=art.height*scale
            canvas.drawBitmap(art,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
            return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it); image.recycle() }.toByteArray()
        }
        return try {
            ShortcutApkBuilder.build(template,File(folder,"${ShortcutApkBuilder.packageName(game.titleId)}.apk"),
                game.titleId,game.name.take(256),png(256,256),png(320,180),signer())
        } finally { fallback.recycle() }
    }
    private fun signer(): ApkSigner.SignerConfig {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=xCLOUD Game Shortcut"))
                    .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4102444800000L)).build())
                generateKeyPair()
            }
        }
        return ApkSigner.SignerConfig.Builder("xcloud-shortcut",store.getKey(KEY_ALIAS,null) as PrivateKey,
            listOf(store.getCertificate(KEY_ALIAS) as X509Certificate)).build()
    }
    companion object { private const val KEY_ALIAS="xcloud-game-shortcuts-v1" }
}
