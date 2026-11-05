package com.xcloud.tclnative.home

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Builds a tiny, independently signed launcher app; the xCLOUD update key is never embedded or used. */
object ShortcutApkBuilder {
    fun packageName(id: String)="com.xcloud.game.g"+MessageDigest.getInstance("SHA-256")
        .digest(id.uppercase(java.util.Locale.ROOT).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun build(template: File, output: File, id: String, name: String, icon: ByteArray, banner: ByteArray,
              signer: ApkSigner.SignerConfig): File {
        require(GameLaunchLink.valid(id) && name.isNotBlank() && name.length<=256)
        output.parentFile!!.mkdirs()
        val unsigned=File.createTempFile("shortcut-", ".unsigned",output.parentFile)
        try {
            var icons=0; var banners=0
            val counted=Counter(unsigned.outputStream())
            ZipOutputStream(counted).use { target -> ZipFile(template).use { source ->
                source.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }.forEach { old ->
                    val original=source.getInputStream(old).use { it.readBytes() }
                    val bytes=when {
                        old.name=="AndroidManifest.xml" -> BinaryManifestEditor.replace(original,mapOf(
                            "com.xcloud.shortcut.template" to packageName(id),"__XCLOUD_GAME_NAME__" to name,"__XCLOUD_GAME_ID__" to id))
                        old.name=="resources.arsc" -> resourcePackage(original,packageName(id))
                        // aapt2 may shorten resource paths. The template has exactly one square icon and one TV banner.
                        old.name.startsWith("res/") && old.name.endsWith(".png") -> {
                            require(original.size>=24 && original[1]=='P'.code.toByte())
                            val png=ByteBuffer.wrap(original).order(ByteOrder.BIG_ENDIAN)
                            if(png.getInt(16)==png.getInt(20)) { icons++; icon } else { banners++; banner }
                        }
                        else -> original
                    }
                    val entry=ZipEntry(old.name).apply {
                        // Stay away from the DOS epoch boundary in every device time zone.
                        time=946684800000L
                        if(old.name=="resources.arsc") {
                            method=ZipEntry.STORED; size=bytes.size.toLong(); compressedSize=size
                            crc=CRC32().apply { update(bytes) }.value
                            // Android's 0xd935 extra field contains a uint16 alignment, then padding.
                            // Counting just a four-byte header produced malformed fields for some titles.
                            val base=counted.size+30+old.name.toByteArray(Charsets.UTF_8).size
                            val padding=((4-((base+6)%4))%4).toInt()
                            extra=ByteBuffer.allocate(6+padding).order(ByteOrder.LITTLE_ENDIAN)
                                .putShort(0xd935.toShort()).putShort((2+padding).toShort()).putShort(4).array()
                        }
                    }
                    target.putNextEntry(entry); target.write(bytes); target.closeEntry()
                }
            } }
            check(icons==1 && banners==1) { "O modelo do atalho não contém ícone e banner válidos." }
            ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(output).setMinSdkVersion(24)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false)
                .build().sign()
            val verified=ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
            check(verified.isVerified && verified.isVerifiedUsingV2Scheme &&
                verified.signerCertificates.singleOrNull()?.encoded?.contentEquals(signer.certificates.first().encoded)==true) {
                "Não foi possível validar a assinatura do atalho. Tente criá-lo novamente."
            }
            return output
        } catch(error: Exception) { output.delete(); throw error }
        finally { unsigned.delete() }
    }
    private fun resourcePackage(bytes: ByteArray,name: String): ByteArray {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset=b.getShort(2).toInt() and 65535
        while(offset<bytes.size) {
            val size=b.getInt(offset+4)
            require(size>=8 && offset+size<=bytes.size)
            if(b.getShort(offset).toInt() and 65535==0x200) {
                require(name.length<128)
                bytes.fill(0,offset+12,offset+268)
                name.toByteArray(Charsets.UTF_16LE).copyInto(bytes,offset+12)
            }
            offset+=size
        }
        return bytes
    }
    private class Counter(out: OutputStream): FilterOutputStream(out) {
        var size=0L
        override fun write(value: Int) { out.write(value); size++ }
        override fun write(bytes: ByteArray,offset: Int,length: Int) { out.write(bytes,offset,length); size+=length }
    }
}
