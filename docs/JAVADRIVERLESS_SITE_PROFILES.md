# Perfis de sites do JavaDriverless

Use perfis de site quando um site precisar de uma superfície de navegador mais restrita que a
suíte padrão de mapeamento de fingerprint. O objetivo é isolar o que difere de uma execução
funcional com Selenium/Chrome sem remover proteções globalmente.

Esses arquivos JSON são exemplos de validação, e não predefinições públicas adicionais. O catálogo do SDK público
se limita a `windows`, `linux`, `docker`, `phantompass` e `seleniumlike`. As variantes JSON do CreepJS, PixelScan
e IPhey permanecem aqui apenas para permitir a reprodução dos contratos mapeados.

## Comparar uma URL

```bash
python3 tools/compare_site_modes.py \
  --url "https://example.com/login" \
  --site-id example-login \
  --fingerprint-profile docs/site-profiles/selenium-like.json
```

O relatório é gravado em:

```text
captures/fingerprint-comparison/<session>/mode-diff.md
```

Ele compara os artefatos `selenium-pure` e `java-driverless`:

- URL final e título
- tamanho do corpo
- valores do navigator
- sinais brutos de fingerprint
- indícios de detecção

Por padrão, o mesmo comando também captura `selenium-driverless`. Quando esse artefato
existe, o relatório o usa como base de comparação com `java-driverless`, pois essa é a
comparação mais próxima para sites nos quais o `selenium-driverless` do Python funciona,
mas o JavaDriverless não.

Se precisar do mesmo proxy na base Python:

```bash
python3 tools/compare_site_modes.py \
  --url "https://example.com/login" \
  --site-id example-login \
  --fingerprint-profile docs/site-profiles/selenium-like.json \
  --selenium-driverless-proxy http://127.0.0.1:8899
```

## Perfil semelhante ao Selenium

`docs/site-profiles/selenium-like.json` mantém o runtime principal do JavaDriverless,
mas define as superfícies configuráveis de fingerprint nos modos nativo/real:

- `webRtc.mode = real`
- `ports.mode = real`
- `speech.mode = real`
- `connection.mode = real`
- `screen/webgl/media/audio/canvas/clientRects/fonts = real`
- `timezoneId = ""` para que o Chrome mantenha o fuso horário nativo, salvo sobrescrita por
  `--timezone` ou `--profile-country`

Esta é a fixture por trás do preset de compatibilidade explícito `seleniumlike`. Ela mantém
intencionalmente `legacy-spoof`; o WebRTC é real com UDP habilitado e o modo de armazenamento
omitido resolve para o padrão legado `spoof`. Por isso, ela é útil para diagnóstico A/B, mas não
é um preset anti-vazamento e nunca é selecionada automaticamente.

Execute um site mapeado com ela:

```bash
python3 tools/fingerprint_site_capture.py java-driverless \
  --session profile-check \
  --sites pixelscan \
  --fingerprint-profile docs/site-profiles/selenium-like.json
```

Para testes de proxy/geolocalização, mantenha o perfil, mas passe a localização explicitamente:

```bash
python3 tools/fingerprint_site_capture.py java-driverless \
  --session profile-check-de \
  --sites trustmyip-timezone \
  --fingerprint-profile docs/site-profiles/selenium-like.json \
  --proxy http://127.0.0.1:8899 \
  --profile-country DE \
  --locale de-DE \
  --timezone Europe/Berlin \
  --languages de-DE,de,en-US,en
```

## Perfil de servidor Linux Docker

`docs/site-profiles/linux-docker-server.json` é uma fixture de compatibilidade mantida para
experimentos antigos com Linux headless. Ela usa a política legada de spoofing; não é evidência
de que a política `minimal-cdp` sem wrappers seja segura no Chrome headless puro. A versão do
Chrome permanece dinâmica (`browserVersion = ""`), então o JavaDriverless resolve a versão a
partir do binário do navegador na inicialização, em vez de congelar uma versão antiga no JSON.

A imagem oficial não seleciona mais este arquivo. O padrão é o preset canônico `docker`,
`JAVA_DRIVERLESS_HEADLESS=0`, iniciado como o usuário sem privilégios `javadriveless` por meio do
`java-driverless-visible-xvfb`. O caminho canônico mantém o sandbox do Chrome habilitado e não
adiciona `--no-sandbox` nem `--disable-setuid-sandbox`; essas flags permanecem apenas em helpers
legados explícitos.

Use-o com as flags de runtime seguras para contêiner:

```bash
JAVA_DRIVERLESS_HEADLESS=1 \
JAVA_DRIVERLESS_CONTAINER_FLAGS=true \
JAVA_DRIVERLESS_HEADLESS_GL=swiftshader \
JAVA_DRIVERLESS_BROWSER_BINARY=/usr/bin/google-chrome-stable \
python3 tools/fingerprint_site_capture.py java-driverless \
  --session docker-profile-check \
  --sites browserscan \
  --fingerprint-profile docs/site-profiles/linux-docker-server.json
```

Para experimentos de runtime Docker/headless específicos de um site, os argumentos padrão
do Chrome podem ser ajustados sem alterar o comportamento normal:

```bash
JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS='--disable-accelerated-2d-canvas,--mute-audio'
JAVA_DRIVERLESS_EXTRA_ARGS='--enable-accelerated-2d-canvas;--use-angle=swiftshader'
```

`JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS` é separado por vírgula, ponto e vírgula ou nova linha e
compara argumentos completos ou prefixos. `JAVA_DRIVERLESS_EXTRA_ARGS` é separado por
ponto e vírgula ou nova linha para que argumentos que contêm vírgulas permaneçam intactos.
Deixe ambos sem definição para manter os padrões atuais.
Flags de armazenamento gerenciado, debugger e proxy são rejeitadas em `JAVA_DRIVERLESS_EXTRA_ARGS`; configure-as
pelas APIs estruturadas de armazenamento/proxy para que um único valor global de ambiente não faça dois
perfis de cliente compartilharem cookies nem exponha o debugger. `minimal-cdp` também rejeita SwiftShader e
`--disable-gpu` depois de montar todos os argumentos efetivos e revalida tudo antes da inicialização.

Para `minimal-cdp`, não use este comando headless puro. Use o launcher visível do Xvfb
documentado abaixo.

### Política sem wrappers para perfis PhantomPass/SDK

Perfis personalizados antigos e persistidos continuam legíveis com sua política legada, mas todos
os novos presets oficiais Windows/Linux/Docker são fixados em `minimal-cdp`. O gerador aceita
somente os cinco nomes canônicos e prepara modos apoiados por CDP, argumentos/preferências do
Chrome ou pelo sistema operacional real:

```java
SdkProfile profile = SdkProfilePresets.linux(
        "account-123", "Account 123", "BR");
```

Para o adaptador SaaS:

```java
PhantomPassProfile contract = SdkProfilePresets.phantomPass(
        SdkProfilePreset.LINUX, "account-123", "Account 123", "BR");
contract.proxy = runtimeProxy;
contract.egressInfo = knownEgress;
ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);
```

`minimal-cdp` rejeita `diversityEnabled=true` porque o ruído de canvas/áudio/DOMRect/WebGL exige
wrappers JavaScript detectáveis. Os presets Windows/Linux visíveis usam a tela real do sistema;
o headless puro é rejeitado antes de o Chrome iniciar. Um servidor ou contêiner sem tela física
deve executar o Chrome no modo visível sob o launcher Xvfb validado abaixo. Identidades de outro
sistema operacional ou móveis falham antes do Chrome, em vez de expor o host silenciosamente.
`minimal-cdp` também rejeita attach a um Chrome externo já iniciado: nesse fluxo não é possível
provar as flags de launch nem a identidade do primeiro statement de pages/workers existentes.
O attach de compatibilidade continua disponível com `legacy-spoof` e preserva ownership externo.

### Matriz operacional — 2026-07-14

| Runtime | estado de `minimal-cdp` | Evidência e operação exigida |
| --- | --- | --- |
| Windows, Chrome visível | Contrato suportado; gate live preparado | O runner Windows é necessário para validar os presets `windows` e `phantompass` na página, no DedicatedWorker, no popup `rel=noopener` e no worker do popup. Este checkout Linux apenas compila/pula esse gate; não classifique o caminho como validado ao vivo até o job Windows passar. |
| Linux, Chrome visível | Baseline suportado | O Chrome 150 bruto retornou a mesma UA clássica nativa e UA-CH completa de alta entropia na página, no DedicatedWorker e no SharedWorker. Mantenha o perfil no sistema operacional/arquitetura Linux nativos. |
| Docker, Chrome visível sob Xvfb | Caminho de servidor validado | A captura CreepJS Docker/Xvfb produziu 16/16 campos obrigatórios, 24/24 correspondências esperadas e 0 mentiras reportadas. As 24 comparações incluem nove campos WebGL do realm principal e paridade exata da GPU entre página e worker. Um teste live separado com dois processos exigiu UA-CH e WebGL completos e iguais na página, DedicatedWorker, SharedWorker, ServiceWorker e no primeiro statement do ServiceWorker, além de `navigator.webdriver=false` nas duas páginas. Este é o caminho canônico sem UI física. |
| Linux ou Docker, Chrome headless puro | Rejeitado em fail-closed | O Chrome bruto expôs `HeadlessChrome` e SwiftShader. Ocultar o token clássico não preservou uma identidade UA-CH completa no worker. |
| Qualquer SO, headless `legacy-spoof` | Caminho de compatibilidade fora desta matriz | Ele pode usar comportamento sintético/com wrappers. Passar no smoke test não prova que `minimal-cdp` ou todo detector esteja limpo. |

O launcher Xvfb executa um processo Chrome visível em uma tela X11 virtual isolada. “Sem UI física”
e “Chrome headless” são modos diferentes aqui. Valide o contrato do launcher com:

```bash
bash scripts/test-run-visible-xvfb.sh
```

Execute o perfil CreepJS mapeado no Linux sem UI física com este comando seguro para colar:

```bash
bash scripts/run-visible-xvfb.sh --screen-width 1365 --screen-height 900 --avail-width 1365 --avail-height 860 -- python3 tools/fingerprint_site_capture.py java-driverless --session creepjs-xvfb-check --sites creepjs --fingerprint-profile docs/site-profiles/linux-docker-server-creepjs-xvfb.json --profile-country BR
```

### PixelScan no Docker/Xvfb

O PixelScan compara hashes renderizados com a superfície visível do navegador. No
Docker/Xvfb, simular uma GPU de desktop enquanto o Chrome renderiza por Mesa/llvmpipe
é detectado como mascaramento. Use o perfil de site com renderização nativa para este site:

```bash
docker run --rm --network host \
  -v "$PWD":/work \
  -v /usr/share/fonts:/host-fonts/usr-share:ro \
  -w /work \
  -e JAVA_DRIVERLESS_BROWSER_BINARY=/usr/bin/google-chrome-stable \
  javadriveless-docker-profile:test \
  bash -lc 'mkdir -p /usr/local/share/fonts/host-local \
    && cp -a /host-fonts/usr-share/* /usr/local/share/fonts/host-local/ 2>/dev/null || true; \
    fc-cache -f >/dev/null 2>&1 || true; \
    exec /usr/local/bin/java-driverless-visible-xvfb \
      --screen-width 1365 --screen-height 900 \
      --avail-width 1365 --avail-height 860 -- \
    mvn -q -DskipTests compile exec:java \
      -Dexec.mainClass=io.github.selenium.javaDriverless.fpmapping.FingerprintMappingCapture \
      -Dexec.args="java-driverless --session pixelscan-docker-check-UNIQUE \
        --sites pixelscan \
        --fingerprint-profile docs/site-profiles/linux-docker-server-pixelscan-native-rendering.json \
        --profile-country BR"'
```

O container de runtime não inclui Python. Depois da captura Java acima, gere os artefatos
normalizados no host e só então aceite o resultado:

```bash
python3 tools/fingerprint_site_capture.py enrich --artifacts-root captures/fingerprint-comparison/pixelscan-docker-check-UNIQUE/java-driverless
```

Substitua `UNIQUE` nos dois comandos do PixelScan antes de cada captura.

O diretório de fontes montado é intencional: a captura verde do PixelScan no Docker/Xvfb
depende de uma superfície de fontes Linux estável e não mínima. Sem essa superfície,
o PixelScan reporta `Masking detected` mesmo quando o fuso horário, WebRTC, idioma e
modo de renderização são coerentes.

Quando um proxy HTTP, HTTPS ou SOCKS é configurado, o resolver de perfil externo
não habilita WebRTC nativo para PixelScan ou IPHey. O STUN usa UDP fora desses
transportes de proxy e foi comprovado que expõe o egress do host. Por isso, o
caminho com proxy herda a política relay-only/fail-closed do perfil base. Um
valor ausente de WebRTC só é aceito com `disableUdp=true`; um endereço renderizado
ainda precisa pertencer à allowlist de egress medida. WebRTC por proxy exige
um relay TURN controlado separadamente, não uma substituição em JavaScript.

O CreepJS usa um perfil Docker/Xvfb separado porque sua pontuação de resistência a headless
também verifica o esquema de cores CSS, permissões de mídia, vozes de fala e consistência
de hardware dos workers:

```bash
docker run --rm --network host \
  -v "$PWD":/work \
  -v /usr/share/fonts:/host-fonts/usr-share:ro \
  -w /work \
  -e JAVA_DRIVERLESS_BROWSER_BINARY=/usr/bin/google-chrome-stable \
  javadriveless-docker-profile:test \
  bash -lc 'mkdir -p /usr/local/share/fonts/host-local \
    && cp -a /host-fonts/usr-share/* /usr/local/share/fonts/host-local/ 2>/dev/null || true; \
    fc-cache -f >/dev/null 2>&1 || true; \
    exec /usr/local/bin/java-driverless-visible-xvfb \
      --screen-width 1365 --screen-height 900 \
      --avail-width 1365 --avail-height 860 -- \
    mvn -q -DskipTests compile exec:java \
      -Dexec.mainClass=io.github.selenium.javaDriverless.fpmapping.FingerprintMappingCapture \
      -Dexec.args="java-driverless --session creepjs-docker-check-UNIQUE \
        --sites creepjs \
        --fingerprint-profile docs/site-profiles/linux-docker-server-creepjs-xvfb.json \
        --profile-country BR"'
```

O launcher visível inicia um Xvfb isolado, publica e lê de volta `_NET_WORKAREA` e só então
expõe o handshake interno da tela nativa ao Java, depois dessa validação. Não defina
`JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED` manualmente.
Substitua `UNIQUE` antes de cada captura; sessões de captura são evidências imutáveis e não
devem ser reutilizadas nem enriquecidas sobre um resultado antigo.

Na captura Docker/Xvfb validada, o CreepJS ainda mostrou `31% like headless`; os contadores
`headless` e `stealth` ficaram em 0%, não houve seção marcada como lie e o contrato não encontrou
forbidden signal. O valor de 31% é mantido no artefato como característica observável do ambiente,
não como uma alegação de invisibilidade total.

No runtime `minimal-cdp`, os argumentos de idioma são singletons: o valor do perfil substitui o
default, sem dois `--lang`/`--accept-lang` concorrentes. Defaults artificiais de teste e os
overrides de Privacy Sandbox/`AutomationControlled` são removidos antes do launch. O Canvas 2D
acelerado fica nativo. `--ignore-gpu-blocklist` continua presente no Xvfb porque o A/B sem ele
bloqueou WebGL na página e no ServiceWorker; esse estado agora falha com
`creepjs-webgl-unavailable`. Flags de redução de egress/telemetria permanecem separadas dessa
limpeza e não foram removidas com base apenas no CreepJS.

O locale minimal usa o comportamento nativo do Chrome: `navigator.language` precisa coincidir com
o idioma primario do perfil, enquanto `Intl` pode canonicalizar a regiao para a mesma familia
linguistica. Um preflight em `about:blank` rejeita fallback para outra familia antes de qualquer
URL externa. O processo sempre nasce somente com `about:blank`; `startupUrl` e uma URL posicional
isolada sao navegadas apenas depois do gate, e configuracoes com multiplos destinos falham
fechado. Popup normal, popup `noopener`, worker do popup e OOPIF cross-origin aninhado fazem parte
da matriz live. No Linux, timezone e color scheme suportado sao baseline nativo do processo e
chegam ao renderer `noopener` sem override pos-resume; preferencias de media sem transporte nativo
global falham antes do launch. Geolocation continua target-local. Nao ha wrapper de `Intl` ou
`navigator` neste caminho.

O perfil selenium-like permanece no caminho `legacy-spoof`. Nesse modo, a identidade UA-CH do
worker inclui explicitamente `formFactors`; sem isso, o primeiro statement do ServiceWorker podia
divergir da pagina mesmo quando os demais hints coincidiam. O gate live de dois navegadores exige
essa paridade junto do isolamento de cookies, Local/Session Storage, IndexedDB, Cache Storage,
ServiceWorker, processo e lifecycle.

O gate CreepJS também exige os nove campos do WebGL principal: hash geral, hashes de imagens e
pixels, contagem/hash de parâmetros, contagem/hash de extensões e vendor/renderer da GPU. O vendor
e o renderer precisam coincidir com o Worker; divergência produz
`creepjs-webgl-realm-mismatch`. Os mesmos valores normalizados ficam em
`extracted-values.json.contractExtracted.webgl`.

O perfil CreepJS usa `runtimePolicy: "minimal-cdp"`: não instala wrappers de superfície na página
ou nos workers e falha antes do Chrome quando um modo configurado não tem transporte nativo de
CDP/Chrome/SO. Ele define intencionalmente `colorScheme: "dark"` via CDP
`Emulation.setEmulatedMedia`. No Docker/Xvfb, o navegador reportaria caso contrário um esquema de
cores preferido claro, que o CreepJS classifica como sinal `like headless`. Renderização, fontes,
armazenamento, conexão, dispositivos de mídia e fala permanecem nativos.
`hardwareConcurrency` e `deviceMemory` precisam permanecer nativos e coerentes em todos os realms.
As cotas de CPU são refletidas no baseline nativo de runtime. Um limite finito de memória do cgroup
permanece fail-closed por padrão porque o Chrome 150 reporta o `navigator.deviceMemory` do host
dentro desse contêiner e não fornece uma sobrescrita global nativa. Um launcher gerenciado só pode
continuar depois de abrir o mesmo Chrome na mesma tela/cgroup, medir `navigator.deviceMemory` no
loopback e exportar o atestado de memória nativa correspondente. O engine compara esse valor com o
perfil e passa o mesmo ambiente explícito ao Chrome; não defina manualmente nenhuma das variáveis
de atestação.

Esse atestado prova consistência, não isolamento do host. No contêiner validado de 4 GiB, o Chrome
reportou `navigator.deviceMemory=32` e `navigator.hardwareConcurrency=16`. Ambos os valores nativos
são observáveis e potencialmente vinculáveis às características do host e não devem ser descritos
como “sem vazamento”. O Docker padrão não forneceu uma fronteira de isolamento forte para eles.
Eliminar essa divulgação sem wrappers JavaScript detectáveis exige uma VM/microVM com a memória e
a topologia de CPU de guest pretendidas (ou um mecanismo nativo futuro do Chrome).

O repositório também inclui um alvo de prova Docker:

```bash
docker compose run --rm --build javadriveless-proof
```

O serviço Compose versionado aplica o perfil seccomp do Chrome, remove todas as capabilities e
adiciona de volta apenas `SYS_CHROOT`. Um `docker run` simples com o perfil seccomp padrão do
Docker pode negar a criação de namespaces exigida pelo sandbox do Chrome e encerrar o Chrome com
código de saída `133`; não contorne essa condição com `--no-sandbox`.

A imagem padrão `linux/amd64` inicia o preset canônico `docker` com Chrome visível sob sua tela
Xvfb provisionada. `DockerRuntimeProof` lê valores reais do DOM/runtime e grava
`/tmp/java-driverless-docker-proof.json`; ele rejeita `HeadlessChrome`, `navigator.webdriver=true`,
locale, idiomas, fuso horário, hardware, memória do dispositivo, tela/área de trabalho/profundidade/DPR
ausentes ou divergentes, candidatos WebRTC com IP literal, WebGL ausente e SwiftShader sob
`minimal-cdp`. A página de prova é servida por HTTP no loopback para que propriedades que exigem
contexto seguro, como `deviceMemory`, sejam observadas.
Ele não afirma medir o egress DNS do proxy sem um resolver controlado. O comando CreepJS específico
do site acima continua sendo o contrato mais amplo do detector; o smoke da imagem, sozinho, não é
uma alegação universal de anti-detecção.

O workflow de fingerprint externo fornece esse ambiente controlado ausente: um resolver Unbound
recursivo e efêmero restrito ao loopback, uma ponte DoH HTTPS, um certificado NSS limitado à execução
e uma política de DNS seguro gerenciada pelo Chrome. O IP/prefixo do resolver, aliases de ISP e
localização são estabelecidos antes da navegação no site a partir de Akamai Whoami, RIPE RIS e um
quórum GeoIP independente. O gate também verifica o contador de consultas DoH depois que o Chrome
sai, portanto um contrato DNS verde do BrowserLeaks exige resultados atribuídos do site e uso
comprovado do transporte controlado.

## Regra

Não altere os padrões globais apenas porque um site falhou. Primeiro compare o
site com `tools/compare_site_modes.py`; depois crie ou selecione o menor
perfil de site que remova a diferença específica.
