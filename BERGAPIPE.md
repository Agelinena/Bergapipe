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

Um push no `main` só compila, para conferir. Para publicar mudanças do Bergapipe sem esperar o
PipePipe: **Actions → Bergapipe → Run workflow**, com **publicar** marcado.

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
| `berga/src/main/.../update/` | Atualizador pelo GitHub deste repositório |

Os únicos ganchos em arquivos do PipePipe (procure por `Bergapipe:`):

| Arquivo | Gancho |
|---|---|
| `settings.gradle` | `include ':berga'` |
| `app/build.gradle` | `apply from:` do overlay, na última linha |
| `app/.../NewVersionWorker.kt` | Manda a checagem de versão para o atualizador do Bergapipe |

Regra para funcionalidade nova: código e recursos em `berga/`, com prefixo `berga_` nos recursos.
Nos arquivos do PipePipe, só ganchos de poucas linhas, marcados com `// Bergapipe:`.

## Compilar

O Client usa `includeBuild('../PipePipeExtractor')`: o Extractor precisa estar ao lado, no commit
de `berga/upstream.properties`. Requer JDK 25 e Android SDK (compileSdk 37). No Windows, o git
precisa de `core.longpaths=true`.

## Assinatura

A chave fica nos secrets `BERGAPIPE_KEYSTORE` (PKCS12 em base64) e `BERGAPIPE_KEYSTORE_PASSWORD`,
alias `bergapipe`. Sem ela, o app instalado não aceita atualização: guarde uma cópia fora do GitHub.

## Licença

GPLv3, como o PipePipe.
