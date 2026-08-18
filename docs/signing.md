# Assinatura oficial do GameDrop

O GameDrop usa uma chave de assinatura persistente para que versões futuras possam atualizar uma instalação existente sem exigir desinstalação.

## Fingerprint oficial

O SHA-256 público do certificado oficial fica em `signing/official-cert-sha256.txt`.

A chave privada, o keystore e as senhas **nunca** devem ser commitados no repositório.

## GitHub Secrets obrigatórios

Configure em **Settings → Secrets and variables → Actions → Repository secrets**:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

O workflow reconstrói o keystore apenas no diretório temporário do runner e não imprime os valores nos logs.

**Status em 18/08/2026:** os quatro Repository Secrets foram cadastrados pelo proprietário. A validação final é feita exclusivamente pelo pipeline oficial, que só publica o APK se conseguir reconstruir o keystore e verificar o certificado esperado.

## Como gerar uma build oficial

O workflow `Build GameDrop APK` pode gerar a build oficial de duas formas, sempre no ref `gamedrop-apk-build`:

1. por um `push` relacionado ao desenvolvimento nessa branch; ou
2. manualmente em **Actions → Build GameDrop APK → Run workflow**, selecionando `gamedrop-apk-build`.

Executar o workflow em outro ref não libera a etapa de assinatura oficial.

## Validação antes de distribuir

O pipeline de release deve:

1. executar os testes;
2. compilar `assembleRelease` com o keystore oficial;
3. validar o APK com `apksigner verify --verbose --print-certs`;
4. extrair o SHA-256 do certificado;
5. comparar com `signing/official-cert-sha256.txt`;
6. validar `applicationId`, `versionCode` e `versionName`;
7. somente então disponibilizar o APK.

Se o fingerprint não corresponder, o workflow falha e o APK não deve ser publicado.

## Migração da chave debug antiga

As primeiras builds de teste foram assinadas por chaves debug temporárias de runners diferentes. Essas chaves privadas não estão disponíveis e não podem ser substituídas silenciosamente. Portanto, a primeira instalação assinada com a chave oficial exige uma migração única: desinstalar a build debug antiga e instalar a build oficial. Depois disso, todas as atualizações devem continuar usando a chave oficial acima.
