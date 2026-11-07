# Bergapipe

Fork pessoal do [PipePipe](https://github.com/InfinityLoop1308/PipePipe) que acompanha só as versões
**stable** do criador e acrescenta funcionalidades próprias. Instala ao lado do PipePipe original
(ID `io.github.agelinena.bergapipe`).

## Como acompanha o PipePipe

O workflow [`.github/workflows/bergapipe.yml`](.github/workflows/bergapipe.yml) roda todo dia:

1. Lê a última release **stable** do PipePipe (o GitHub nunca devolve pre-release em `releases/latest`).
2. Pega os commits exatos do Client e do Extractor daquela tag (os submódulos do repositório `PipePipe`).
3. Junta (`git merge`) o Client daquela versão no `main` e grava a base em [`berga/upstream.properties`](berga/upstream.properties).
4. Compila com o Extractor da mesma versão, assina e publica a release `vX.Y.Z-bergaN`.

Se a junção conflitar, o workflow abre uma issue com os arquivos em conflito e não publica nada.

Um push no `main` que mexe em código também publica, como revisão nova (`berga2`, `berga3`...).
Push só de documentação (`.md`) não roda nada. Para publicar de novo sem mudança:
**Actions → Bergapipe → Run workflow**, com **publicar** marcado.

## Atualização no celular

O app procura a release `latest` deste repositório ao abrir (no máximo a cada 6 horas), e o botão
de procurar atualização nas configurações faz a mesma coisa na hora. Com versão nova, aparece uma
notificação: tocar em **Atualizar** baixa o APK e instala. Na primeira vez o Android pede a
permissão de instalar apps do Bergapipe.

## Onde fica o que é nosso

Para as versões do criador entrarem sem conflito, quase tudo fica no módulo [`berga/`](berga/):

| Arquivo | O que faz |
|---|---|
| `berga/app-overlay.gradle` | ID, nome, versão (`X.Y.Z-bergaN`) e nome dos APKs |
| `berga/upstream.properties` | Versão stable do PipePipe usada como base |
| `berga/src/main/.../update/` | Atualizador pelo GitHub deste repositório (módulo `:berga`) |
| `berga/app-src/` | Código que usa classes do PipePipe, compilado junto com o app: pastas, backup automático e transmissão |

O módulo `:berga` não enxerga as classes do app; o que precisa delas (telas, listas, player)
fica em `berga/app-src/`, que o overlay soma às pastas de código do app.

Os únicos ganchos em arquivos do PipePipe (procure por `Bergapipe`):

| Arquivo | Gancho |
|---|---|
| `settings.gradle` | `include ':berga'` |
| `app/build.gradle` | `apply from:` do overlay, na última linha |
| `app/.../NewVersionWorker.kt` | Manda a checagem de versão para o atualizador do Bergapipe |
| `app/.../settings/tabs/Tab.java` | Tipo de aba `BERGA_FOLDERS` |
| `app/.../info_list/dialog/StreamDialogDefaultEntry.java` | Entradas "Salvar na pasta" e "Transmitir na TV" |
| `app/.../info_list/dialog/InfoItemDialog.java` | Põe as entradas no menu de segurar o vídeo |
| `app/.../fragments/detail/VideoDetailFragment.java` | Botões "Pasta" e "TV" na tela do vídeo (`BergaDetail`) |
| `app/src/main/res/xml/backup_settings.xml` | Item "Backup automático" em Backup e restauração |

Regra para funcionalidade nova: código e recursos em `berga/`, com prefixo `berga_` nos recursos.
Nos arquivos do PipePipe, só ganchos de poucas linhas, marcados com `// Bergapipe`.

## Pastas

- A aba **Pastas** entra sozinha na página principal na primeira abertura (dá para tirar ou mover
  em Ajustes → Conteúdo → Conteúdo da página principal).
- Segurar um vídeo em qualquer lista → **Salvar na pasta**; na tela do vídeo, botão **Pasta**.
- Ficam num banco próprio, `berga_pastas.db`. O backup automático inclui as pastas; o export
  manual do PipePipe, não.

## Backup automático

Ajustes → Backup e restauração → **Backup automático**.

- Destinos: Nextcloud/WebDAV, Backblaze B2/S3 (assinatura AWS V4), Google Drive (OAuth pelo
  navegador, escopo `drive.file`, com um projeto seu no Google Cloud) e pasta no celular.
- Diário, com o celular carregando e no Wi-Fi; guarda os 14 mais recentes.
- O arquivo é o zip do "Exportar banco de dados" do PipePipe mais `berga_pastas.db`.
  Com criptografia: AES-256-GCM, chave por PBKDF2 da senha (`.zip.enc`).
- Senhas e chaves ficam fora das configurações do PipePipe (não vão para o backup), cifradas
  com uma chave do Android Keystore.
- **Restaurar um backup** troca banco, configurações e pastas e reinicia o app.

## Transmissão para a TV

Botão **TV** na tela do vídeo, ou segurar um vídeo → **Transmitir na TV**.

- **Chromecast**: Google Cast SDK com o receptor padrão do Google (precisa do Play Services).
- **TVs DLNA** (Samsung e outras): descoberta por SSDP e controle por UPnP AVTransport (SOAP),
  por socket próprio (`LanHttp`), já que o app bloqueia `http://` nas bibliotecas de rede.
- O celular baixa o melhor H.264 até 1080p e o melhor AAC (por URL ou pela sessão SABR, com as
  peças dos downloads do PipePipe), junta com o ffmpeg num MP4 e serve pela rede local com
  Range e cabeçalhos DLNA (`MediaServer`). A TV puxa o vídeo do celular, que precisa ficar no
  mesmo Wi-Fi; o `CastService` mantém o app vivo e mostra os controles na notificação.
- **Chromecast toca enquanto baixa** (`CastHlsStream`): os downloads crescem em arquivos,
  pipes alimentam o ffmpeg, que gera uma playlist HLS (pedaços MPEG-TS de 4 s) crescendo; a TV
  começa com 3 pedaços. Se falhar antes de a TV começar, cai para baixar tudo e depois tocar.
- **TVs DLNA** esperam baixar tudo (não tocam HLS de forma confiável).
- Enquanto a TV está conectada, uma barra fica no rodapé de todas as telas (`CastBar`, por
  `ActivityLifecycleCallbacks`); a janela de controle só minimiza. Ao transmitir, o player do
  celular é fechado.

## Quando um build falha

O workflow abre (ou comenta) a issue **Build do Bergapipe falhando** com os erros do Gradle, e
fecha a issue quando volta a compilar.

## Compilar

O Client usa `includeBuild('../PipePipeExtractor')`: o Extractor precisa estar ao lado, no commit
de `berga/upstream.properties`. Requer JDK 25 e Android SDK (compileSdk 37). No Windows, o git
precisa de `core.longpaths=true`.

## Assinatura

A chave fica nos secrets `BERGAPIPE_KEYSTORE` (PKCS12 em base64) e `BERGAPIPE_KEYSTORE_PASSWORD`,
alias `bergapipe`. Sem ela, o app instalado não aceita atualização: guarde uma cópia fora do GitHub.

## Licença

GPLv3, como o PipePipe.
