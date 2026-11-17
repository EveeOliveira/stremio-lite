# Stremio Lite

![versão](https://img.shields.io/badge/vers%C3%A3o-0.1.0--beta-orange)
![plataforma](https://img.shields.io/badge/Android%20TV-9%2B-3DDC84)
![abi](https://img.shields.io/badge/ABI-armeabi--v7a-blue)
![licença](https://img.shields.io/badge/licen%C3%A7a-MIT-lightgrey)

> **Versão beta.** Funciona no aparelho para o qual foi feito, mas ainda tem limitações conhecidas
> (veja o fim deste arquivo) e pode mudar bastante. Relate problemas em *Issues*.
>
> Projeto independente e não oficial. **Não é afiliado ao Stremio** nem usa código do stremio-web; apenas
> implementa o protocolo público de addons. Nenhum conteúdo é hospedado ou fornecido por este app.

Cliente leve para Android TV do **protocolo de addons do Stremio**, feito para o projetor HY320
(Amlogic T950S, ARMv7 32 bits, 2 GB de RAM, Android 13, 1280x720).

- APK de **3,6 MB**, só `armeabi-v7a`, minSdk 28, targetSdk 33.
- Views clássicas + RecyclerView (sem Compose e sem a biblioteca Leanback, que pesam no ARMv7), sem animações,
  navegação 100% pelo controle remoto (D-pad).
- Áudio **sempre decodificado por software** (FFmpeg) para PCM: resolve o AC3/EAC3 mudo do firmware, que
  anuncia Dolby mas não tem o decodificador.
- Só streams HTTP (Torrentio + Real-Debrid, etc.). Sem motor de torrent.

Medições e evidências: [docs/validacao.md](docs/validacao.md).

## Funcionalidades

| Tela | O que faz |
|---|---|
| Início | "Continuar assistindo" (com barra de progresso) + catálogos de todos os addons |
| Buscar | Busca em todos os catálogos com `extra: search` (ex.: Cinemeta) |
| Detalhes | Filme: botão Assistir/Continuar. Série: temporadas, episódios, "Continuar T1 E3" |
| Fontes | Fontes de todos os addons de stream. **Pré-seleciona e destaca 720p** (RD+ primeiro). Mostra RD+/RD, tamanho, codec de áudio (AC3/EAC3/DTS/TrueHD/Atmos), codec de vídeo (HEVC/HDR/DV). Torrents sem URL ficam ocultos |
| Player | Media3 ExoPlayer + FFmpeg, retomada de posição, troca de áudio/legenda/velocidade pelo botão de engrenagem e CC, legendas dos addons (OpenSubtitles) com preferência pt-BR → pt → en, ←/→ voltam/avançam 10 s com o controle escondido |
| Conta e addons | Login por **código** (abre `link.stremio.com/XXXX` no celular já logado) ou por e-mail/senha; sair; lista de addons; adicionar/remover addon por URL de manifest |

### Com login × sem login

- **Com login** (API `api.strem.io`): os addons vêm da coleção da conta (`addonCollectionGet`), a biblioteca e o
  "Continuar assistindo" vêm do `datastoreGet` (coleção `libraryItem`), e o progresso é gravado de volta com
  `datastorePut` a cada 30 s, ao sair do player e no fim. Ao passar de 92% o item é marcado como assistido.
- **Sem login**: Cinemeta + OpenSubtitles v3 + os addons que você adicionar por URL. O progresso fica só no aparelho.
- Os addons adicionados por URL valem nos dois modos.

Para usar o Torrentio com Real-Debrid sem login, cole a URL do manifest configurado
(`https://torrentio.strem.fun/<config>/manifest.json`) em "Conta e addons". A tela mostra só o host do addon,
nunca o caminho (que contém a chave do Real-Debrid).

## Como funciona o áudio

`player/SwAudioRenderersFactory.kt`:

1. `FfmpegAudioRenderer` (build pronta do decoder-ffmpeg do Media3 publicada pelo Jellyfin,
   `org.jellyfin.media3:media3-ffmpeg-decoder`) entra **antes** do `MediaCodecAudioRenderer`: AC3, EAC3, DTS,
   TrueHD, AAC, Opus etc. são decodificados no app.
2. O `DefaultAudioSink` é criado **sem Context** e com `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES`: ele não
   consulta as capacidades HDMI/Dolby que o sistema anuncia, então nunca tenta passthrough. Offload fica desligado
   (padrão do Media3) e a saída é PCM 16 bits (sem float).
3. Downmix 5.1/7.1 → estéreo com `ChannelMixingAudioProcessor`. Sem isso, o PCM de 6 canais era roteado pela
   política da Amlogic para uma saída OFFLOAD; em estéreo ele vai sempre para a thread MIXER (alto-falante, HDMI
   e Bluetooth).

Buffer pensado para 2 GB de RAM: 15–50 s, teto de 64 MB, sem back-buffer.

## Build no Windows

Requisitos:

- JDK 17 ou mais novo (testado com JDK 22).
- Android SDK com `platforms;android-35` e `build-tools;35.x` (normalmente em `%LOCALAPPDATA%\Android\Sdk`).
- Nada de NDK: o FFmpeg vem pré-compilado na dependência do Jellyfin.

O Gradle 8.11.1 é baixado sozinho pelo wrapper. O caminho do SDK fica em `local.properties`
(não versionado):

```
sdk.dir=C:/Users/SEU_USUARIO/AppData/Local/Android/Sdk
```

Compilar (PowerShell ou Git Bash, na pasta `stremio-lite`):

```bash
./gradlew assembleRelease
```

Saída: `app/build/outputs/apk/release/app-release.apk` (assinado com a chave de debug local, o suficiente para
instalar via adb). O release usa R8 + shrinkResources.

Versões: AGP 8.7.3, Kotlin 2.0.21, Media3 1.6.1, OkHttp 4.12, Coil 2.7 (imagens), coroutines 1.9.

## Instalar no projetor

Troque `192.168.0.50` pelo IP do seu aparelho (ative a depuração ADB pela rede nas opções de desenvolvedor).

```bash
adb connect 192.168.0.50:5555
```

```bash
adb -s 192.168.0.50:5555 install -r app/build/outputs/apk/release/app-release.apk
```

O app aparece no FLauncher como o banner roxo com um ▶. O Stremio oficial continua instalado e independente.

Se o FLauncher não mostrar o app (aconteceu no HY320: ele registrou o app mas não o pôs em nenhuma categoria),
adicione-o a "TV Applications" pelas configurações do FLauncher, ou via root:

```bash
adb -s 192.168.0.50:5555 shell "am force-stop me.efesser.flauncher; su 0 sqlite3 /data/data/me.efesser.flauncher/app_flutter/db.sqlite \"insert into apps_categories(category_id, app_package_name, \\\"order\\\") values (1, 'app.stremiolite', 10);\""
```

Para depurar:

```bash
adb -s 192.168.0.50:5555 logcat -s StremioLite
```

Também dá para abrir qualquer vídeo HTTP direto no player (ele aceita `ACTION_VIEW` de `video/*`):

```bash
adb -s 192.168.0.50:5555 shell am start -n app.stremiolite/.player.PlayerActivity -e url http://servidor/arquivo.mkv
```

## Medir CPU e RAM

```bash
adb -s 192.168.0.50:5555 push tools/medir.sh /data/local/tmp/
```

```bash
adb -s 192.168.0.50:5555 shell sh /data/local/tmp/medir.sh app.stremiolite 60
```

No Git Bash, rode `export MSYS_NO_PATHCONV=1` antes, senão o caminho `/data/local/tmp` é convertido para um
caminho do Windows.

Mostra a CPU do app e dos serviços de mídia (% de um núcleo), MemAvailable, e RSS/PSS por processo.
`tools/rangeserver.py <pasta> 8765` serve arquivos de teste na rede local com suporte a Range.

## Estrutura

```
app/src/main/java/app/stremiolite/
  App.kt                     Application, ImageLoader (RGB565, sem hardware bitmaps: o gralloc do T950S falha)
  net/Http.kt                OkHttp + JSON (org.json do Android, sem bibliotecas de serialização)
  addon/Models.kt            Manifest, catálogos, meta, vídeos, streams, legendas
  addon/AddonClient.kt       GET {base}/{catalog|meta|stream|subtitles}/{tipo}/{id}[/{extras}].json
  addon/StreamInfo.kt        Extrai qualidade, RD+, tamanho, codecs do texto da fonte
  account/StremioApi.kt      login, código de link, addonCollectionGet, datastoreGet/Put
  data/Repo.kt               Conta, cache de addons em disco, biblioteca/progresso (formato libraryItem)
  ui/                        Início, Busca, Detalhes, Fontes, Conta e addons
  player/                    PlayerActivity + SwAudioRenderersFactory
```

O protocolo foi implementado a partir da documentação pública do `stremio-addon-sdk` (`docs/api/`).
Nenhum código do stremio-web foi usado.

## Limitações conhecidas do MVP

- Sem motor de torrent (fontes só-magnet ficam ocultas e contadas na tela de fontes).
- Addons da conta são só leitura aqui: instalar/remover addons da conta é pelo site/app do Stremio.
- "Continuar assistindo" de série mostra o último episódio parado; não avança sozinho para o próximo episódio.
- O progresso conta a partir de 92% como assistido; o campo `watched` (bitfield por episódio) não é atualizado.

Histórico de versões: [CHANGELOG.md](CHANGELOG.md).

## Licença

[MIT](LICENSE). Stremio é marca de seus respectivos donos; este projeto não tem relação com eles.
