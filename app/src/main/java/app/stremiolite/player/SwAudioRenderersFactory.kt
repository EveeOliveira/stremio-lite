package app.stremiolite.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * O firmware do HY320 anuncia suporte Dolby mas nao tem o decodificador: AC3/EAC3 em
 * passthrough/offload sai mudo. Aqui todo audio e decodificado por software (FFmpeg) para PCM
 * 16 bits e o AudioSink so conhece PCM (sem passthrough, sem offload, sem float).
 */
@OptIn(UnstableApi::class)
class SwAudioRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF) // o FFmpeg e adicionado manualmente abaixo
    }

    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        // Builder sem Context: assim o sink NAO consulta as capacidades HDMI/Dolby do sistema
        // e fica preso em DEFAULT_AUDIO_CAPABILITIES (somente PCM).
        DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            // PCM 16 bits com 6 canais e roteado pela politica da Amlogic para uma saida OFFLOAD/direta;
            // estereo vai sempre para a thread MIXER (alto-falante, HDMI e Bluetooth).
            .setAudioProcessors(arrayOf(stereoDownmix()))
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()

    /** Downmix para estereo (canais na ordem Android/FFmpeg: FL FR FC LFE BL BR [SL SR]). */
    private fun stereoDownmix() = ChannelMixingAudioProcessor().apply {
        val f = 0.55f; val c = 0.40f; val s = 0.35f; val lfe = 0.10f
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
        // linhas = canal de entrada, colunas = (L, R)
        putChannelMixingMatrix(ChannelMixingMatrix(6, 2, floatArrayOf(
            f, 0f,      // FL
            0f, f,      // FR
            c, c,       // FC
            lfe, lfe,   // LFE
            s, 0f,      // BL/SL
            0f, s,      // BR/SR
        )))
        putChannelMixingMatrix(ChannelMixingMatrix(8, 2, floatArrayOf(
            f, 0f, 0f, f, c, c, lfe, lfe,
            s * 0.8f, 0f, 0f, s * 0.8f,  // BL, BR
            s * 0.8f, 0f, 0f, s * 0.8f,  // SL, SR
        )))
        for (n in intArrayOf(3, 4, 5, 7)) putChannelMixingMatrix(ChannelMixingMatrix.create(n, n))
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // FFmpeg primeiro: em empate de suporte, o seletor escolhe o primeiro renderer.
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out)
    }
}
