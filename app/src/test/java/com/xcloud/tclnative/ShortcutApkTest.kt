package com.xcloud.tclnative

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.xcloud.tclnative.home.BinaryManifestEditor
import com.xcloud.tclnative.home.ShortcutApkBuilder
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class ShortcutApkTest {
    companion object {
        private lateinit var folder: File
        private lateinit var signer: ApkSigner.SignerConfig
        @JvmStatic @BeforeClass fun signingIdentity() {
            folder=Files.createTempDirectory("shortcut-apk-test-").toFile()
            val file=File(folder,"throwaway.p12")
            val keytool=File(System.getProperty("java.home"),"bin/keytool")
            val process=ProcessBuilder(keytool.path,"-genkeypair","-alias","shortcut","-keyalg","RSA","-keysize","2048",
                "-dname","CN=Shortcut Test","-validity","1","-keystore",file.path,"-storetype","PKCS12","-storepass","test-only").redirectErrorStream(true).start()
            val output=process.inputStream.bufferedReader().readText()
            assertEquals(output,0,process.waitFor())
            val keys=KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it,"test-only".toCharArray()) } }
            signer=ApkSigner.SignerConfig.Builder("shortcut",keys.getKey("shortcut","test-only".toCharArray()) as PrivateKey,
                listOf(keys.getCertificate("shortcut") as X509Certificate)).build()
        }
        @JvmStatic @AfterClass fun cleanup() { if(::folder.isInitialized) folder.deleteRecursively() }
    }
    private val template=File("build/generated/shortcutAssets/game-shortcut/template.apk")
    private fun build(id: String,name: String): File = ZipFile(template).use { apk ->
        val images=apk.entries().asSequence().filter { it.name.endsWith(".png") }.map {
            apk.getInputStream(it).use { stream -> stream.readBytes() }
        }.toList()
        fun square(bytes: ByteArray): Boolean = java.nio.ByteBuffer.wrap(bytes).let { it.getInt(16)==it.getInt(20) }
        val icon=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jcp8AAAAASUVORK5CYII=")
        val banner=images.single { !square(it) }
        ShortcutApkBuilder.build(template,File(folder,"$id.apk"),id,name,icon,banner,signer)
    }
    private fun tool(name: String,vararg args: String): String {
        val sdk=System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val process=ProcessBuilder(listOf(File(sdk,"build-tools/34.0.0/$name").path)+args).redirectErrorStream(true).start()
        val result=process.inputStream.bufferedReader().readText()
        assertEquals(result,0,process.waitFor())
        return result
    }
    @Test fun generatedGameAppHasAValidAndroidSignatureLauncherEntryAndAlignedResources() {
        val apk=build("FORZA5","Forza Horizon 5")
        val result=ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify()
        assertTrue(result.errors.toString(),result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertArrayEquals(signer.certificates.single().encoded,result.signerCertificates.single().encoded)
        val manifest=tool("aapt","dump","badging",apk.path)
        assertTrue(manifest.contains("package: name='${ShortcutApkBuilder.packageName("FORZA5")}'"))
        assertTrue(manifest.contains("application-label:'Forza Horizon 5'"))
        assertTrue(manifest.contains("leanback-launchable-activity:"))
        assertFalse(manifest.contains("uses-permission:"))
        tool("zipalign","-c","4",apk.path)
        ZipFile(apk).use { zip ->
            val strings=BinaryManifestEditor.strings(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() })
            assertTrue("FORZA5" in strings); assertFalse("__XCLOUD_GAME_ID__" in strings)
            val icons=zip.entries().asSequence().filter { it.name.endsWith(".png") }.map {
                zip.getInputStream(it).use { stream -> stream.readBytes() }
            }.toList()
            assertTrue(icons.any { java.nio.ByteBuffer.wrap(it).getInt(16)==1 })
        }
    }
    @Test fun updatingANameKeepsThePackageAndSignerAndSupportsLongUnicodeLabels() {
        val first=build("HADES2","Hades II")
        val original=ApkVerifier.Builder(first).build().verify().signerCertificates.single()
        val name="Hades II — Edição de demonstração 💚 " + "Á".repeat(90)
        val updated=build("HADES2",name)
        val result=ApkVerifier.Builder(updated).build().verify()
        assertTrue(result.errors.toString(),result.isVerified)
        assertArrayEquals(original.encoded,result.signerCertificates.single().encoded)
        ZipFile(updated).use { zip ->
            assertTrue(name in BinaryManifestEditor.strings(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }))
        }
        assertNotEquals(ShortcutApkBuilder.packageName("HADES2"),ShortcutApkBuilder.packageName("FORZA5"))
        assertEquals(ShortcutApkBuilder.packageName("hades2"),ShortcutApkBuilder.packageName("HADES2"))
    }
    @Test fun callOfDutyShortcutLabelsAreAcceptedByTheAndroidResourceParser() {
        for((id,name) in listOf("CODBO6" to "Call of Duty®: Black Ops 6", "CODMW3" to "Call of Duty®: Modern Warfare® III")) {
            val apk=build(id,name)
            val manifest=tool("aapt","dump","badging",apk.path)
            assertTrue(manifest,manifest.contains("application-label:'$name'"))
            tool("aapt","dump","xmltree",apk.path,"AndroidManifest.xml")
            tool("zipalign","-c","4",apk.path)
            val result=ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify()
            assertTrue(result.errors.toString(),result.isVerified)
        }
    }
    @Test fun resourceAlignmentExtraFieldDeclaresFourByteAlignmentForEveryPackageLayout() {
        var fields=0
        repeat(12) { index ->
            val apk=build("COD-MWIII-$index","Call of Duty®: Modern Warfare® III")
            tool("zipalign","-c","4",apk.path)
            ZipFile(apk).use { zip ->
                val extra=zip.getEntry("resources.arsc").extra ?: byteArrayOf()
                val b=java.nio.ByteBuffer.wrap(extra).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while(b.remaining()>=4) {
                    val type=b.short.toInt() and 65535; val length=b.short.toInt() and 65535
                    assertTrue(length<=b.remaining())
                    if(type==0xd935) {
                        fields++
                        assertTrue("APK $index: malformed Android alignment field of $length bytes",length>=2)
                        assertEquals("APK $index: invalid declared Android alignment",4,b.getShort(b.position()).toInt() and 65535)
                    }
                    b.position(b.position()+length)
                }
            }
        }
        assertTrue(fields>0)
    }
    @Test fun generatedPackagesStayAlignedAcrossDeviceTimeZones() {
        val original=java.util.TimeZone.getDefault()
        try {
            for(zone in listOf("GMT-12:00","Pacific/Kiritimati","America/Sao_Paulo")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                val apk=build("COD-TIMEZONE","Call of Duty®: Modern Warfare® III")
                tool("zipalign","-c","4",apk.path)
                val manifest=tool("aapt","dump","badging",apk.path)
                assertTrue(manifest.contains("leanback-launchable-activity:"))
                assertTrue(ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).build().verify().isVerified)
            }
        } finally { java.util.TimeZone.setDefault(original) }
    }
    @Test(expected=IllegalArgumentException::class) fun invalidLaunchIdsNeverGenerateAnInstallableApp() {
        build("../invalid","Invalid")
    }
}
