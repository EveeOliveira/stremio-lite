# Validação no projetor HY320 — 17/11/2026

Aparelho: Amlogic T950S, ARMv7 32 bits, 4 núcleos a 1,46 GHz, 2 GB de RAM, Android 13, 1280x720 (213 dpi).
APK: `app-release.apk` 0.1.0, 3,6 MB, só `armeabi-v7a`.

## Arquivos de teste

Gerados no PC com o FFmpeg e servidos na rede local por `tools/rangeserver.py` (servidor HTTP com Range,
como o Real-Debrid), via um addon estático de teste (`manifest.json` + `stream/movie/tt0111161.json` +
`subtitles/movie/tt0111161.json`):

| Arquivo | Vídeo | Áudio |
|---|---|---|
| `teste_ac3_51_longo.mkv` | H.264 1280x720 2,5 Mbps | **AC3 5.1** 448 kbps, 20 min |
| `teste_1080p_eac3_51.mkv` | H.264 1920x1080 8 Mbps | **EAC3 5.1** 640 kbps, 10 min |

## Áudio: FFmpeg → PCM → thread MIXER

Log do app (`adb logcat -s StremioLite`):

```
ffmpeg disponivel=true versao=Lavc60.3.100 ac3=true eac3=true dts=true truehd=true
decodificador de video: c2.amlogic.avc.decoder
decodificador de audio: ffmpegLavc60.3.100-ac3
audio entrada: audio/ac3 6ch 48000Hz
faixa selecionada: tipo=3 application/x-media3-cues lang=pt-br label=pt-BR · OpenSubtitles v3 1
legenda exibida: Acho-o particularmente frio / e desprovido de remorsos.
```

`adb shell dumpsys media.audio_flinger` tocando o AC3 5.1 (trilha 65 = app, uid 13323):

```
Output thread 0xb1f32340, name AudioOut_D, tid 579, type 0 (MIXER):
  Standby: no
  HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)
  Channel count: 2
  Channel mask: 0x00000003 (front-left, front-right)
  5 Tracks of which 1 are active
    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ... FrmRdy F Underruns
             65    yes  13323     337      45 A  0x000 00000001 00000003  48000 ... 16400 A         0
```

Formato `00000001` = PCM 16 bits, máscara `0x3` = estéreo, 0 underruns. Com o EAC3 5.1 em 1080p o resultado foi
igual (trilha 67, mesma thread MIXER).

### Por que o downmix para estéreo

Na primeira versão o FFmpeg entregava PCM 16 bits com **6 canais**, e a política de áudio da Amlogic roteava essa
trilha para uma saída do tipo **OFFLOAD** (`AudioOut_8D`, type 4, PCM 6ch), e não para o MIXER. Para garantir o
MIXER (e o Bluetooth, que é estéreo), o app faz o downmix 5.1/7.1 → estéreo com o `ChannelMixingAudioProcessor`
do Media3, antes do `AudioTrack`. Com isso a trilha cai sempre em `AudioOut_D (MIXER)`.

## Teste com a conta real (login por código) e stream do Torrentio RD

O login pelo código de `link.stremio.com` trouxe da conta 18 addons (incluindo o Torrentio RD), 231 itens
na biblioteca e 116 no "Continuar assistindo", com os pôsteres e as barras de progresso.

Episódio do "Continuar assistindo" (série, T1 E4): 13 fontes do Torrentio RD, e o app pré-selecionou
`[RD+] Torrentio 720p` (WEB H.264, 1,27 GB). Log:

```
iniciando em 2082893ms com 4 legenda(s) de addon        <- retomou na posição gravada na conta (34:42)
faixa selecionada: tipo=1 audio/eac3 lang=en
faixa selecionada: tipo=3 application/x-media3-cues lang=pt-br label=pt-BR · OpenSubtitles v3 1
decodificador de video: c2.amlogic.avc.decoder
decodificador de audio: ffmpegLavc60.3.100-eac3
audio entrada: audio/eac3 6ch 48000Hz
```

Primeiro quadro cerca de 4 s depois de escolher a fonte. No `audio_flinger`, a trilha do app (formato
`00000001`, máscara `00000003`) ficou ativa na thread `AudioOut_D (MIXER)`. Medição em regime (60 s):
**CPU do app 46%** de um núcleo, serviços de mídia 18%, **RSS 162 MB, PSS 72 MB**, MemAvailable 1.082 MB.
Ao sair do player o progresso foi gravado na conta (`datastorePut` sem erro).

## CPU por thread (720p AC3, janela de 20 s)

```
17.6% ExoPlayer:Playback   (loop de render + downmix + escrita no AudioTrack)
 9.1% MediaCodec_loop      (vídeo por hardware, c2.amlogic.avc.decoder)
 6.7% HwBinder             (vídeo por hardware)
 5.8% ExoPlayer:SimpleDecoder  (decodificação AC3 no FFmpeg)
 1.4% ExoPlayer:Loader     (download HTTP + extrator MKV)
```

## Medições (script `tools/medir.sh`, CPU em % de UM núcleo)

| Cenário | Stremio oficial (1.12.1) | Stremio Lite |
|---|---|---|
| Tamanho instalado | APK 80 MB (22 MB + split ARMv7 58 MB), libs 126 MB | **APK 3,6 MB** (3,5 MB instalado) |
| Parado na tela inicial: RSS | 228 MB + 200 MB (`stremio_server_process`) = **428 MB** | **115 MB** |
| Parado: PSS | 116 + 128 = **244 MB** | **33 MB** |
| Parado: CPU | 0% | 0% |
| Parado: MemAvailable do sistema | 1.150 MB | 1.267 MB (**+117 MB**) |
| Tocando: RSS | ~300 MB + ~220 MB (referência da sessão anterior, player MPV) | **147 MB** (720p AC3) / **184 MB** (1080p EAC3) |
| Tocando: PSS | — | 58 MB / 94 MB |
| Tocando: CPU do app | **~68%** (MPV, referência da sessão anterior) | **40%** (720p AC3) / **43%** (1080p EAC3) / **46%** (stream real RD 720p EAC3) |
| Tocando stream real RD: RSS / PSS | — | 162 MB / 72 MB |
| Tocando: CPU dos serviços de mídia do sistema | — | 20% (codec c2, audioserver, extractor) |
| Tocando: MemAvailable | — | 1.199 MB / 1.152 MB |
| Áudio AC3 5.1 | ExoPlayer padrão: OFFLOAD AC3 (mudo); MPV: PCM float 6ch no MIXER | **PCM 16 bits estéreo no MIXER** |

Observações honestas:

- Os números do Stremio oficial **tocando** são os da sessão anterior (outro arquivo, stream real do Real-Debrid
  pelo MPV). Não toquei nada no Stremio oficial para não mexer na sua conta (biblioteca e "Continuar assistindo").
  Os números **parado** foram medidos agora, do mesmo jeito para os dois apps.
- O Lite foi medido com os arquivos de teste da rede local e também com um stream real do Real-Debrid
  (720p WEB, EAC3 5.1). Um 1080p remux de 20–40 Mbps deve subir a RAM até o teto do buffer (64 MB), mas não a
  CPU de áudio.
- Pelo `screencap` não dá para ver o vídeo (ele fica na camada de overlay de hardware da Amlogic), por isso a
  verificação de áudio e legenda foi feita por log e pelo `audio_flinger`.
