# Telumia TV

![Telumia](https://raw.githubusercontent.com/Pepeu2010/telumia/main/assets/brand/telumia-banner.png)

**Seu cinema, suas séries e novelas — com foco no Brasil.** Cliente nativo Telumia para Android TV, desenvolvido sobre a infraestrutura do Nuvio TV com Kotlin, Compose TV e Media3. Possui marca e identidade próprias, preservando contas, biblioteca, progresso e addons compatíveis.

## Versão 1.0.0

A versão atual é **1.0.0**, sem sufixo alpha, com novo código de versão 10000. A versão está publicada como release regular e Latest. Passaram 214 testes selecionados, dez casos nativos de frames e startup real de 30 segundos nas APIs 24/36. O APK universal funciona nas arquiteturas suportadas; também há variantes ARM64, ARM32, x86 e x86_64. A distribuição continua **Android TV Full Debug**, com a identidade e assinatura de desenvolvimento anteriores para permitir atualização. Não há interface específica para celular.

Inclui Home e detalhes cinematográficos, seleção e edição local de avatares, navegação/motion por D-pad, cache Auto/Manual, momentos salvos e mesclagem durável de coleções/addons. A base de extração de frames usa decoder Media3 separado, timestamps reais e transporte de prévia restrito à origem. A integração visual das miniaturas/filmstrip com o controle ainda está em desenvolvimento. [Alcance e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/VERSION_100.md).

Buscar **Iludida** consulta nomes alternativos e prefere a edição brasileira quando uma fonte instalada oferece seus 78 capítulos. Os IDs de reprodução são preservados. Fontes com 31 episódios conservam essa edição, sem capítulos inventados. Português do Brasil é o idioma inicial dos metadados de perfis novos.

[Downloads e status](https://github.com/Pepeu2010/telumia/releases) · [Especificação e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/TELUMIA.md) · [Roadmap](https://github.com/Pepeu2010/telumia/blob/main/docs/ROADMAP.md).

## Desenvolvimento

O código posterior à release 1.0.0 integra miniaturas reais e filmstrip ao seek por D-pad, com timestamp decodificado, preservação do foco e do player principal, cancelamento ao trocar contexto e respeito a movimento Reduzido/Desligado. Passaram 222 testes selecionados; a QA de componentes passou em APIs 24/36 e 720p/1080p/4K. Um timeout de extração observado anteriormente no emulador não foi reproduzido na execução seguinte e continua registrado para investigação. Esses testes não comprovam todas as rotas do player com conta autenticada nem desempenho em TV física. **Os APKs publicados da 1.0.0 ainda não incluem essa integração visual.** [Evidências e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/TV_TIMELINE_SEEK.md).

O código em desenvolvimento inclui o editor de avatar pessoal no fluxo existente de perfis: biblioteca integrada com 64 ilustrações licenciadas, arquivo local, clipboard com URI de imagem, recorte, zoom e versões PNG otimizadas. Em aparelhos sem seletor de documentos, a escolha usa fotos locais com permissão solicitada somente ao abrir a ação. O armazenamento é privado, separado do cache e isolado por conta e identidade do perfil.

A QA manual também percorreu o fluxo real de instalação de addon, seleção de fonte, reprodução Media3 e seek/retorno por D-pad em uma AVD nova API 36/1080p, com vídeo original e modo convidado. Thumbnail e filmstrip foram capturados enquanto o player principal permanecia pausado. Isso não comprova conta oficial ou todos os providers. Uma falha de início da instrumentação no ambiente frio foi preservada, sem declarar correção por uma repetição aprovada. A nota da tela Conta agora explica a atualização periódica e ao retornar ao app; pt-BR e inglês foram conferidos no APK, e o build posterior passou em 222 testes selecionados. Esses incrementos continuam fora dos APKs publicados da 1.0.0.

Os incrementos anteriores possuem testes de decoder e interface em 720p, 1080p e 4K. A validação usa emulador e imagens controladas; não comprova todos os seletores de fabricantes nem desempenho em TV física. [Evidências e pendências](https://github.com/Pepeu2010/telumia/blob/main/docs/PROFILE_STUDIO.md).

```sh
./gradlew :app:assembleFullDebug
```

Use as instruções de configuração local do [projeto central](https://github.com/Pepeu2010/telumia). Não publique arquivos de credenciais ou keystores. A meta integral continua em execução: faltam Scene Info completo, Source Intelligence, Live TV/EPG, Phone Remote, Smart Collections, downloads avançados e o redesign de todas as superfícies. Sincronização com conta oficial, reprodução/HDR e desempenho em TV física exigem evidências próprias. Avatares locais e bookmarks não são anunciados como sincronizados por contratos remotos que não os suportam.

## Licença

[GPL-3.0](LICENSE). Copyrights, autoria e avisos de terceiros preservados. [Créditos do código](FORK_NOTICE.md).
