package com.xcloud.tclnative.home

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuild a compiled XML string pool while preserving its indices and all resource/attribute types. */
object BinaryManifestEditor {
    private fun ByteArray.buffer()=ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    fun replace(xml: ByteArray, values: Map<String,String>): ByteArray {
        val src=xml.buffer()
        require(src.getShort(0).toInt()==3 && src.getInt(4)==xml.size)
        val result=ByteArrayOutputStream().apply { write(xml,0,8) }
        var cursor=8
        var replaced=false
        while(cursor<xml.size) {
            val type=src.getShort(cursor).toInt() and 65535
            val size=src.getInt(cursor+4)
            require(size>=8 && cursor+size<=xml.size)
            if(type==1) {
                result.write(pool(xml.copyOfRange(cursor,cursor+size),values))
                replaced=true
            } else result.write(xml,cursor,size)
            cursor+=size
        }
        require(replaced)
        return result.toByteArray().also { it.buffer().putInt(4,it.size) }
    }
    private fun pool(chunk: ByteArray,values: Map<String,String>): ByteArray {
        val b=chunk.buffer()
        val header=b.getShort(2).toInt() and 65535
        val count=b.getInt(8)
        require(header>=28 && b.getInt(12)==0 && count in 1..10000) // Manifest pools contain no styled text.
        val flags=b.getInt(16)
        val utf8=flags and 0x100!=0
        val start=b.getInt(20)
        val data=ByteArrayOutputStream()
        val offsets=IntArray(count)
        repeat(count) { index ->
            val position=start+b.getInt(header+index*4)
            val text=readString(chunk,position,utf8)
            val replacement=values[text] ?: text
            offsets[index]=data.size()
            if(utf8) {
                val encoded=replacement.toByteArray(Charsets.UTF_8)
                length8(data,replacement.length); length8(data,encoded.size); data.write(encoded); data.write(0)
            } else {
                require(replacement.length<32768)
                short(data,replacement.length); data.write(replacement.toByteArray(Charsets.UTF_16LE)); short(data,0)
            }
        }
        while(data.size()%4!=0) data.write(0)
        val stringsStart=header+count*4
        val out=ByteArray(stringsStart+data.size())
        chunk.copyInto(out,0,0,header)
        val target=out.buffer()
        target.putInt(4,out.size); target.putInt(16,flags and 1.inv()); target.putInt(20,stringsStart); target.putInt(24,0)
        offsets.forEachIndexed { i,value -> target.putInt(header+i*4,value) }
        data.toByteArray().copyInto(out,stringsStart)
        return out
    }
    fun strings(xml: ByteArray): List<String> {
        val b=xml.buffer(); var p=8
        while(p<xml.size) {
            if(b.getShort(p).toInt()==1) {
                val header=b.getShort(p+2).toInt() and 65535
                val count=b.getInt(p+8); val start=b.getInt(p+20); val utf8=b.getInt(p+16) and 0x100!=0
                return (0 until count).map { readString(xml,p+start+b.getInt(p+header+it*4),utf8) }
            }
            p+=b.getInt(p+4)
        }
        return emptyList()
    }
    private fun readString(bytes: ByteArray,position: Int,utf8: Boolean): String {
        var p=position
        fun length(): Int {
            val first=bytes[p++].toInt() and 255
            return if(first and 128==0) first else ((first and 127) shl 8) or (bytes[p++].toInt() and 255)
        }
        return if(utf8) {
            length(); val size=length(); String(bytes,p,size,Charsets.UTF_8)
        } else {
            val b=bytes.buffer(); val first=b.getShort(p).toInt() and 65535; p+=2
            val size=if(first and 32768==0) first else { val second=b.getShort(p).toInt() and 65535; p+=2; ((first and 32767) shl 16) or second }
            String(bytes,p,size*2,Charsets.UTF_16LE)
        }
    }
    private fun short(out: ByteArrayOutputStream,n: Int) { out.write(n and 255); out.write((n ushr 8) and 255) }
    private fun length8(out: ByteArrayOutputStream,n: Int) {
        require(n<32768)
        if(n>127) out.write((n ushr 8) or 128)
        out.write(n and 255)
    }
}
