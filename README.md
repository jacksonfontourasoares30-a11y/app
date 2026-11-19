# ProCam — câmera profissional para Android

App de câmera que já abre no modo profissional, com predefinições prontas
e controles manuais que se ajustam ao que cada celular suporta.

## O que tem (versão 0.1)
- Predefinições: Auto, Noite, Retrato, Esporte, Paisagem e Manual (seus ajustes)
- ISO e obturador manuais (com os valores do automático aparecendo ao vivo)
- Foco manual ou toque na tela para focar
- Balanço de branco (Sol, Nublado, Sombra, Lâmpada, Fluorescente)
- Compensação de exposição (EV)
- Histograma ao vivo e grade dos terços
- Câmera traseira/frontal
- Mostra no topo o nível da câmera do aparelho (LEGACY / LIMITED / FULL / LEVEL_3)
- Abre sempre com a última configuração usada
- Fotos salvas em `Pictures/ProCam`

## Como gerar o APK

### Opção 1 — Android Studio (no computador)
1. Instale o Android Studio: https://developer.android.com/studio
2. Abra a pasta `ProCam` (File > Open).
3. Espere o Gradle sincronizar (baixa tudo sozinho).
4. Ligue o celular com "Depuração USB" ativada e clique em ▶ Run.
   Ou: Build > Build APK(s) e copie o arquivo para o celular.

### Opção 2 — GitHub (sem instalar nada)
1. Crie um repositório no GitHub e envie esta pasta.
2. O GitHub compila sozinho (aba **Actions** > "Gerar APK").
3. Baixe o APK em **Artifacts** e instale no celular.

## Próximos passos possíveis
- Foto em RAW (DNG)
- Modo vídeo com bitrate/FPS manuais
- Focus peaking e zebra de superexposição
- Escolha das lentes extras (ultrawide/tele) quando o aparelho permitir
