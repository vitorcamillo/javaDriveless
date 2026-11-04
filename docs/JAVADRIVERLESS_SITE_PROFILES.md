# JavaDriverless Site Profiles

Use site profiles when a website needs a narrower browser surface than the
default fingerprint mapping suite. The goal is to isolate what differs from a
working Selenium/Chrome run without removing protections globally.

These JSON files are validation fixtures, not extra public presets. The public SDK catalog is
limited to `windows`, `linux`, `docker`, `phantompass` and `seleniumlike`. The CreepJS, PixelScan
and IPhey JSON variants remain here only so their mapped contracts can be reproduced.

## Compare one URL

```bash
python3 tools/compare_site_modes.py \
  --url "https://example.com/login" \
  --site-id example-login \
  --fingerprint-profile docs/site-profiles/selenium-like.json
```

The report is written to:

```text
captures/fingerprint-comparison/<session>/mode-diff.md
```

It compares `selenium-pure` and `java-driverless` artifacts:

- final URL and title
- body length
- navigator values
- raw fingerprint signals
- detection hints

By default the same command also captures `selenium-driverless`. When that
artifact exists, the report uses it as the baseline against `java-driverless`,
because that is the closest comparison for sites where Python
`selenium-driverless` works but JavaDriverless does not.

If you need the same proxy on the Python baseline:

```bash
python3 tools/compare_site_modes.py \
  --url "https://example.com/login" \
  --site-id example-login \
  --fingerprint-profile docs/site-profiles/selenium-like.json \
  --selenium-driverless-proxy http://127.0.0.1:8899
```

## Selenium-like profile

`docs/site-profiles/selenium-like.json` keeps the main JavaDriverless runtime,
but sets the configurable fingerprint surfaces to native/real modes:

- `webRtc.mode = real`
- `ports.mode = real`
- `speech.mode = real`
- `connection.mode = real`
- `screen/webgl/media/audio/canvas/clientRects/fonts = real`
- `timezoneId = ""` so Chrome keeps the native timezone unless overridden by
  `--timezone` or `--profile-country`

This is the fixture behind the explicit `seleniumlike` compatibility preset. It intentionally
keeps `legacy-spoof`; WebRTC is real with UDP enabled and the omitted storage mode resolves to the
legacy `spoof` default. Therefore it is useful for A/B diagnosis, but it is not an anti-leak preset
and is never selected automatically.

Run a mapped site with it:

```bash
python3 tools/fingerprint_site_capture.py java-driverless \
  --session profile-check \
  --sites pixelscan \
  --fingerprint-profile docs/site-profiles/selenium-like.json
```

For proxy/geo tests, keep the profile but pass explicit location:

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

## Linux Docker server profile

`docs/site-profiles/linux-docker-server.json` is a retained compatibility fixture for
old headless Linux experiments. It uses the legacy spoof policy;
it is not evidence that the wrapper-free `minimal-cdp` policy is safe in pure
Chrome headless. It keeps the Chrome version dynamic (`browserVersion = ""`),
so JavaDriverless resolves the version from the browser binary at startup
instead of freezing a stale version in the JSON.

The official image no longer selects this file. Its default is the canonical `docker` preset,
`JAVA_DRIVERLESS_HEADLESS=0`, launched as the unprivileged `javadriveless` user through
`java-driverless-visible-xvfb`. The canonical path keeps Chrome's sandbox enabled and does not add
`--no-sandbox` or `--disable-setuid-sandbox`; those flags remain only in explicit legacy helpers.

Use it with the container-safe runtime flags:

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

For site-specific Docker/headless runtime experiments, Chrome default
arguments can be adjusted without changing normal behavior:

```bash
JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS='--disable-accelerated-2d-canvas,--mute-audio'
JAVA_DRIVERLESS_EXTRA_ARGS='--enable-accelerated-2d-canvas;--use-angle=swiftshader'
```

`JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS` is comma/semicolon/newline separated and
matches full arguments or prefixes. `JAVA_DRIVERLESS_EXTRA_ARGS` is
semicolon/newline separated so arguments containing commas remain intact.
Leave both unset for the existing defaults.
Managed storage, debugger and proxy flags are rejected in `JAVA_DRIVERLESS_EXTRA_ARGS`; configure
them through the structured storage/proxy APIs so one global environment value cannot make two
client profiles share cookies or expose the debugger. `minimal-cdp` also rejects SwiftShader and
`--disable-gpu` after all effective arguments have been assembled and revalidates before launch.

For `minimal-cdp`, do not use this pure-headless command. Use the visible Xvfb
launcher documented below.

### Wrapper-free policy for PhantomPass/SDK profiles

Old persisted custom profiles remain readable with their legacy policy, but all new official
Windows/Linux/Docker presets are fixed to `minimal-cdp`. The generator accepts only the five
canonical names and prepares modes backed by CDP, Chrome arguments/preferences or the real OS:

```java
SdkProfile profile = SdkProfilePresets.linux(
        "account-123", "Account 123", "BR");
```

For the SaaS adapter:

```java
PhantomPassProfile contract = SdkProfilePresets.phantomPass(
        SdkProfilePreset.LINUX, "account-123", "Account 123", "BR");
contract.proxy = runtimeProxy;
contract.egressInfo = knownEgress;
ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);
```

`minimal-cdp` rejects `diversityEnabled=true` because canvas/audio/DOMRect/WebGL noise requires
detectable JavaScript wrappers. Visible Windows/Linux presets use the real OS screen;
pure headless is rejected before Chrome starts. A server or container without a physical display
must run Chrome in visible mode under the validated Xvfb launcher below. Cross-OS and mobile
identities fail before Chrome instead of silently exposing the host.
`minimal-cdp` também rejeita attach a um Chrome externo já iniciado: nesse fluxo não é possível
provar as flags de launch nem a identidade do primeiro statement de pages/workers existentes.
O attach de compatibilidade continua disponível com `legacy-spoof` e preserva ownership externo.

### Operational matrix — 2026-07-14

| Runtime | `minimal-cdp` state | Evidence and required operation |
| --- | --- | --- |
| Windows, visible Chrome | Contract-supported; live gate prepared | The Windows runner is required to validate both `windows` and `phantompass` presets across page, DedicatedWorker, `rel=noopener` popup and the popup worker. This Linux checkout only compiles/skips that gate, so do not label the path live-validated until the Windows job passes. |
| Linux, visible Chrome | Supported baseline | Raw Chrome 150 returned the same native classic UA and complete high-entropy UA-CH in the page, DedicatedWorker and SharedWorker. Keep the profile on the native Linux OS/architecture. |
| Docker, visible Chrome under Xvfb | Validated server path | The CreepJS Docker/Xvfb capture produced 16/16 required fields, 24/24 expected matches and 0 reported lies. The 24 comparisons include nine main-realm WebGL fields and exact main/worker GPU parity. A separate two-process live test required complete, equal UA-CH and WebGL in the page, DedicatedWorker, SharedWorker, ServiceWorker and the ServiceWorker first statement, plus `navigator.webdriver=false` in both pages. This is the canonical no-physical-UI path. |
| Linux or Docker, pure Chrome headless | Rejected fail-closed | Raw Chrome exposed `HeadlessChrome` and SwiftShader. Hiding the classic token did not preserve a complete worker UA-CH identity. |
| Any OS, `legacy-spoof` headless | Compatibility path outside this matrix | It may use synthetic/wrapper behavior. Passing its smoke test is not proof that `minimal-cdp` or every detector is clean. |

The Xvfb launcher runs a visible Chrome process on an isolated virtual X11 display. “No physical
UI” and “Chrome headless” are different modes here. Validate the launcher contract with:

```bash
bash scripts/test-run-visible-xvfb.sh
```

Run the mapped CreepJS profile on Linux without a physical UI with this paste-safe command:

```bash
bash scripts/run-visible-xvfb.sh --screen-width 1365 --screen-height 900 --avail-width 1365 --avail-height 860 -- python3 tools/fingerprint_site_capture.py java-driverless --session creepjs-xvfb-check --sites creepjs --fingerprint-profile docs/site-profiles/linux-docker-server-creepjs-xvfb.json --profile-country BR
```

### PixelScan in Docker/Xvfb

PixelScan compares rendered hashes against the visible browser surface. In
Docker/Xvfb, spoofing a desktop GPU while Chrome renders through Mesa/llvmpipe
is detected as masking. Use the native-rendering site profile for this site:

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

Replace `UNIQUE` in both PixelScan commands before every capture.

The mounted font directory is intentional: the green PixelScan Docker/Xvfb
capture depends on a stable, non-minimal Linux font surface. Without that
surface, PixelScan reports `Masking detected` even when timezone, WebRTC,
language, and rendering mode are coherent.

When a HTTP, HTTPS or SOCKS proxy is configured, the external-profile resolver
does not enable native WebRTC for PixelScan or IPHey. STUN uses UDP outside
those proxy transports and was proven to expose host egress. The proxied path
therefore inherits the base profile's relay-only/fail-closed policy. A missing
WebRTC value is accepted only with `disableUdp=true`; a rendered address must
still belong to the measured egress allowlist. WebRTC through a proxy requires
a separately controlled TURN relay, not a JavaScript replacement.

CreepJS uses a separate Docker/Xvfb profile because its headless-resistance
score also checks CSS color-scheme, media permissions, speech voices, and
worker hardware consistency:

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

The visible launcher starts an isolated Xvfb, publishes and reads back
`_NET_WORKAREA`, then exposes the internal native-screen handshake to Java only after that
validation. Do not set `JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED` manually.
Replace `UNIQUE` before every capture; capture sessions are immutable evidence and must not be
reused or enriched over an older result.

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

The CreepJS profile uses `runtimePolicy: "minimal-cdp"`: it installs no page/worker surface
wrappers and fails before Chrome when a configured mode has no native CDP/Chrome/OS transport.
It intentionally sets `colorScheme: "dark"` via CDP
`Emulation.setEmulatedMedia`. In Docker/Xvfb, the browser otherwise reports a
light preferred color scheme and CreepJS classifies that as a `like headless`
signal. Rendering, fonts, storage, connection, media devices and speech remain native.
`hardwareConcurrency` and `deviceMemory` must remain native and coherent in every realm. CPU quotas
are reflected in the native runtime baseline. A finite cgroup memory limit remains fail-closed by
default because Chrome 150 reports host `navigator.deviceMemory` inside such a container and
provides no native global override. A managed launcher may continue only after opening the same
Chrome under the same display/cgroup, measuring `navigator.deviceMemory` on loopback and exporting
the matching native-memory attestation. The engine compares that value with the profile and passes
the same explicit environment to Chrome; do not set either attestation variable manually.

That attestation proves consistency, not host isolation. In the validated 4 GiB container, Chrome
reported `navigator.deviceMemory=32` and `navigator.hardwareConcurrency=16`. Both native values
are observable and potentially linkable host characteristics and must not be described as “no
leak”. Standard Docker did not provide a strong isolation boundary for them. Eliminating that
disclosure without detectable JavaScript wrappers requires a VM/microVM with the intended guest
memory and CPU topology (or a future native Chrome mechanism).

The repository also includes a Docker proof target:

```bash
docker compose run --rm --build javadriveless-proof
```

The checked-in Compose service applies the Chrome seccomp profile, drops all capabilities and adds
back only `SYS_CHROOT`. A plain `docker run` with Docker's default seccomp profile can deny the
namespace creation required by Chrome's sandbox and terminate Chrome with exit code `133`; do not
work around that condition with `--no-sandbox`.

The default `linux/amd64` image starts the canonical `docker` preset as visible Chrome under its provisioned
Xvfb display. `DockerRuntimeProof` reads live DOM/runtime values and writes
`/tmp/java-driverless-docker-proof.json`; it rejects `HeadlessChrome`, `navigator.webdriver=true`,
missing/mismatched locale, languages, timezone, hardware, device memory, screen/workarea/depth/DPR,
literal-IP WebRTC candidates, missing WebGL and SwiftShader under `minimal-cdp`. The proof page is
served from loopback HTTP so secure-context-only properties such as `deviceMemory` are observed.
It does not claim to measure proxy DNS egress without a controlled resolver. The site-specific
CreepJS command above remains the broader detector contract; the image smoke alone is not a
universal anti-detection claim.

The external fingerprint workflow supplies that missing controlled environment itself: an
ephemeral loopback-only recursive Unbound resolver, an HTTPS DoH bridge, an execution-scoped NSS
certificate, and a managed Chrome secure-DNS policy. Resolver IP/prefix, ISP aliases, and location
are established before site navigation from Akamai Whoami, RIPE RIS, and an independent GeoIP
quorum. The gate also checks the DoH query counter after Chrome exits, so a green BrowserLeaks DNS
contract requires both attributed site results and proven use of the controlled transport.

## Rule

Do not change global defaults just because one site fails. First compare the
site with `tools/compare_site_modes.py`, then create or select the smallest
site profile that removes the specific difference.
