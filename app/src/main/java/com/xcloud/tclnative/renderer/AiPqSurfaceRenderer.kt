package com.xcloud.tclnative.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer

class AiPqSurfaceRenderer(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "AiPqSurfaceRenderer"
        private const val STREAM_WIDTH = 1920
        private const val STREAM_HEIGHT = 1080
    }

    private var mediaCodec: MediaCodec? = null
    private var isDecoderReady = false

    init {
        holder.addCallback(this)
        // Fixa o plano lógico em 1080p para obrigar o Hardware Scaler AiPQ da TCL P755
        // a escalonar o plano de vídeo (Hardware Overlay) diretamente para 3840x2160 (4K)
        holder.setFixedSize(STREAM_WIDTH, STREAM_HEIGHT)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface de Hardware criada. Inicializando MediaCodec Low-Latency...")
        initLowLatencyDecoder(holder)
    }

    private fun initLowLatencyDecoder(holder: SurfaceHolder) {
        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, STREAM_WIDTH, STREAM_HEIGHT)

            // 1. Ativa decodificação sem fila de espera (1-in, 1-out) nativa do Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            // 2. Prioridade máxima de tempo real (0) e clock máximo na VPU
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())

            // 3. Flags específicas para os chips da TCL P755 (Realtek RTD2880 / Amlogic T963)
            format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.mtk-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.amlogic.avc.low_latency", 1)

            // 4. Espaço de cor BT.709 para alimentar o mapeamento de cor do AiPQ Engine
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

            // Conecta o decodificador direto na Surface física da TV (Zero-Copy)
            codec.configure(format, holder.surface, null, 0)
            codec.start()

            this.mediaCodec = codec
            this.isDecoderReady = true
            Log.i(TAG, "Decodificador H.264 Low-Latency + AiPQ Overlay ativo com sucesso!")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar decodificador de hardware: ${e.message}", e)
        }
    }

    /**
     * Recebe um pacote H.264 (NAL Unit) diretamente do WebRTC do xCloud e entrega à TV sem delay
     */
    fun decodeFrame(h264Data: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: return
        if (!isDecoderReady) return

        try {
            // Timeout zero ou mínimo (2ms) para nunca travar a thread de rede
            val inputBufferIndex = codec.dequeueInputBuffer(2000)
            if (inputBufferIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(h264Data)
                codec.queueInputBuffer(inputBufferIndex, 0, h264Data.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                // true = renderiza imediatamente no Hardware Overlay da TCL P755
                codec.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no ciclo de decodificação: ${e.message}")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isDecoderReady = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch ( ignored: Exception) {}
        mediaCodec = null
    }
}
