package com.xcloud.tclnative.home

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.util.UUID
import javax.security.auth.x500.X500Principal

/** Invoked only after selecting a favorite. The system installer retains control of installation. */
class GameShortcutInstaller(private val context: Context) {
    fun create(game: GameTitle,cover: Bitmap?): File {
        val folder=File(context.cacheDir,"game-shortcuts").apply { mkdirs() }
        // An installer may still be reading the previous APK, even after another request starts.
        val template=File.createTempFile("template-", ".apk",folder)
        val packageName=ShortcutApkBuilder.packageName(game.titleId)
        val output=File(folder,"$packageName-${UUID.randomUUID().toString().replace("-", "")}.apk")
        var fallback: Bitmap?=null
        fun png(art: Bitmap,width: Int,height: Int): ByteArray {
            val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(image); canvas.drawColor(Color.rgb(18,38,27))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val scale=minOf(width.toFloat()/art.width,height.toFloat()/art.height)
                val w=art.width*scale; val h=art.height*scale
                canvas.drawBitmap(art,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
                return ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
            } finally { image.recycle() }
        }
        return try {
            context.assets.open("game-shortcut/template.apk").use { source -> template.outputStream().use { source.copyTo(it) } }
            val art=cover ?: BitmapFactory.decodeResource(context.resources,R.drawable.brand_logo,
                BitmapFactory.Options().apply { inSampleSize=4; inScaled=false }).also { fallback=it }
            ShortcutApkBuilder.build(template,output,game.titleId,game.name.take(256),png(art,256,256),png(art,320,180),signer())
            @Suppress("DEPRECATION")
            val parsed=context.packageManager.getPackageArchiveInfo(output.path,PackageManager.GET_ACTIVITIES)
            check(parsed?.packageName==packageName && parsed.activities?.any {
                it.name=="com.xcloud.shortcut.ShortcutActivity" && it.exported
            }==true) { UiText.text("O Android deste aparelho não conseguiu ler o pacote do atalho. O atalho não foi enviado ao instalador.") }
            output
        } catch(error: Exception) { output.delete(); throw error }
        finally { template.delete(); fallback?.recycle() }
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
