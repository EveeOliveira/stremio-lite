# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/); versões seguem
[SemVer](https://semver.org/lang/pt-BR/).

## [0.1.0-beta] — 2026-11-17

Primeira versão pública (beta), validada no projetor HY320 (Amlogic T950S, ARMv7, 2 GB, Android 13).

### Adicionado
- Tela inicial com "Continuar assistindo" (barra de progresso) e catálogos de todos os addons.
- Busca em todos os catálogos que suportam `extra: search`.
- Detalhes de filme e série (temporadas, episódios, "Continuar T1 E3").
- Tela de fontes com pré-seleção de 720p, destaque RD+/RD, tamanho e codecs de áudio/vídeo.
- Player Media3 ExoPlayer com áudio sempre decodificado por FFmpeg para PCM estéreo
  (corrige AC3/EAC3 mudo no firmware Amlogic), retomada de posição, troca de áudio/legenda/velocidade
  e legendas dos addons com preferência pt-BR → pt → en.
- Login na conta Stremio por código (`link.stremio.com`) ou e-mail/senha; sincronização de addons,
  biblioteca e progresso (`datastoreGet`/`datastorePut`).
- Modo sem login com Cinemeta + OpenSubtitles v3 e addons adicionados por URL de manifest.
- Scripts de medição (`tools/medir.sh`) e servidor de teste com Range (`tools/rangeserver.py`).

### Limitações conhecidas
- Sem motor de torrent: fontes só-magnet ficam ocultas.
- Addons da conta são só leitura no app.
- "Continuar assistindo" de série não avança sozinho para o próximo episódio.
- O bitfield `watched` por episódio não é atualizado.

[0.1.0-beta]: https://github.com/OWNER/stremio-lite/releases/tag/v0.1.0-beta
