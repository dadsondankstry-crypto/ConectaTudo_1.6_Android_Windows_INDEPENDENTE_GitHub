# ConectaTudo 1.6 — espelhamento para Redmi Pad 2

Este projeto é evolução da 1.5, com `applicationId` independente (`br.app.conectatudo.v16`).

## O que foi adicionado
- Página leve `http://IP-DO-WINDOWS:8765/tablet?pin=PIN` para **espelhar a tela atual** do Legion Go no navegador ou via botão "Modo tablet" do Android.
- Botão no Windows com instruções para o Redmi Pad 2.
- Botão para salvar diagnóstico TXT no Windows, sem guardar PIN ou código de controle.
- O painel Android tem botão dedicado para abrir o visualizador no tablet, após informar IP/PIN do Windows.
- GitHub Actions atualizado para gerar APK 1.6, EXE 1.6 e incluir `tablet.html` no PyInstaller.
- Teste da rota `/tablet` e do bloqueio de `/api/info` com PIN errado.

## O que NÃO faz
- **Não cria monitor estendido do Windows.** Estender de verdade requer driver de monitor virtual compatível no Windows, mais configuração e testes. Nenhum driver ou solução de terceiros é instalado silenciosamente por este projeto.
- Não usa vídeo H.264; mantém captura MJPEG, com latência conforme rede e hardware.
- Não inclui nuvem, retransmissão pública ou criptografia própria: use rede local confiável ou VPN privada, nunca exponha a porta 8765 na internet pública.
- Controle Android permanece experimental e sujeito a permissões do Android.

## Uso
1. Execute o programa Windows 1.6 e marque "Compartilhar tela deste Windows".
2. Ponha Redmi Pad 2 e Legion Go no mesmo Wi-Fi ou hotspot (sem internet funciona).
3. No Redmi Pad 2 abra app 1.6, coloque IP/PIN do Windows e toque em "Modo tablet: espelhar tela do Legion Go".
4. Toque "Tela cheia" no visualizador para melhor aproveitamento do tablet.
5. Se não conectar, no Windows use "Gerar diagnóstico TXT" e confira o Wi-Fi, IP, PIN e firewall local.

Para compilar Windows, `windows/tablet.html` deve ser empacotado junto do painel. Para compilar Android, execute `gradle -p android :app:assembleDebug` com Gradle 8.9, JDK 17 e SDK 35 já preparados.
