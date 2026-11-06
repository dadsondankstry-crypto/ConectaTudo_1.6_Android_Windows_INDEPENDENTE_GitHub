# NOVO NA 1.6: consulte README_1.6_ADICIONAL.md para o modo tablet (espelhamento, não extensão real) e diagnóstico TXT no Windows.

## Correções herdadas da versão 1.5 (preservadas na 1.6)

- IP e PIN visíveis diretamente na tela Android enquanto o servidor estiver ativo, sem abrir QR Code. O IP continua visível mesmo se o servidor parar, com aviso de status.
- Botão de diagnóstico para identificar interfaces Wi-Fi/hotspot, VPN e rede móvel e copiar o relatório sem expor PIN.
- Priorização dos IPs de Wi-Fi/hotspot na lista; seleção explícita do IP ao copiar ou compartilhar QR.
- Aviso importante: a captura fornecida mostra telefone em 5G, sem Wi-Fi. IP `10.x.x.x` da operadora normalmente não é alcançável pelo Windows devido a CGNAT. Dois aparelhos devem estar no mesmo Wi-Fi/hotspot OU ter a mesma VPN privada configurada. Mudar de versão não altera essa regra de rede.
- Correção de texto contraditório no painel Android que dizia que controle Android não estava implementado.
- `applicationId` novo `br.app.conectatudo.v16`, instalador Windows com novo AppId; versões anteriores preservadas.

**Limitações:** Controle Android continua experimental, JPEG/MJPEG não é H.264. A compilação e os testes em dispositivos reais ainda não estão confirmados. A porta 8765 só deve ser usada em rede privada confiável / VPN, nunca exposta publicamente. Usar somente uma versão de servidor por vez no mesmo aparelho.

# ConectaTudo 1.6 — Android + Windows — Instalação independente

**Projeto completo (fonte)**: ao enviar este ZIP à **raiz** de um repositório GitHub, os workflows `.github/workflows/android.yml` e `windows.yml` tentam produzir o **APK Android**, o **EXE Windows portátil** e, quando disponível, o instalador Windows.

A versão Android usa `applicationId = br.app.conectatudo.v16` (é **outro aplicativo**; não substitui 1.3, 1.2 ou 1.1). O EXE Windows é `ConectaTudo-Windows-1.6.exe`; o instalador opcional recebe AppId diferente. **Os aplicativos de versões diferentes compartilham a porta 8765: execute só um servidor por aparelho de cada vez.**

## O que foi aprimorado na 1.4

1. **Transmissão de tela mais responsiva sem trocar de protocolo:** continua usando **MJPEG**, não H.264. No Windows, há um único capturador (opcionalmente acelerado com MSS), que transmite quadros em cache a todos os visualizadores. Modos *Econômico (~6 fps)*, *Equilibrado (~9 fps)*, *Nítido (~6 fps)* e *Baixa latência (até ~12 fps)*. O FPS real depende da carga do Legion Go, da rede e da tela. Não há garantia de transmissão suave de jogos.
2. **Android transmite com perfis configuráveis:** Econômico, Equilibrado, Nítido ou Baixa latência. A captura só começa após a permissão **MediaProjection** que o Android mostra para cada sessão. O fluxo MJPEG envia apenas quadros novos (evita duplicação constante de imagens antigas).
3. **Controle remoto NOS DOIS SENTIDOS**:
   - **Celular/Tablet → Windows:** preservado da 1.3, com toques, rolagem e teclas, e adicionado arrastar janelas pela imagem.
   - **Windows → Celular/Tablet Android:** **novo, experimental** via `AccessibilityService` opt-in, gestos, deslizes, início/voltar/recentes e inserção de texto em campo editável normal. Não controla telas de bloqueio, senhas nem recursos que o Android proteja.
   - Em **qualquer aparelho controlado**, são obrigatórios: **PIN de conexão (6 dígitos), compartilhamento de tela ativo e autorização explícita temporária com um SEGUNDO código de 12 dígitos**. A autorização dura até 15 minutos e pode ser revogada imediatamente.
4. **Interface web universal** para controlar o Windows ou o Android: toque ou clique na imagem, arraste sobre ela para simular gesto, botões dedicados para Android (Voltar/Início/Recentes). A página web é servida pelo próprio aparelho de destino. Nada exige câmera no Legion Go.
5. **Preservadas as funções da 1.3**: descoberta local, QR Code Android, conexão por IP e PIN, troca de textos, transferência de arquivos, progresso, envio de pastas ZIP no Windows e envio retomável por blocos pelo painel web enquanto o receptor continua ativo.

## Como começar na rede Wi-Fi ou hotspot (OFFLINE)

1. Instale o EXE no Legion Go e o novo APK Android, conecte os dois ao **mesmo Wi-Fi/hotspot**. O hotspot pode funcionar **sem internet** quando o sistema permitir.
2. Abra o ConectaTudo nos dois. Em um, copie o **IP e PIN** de seis números exibidos; no outro, digite IP + PIN e clique em **Conectar**. Também é possível usar **Procurar aparelhos** (LAN) e QR Code entre Androids.
3. Para **ver a tela do Legion Go** no celular, marque no Windows *Compartilhar tela* e clique no Android em *Ver tela do outro dispositivo*.
4. Para **controlar o Legion Go no celular**, no Windows marque *Compartilhar tela* e aperte *Permitir controle (15 min)*; anote o código extra de 12 números. No Android, abra *Controlar aparelho remoto*, preencha o código extra no painel web e clique ou arraste na imagem.
5. Para **ver/controlar o Android no Legion Go**:
   - No Android 1.4 toque **Habilitar controle Android (acessibilidade)** e habilite o serviço **ConectaTudo — controle remoto autorizado** em Configurações de Acessibilidade. No POCO/HyperOS pode ser necessário permitir *Configurações restritas* nas informações do app. Faça isso somente após conferir que instalou este projeto confiável.
   - Volte ao ConectaTudo Android, escolha o perfil da tela e clique **Compartilhar minha tela**. Aceite a solicitação do Android.
   - Clique em **Permitir controle deste Android por 15 minutos**. Um código extra de 12 dígitos aparecerá. **Não é o mesmo PIN de conexão.**
   - No programa Windows, preencha o IP e PIN do Android e clique **Controlar aparelho remoto**. No painel web, digite o código extra do Android. Agora é possível clicar/deslizar sobre a imagem. Os botões Voltar/Início/Recentes só aparecem para Android.
   - Para interromper imediatamente, toque **Bloquear controle deste Android agora**, use **Bloquear controle** na notificação ou desative **Compartilhar minha tela** / o serviço de acessibilidade.
6. Para **transferir arquivos** use *Enviar arquivos*. Para pastas completas, no Windows há *Enviar pasta (ZIP)*. Para retomada por blocos use o painel web **do aparelho receptor** (a versão nativa ainda não faz retomada).

## Conexão pela internet (modo remoto)

Não existe P2P NAT traversal próprio, servidor de descoberta na nuvem ou retransmissão nesta versão. Para aparelhos em redes diferentes, conecte-os manualmente à **mesma VPN privada com criptografia**, como Tailscale, e use o IP da VPN + PIN. **Não encaminhe a porta 8765 do roteador à internet pública.**

**Segurança:** o ConectaTudo comunica por **HTTP sem TLS próprio**. O segundo código protege o controle remoto, mas **não criptografa as imagens, arquivos ou mensagens**. Use somente rede local confiável ou VPN privada que criptografe o trajeto. Acessibilidade dá acesso amplo ao controle da tela: habilite só se realmente quiser o controle do Android e desabilite quando não estiver usando. O serviço não coleta nem envia eventos ou texto da tela; ele recebe apenas gestos e texto voluntariamente enviados pelo controlador. O sistema poderá bloquear certas ações.

## O que ainda NÃO está implementado

- Streaming de vídeo H.264/WebRTC com áudio e 30/60 fps garantidos; MJPEG ainda exige recursos de CPU, e atrasos são possíveis.
- Conexão automática mundial sem VPN, servidor próprio, conexão pública por ID, retransmissão TURN.
- Criptografia de ponta a ponta nativa, compartilhamento de áudio, controle de tela bloqueada/UAC, transmissão para vários destinos com controle coordenado, controle de Android sem o serviço de acessibilidade.
- Retomada permanente de transferências após reiniciar o servidor; retomada pela interface nativa Windows/Android.

## GitHub Actions

Envie **os arquivos deste ZIP para a raiz do repositório**, sem uma pasta extra. Não esqueça `.github/`.

| Artefato | Sistema |
| --- | --- |
| `ConectaTudo-Android-1.6-INDEPENDENTE-APK` | Android 8+ (APK `app-debug.apk`) |
| `ConectaTudo-Windows-1.6-EXE` | Windows (`ConectaTudo-Windows-1.6.exe` portátil) |
| `ConectaTudo-Windows-1.6-INSTALADOR` | Instalador opcional (`ConectaTudo-Setup-1.6.exe`) |

Android: JDK 17, Gradle 8.9, AGP 8.7.3, compileSdk 35, targetSdk 35. Windows: Python 3.12, Pillow, psutil, MSS e PyInstaller. Workflows executam nos servidores GitHub (precisam de internet para baixar dependências e compilar).

## Verificações feitas

- Testes locais do protocolo Windows com `python -m unittest discover -s tests -v`.
- Verificação sintática do código Python e do JavaScript da interface web.
- Verificação da estrutura do ZIP, workflow e do `applicationId` independente.

**Ainda NÃO foram compilados localmente nem APK nem EXE; ainda não se verificou a compatibilidade do serviço de acessibilidade/MediaProjection em seu POCO X7 Pro, Redmi Pad 2 ou Legion Go.** O GitHub Actions confirmará o build; testes em aparelhos confirmarão captura e controle. Se aparecer falha, envie o log de erro do workflow para correção.

### QR Code entre versões
A leitura QR foi corrigida: o leitor Android aceita o esquema estável `conectatudo://connect?...` e os esquemas numerados de versões anteriores (`conectatudo14://...`). O QR gerado pela versão 1.5 usa o esquema estável. A tela do app sempre permite digitar IP e PIN se a câmera ou o protocolo do QR falhar.
