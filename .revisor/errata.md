# Errata de mensagens de commit

- 3d4cd97: --geo em formato diferente de "latitude,longitude" passa a lançar IllegalArgumentException em FingerprintMappingLocationProfile.apply — não declarado na mensagem
- 3d4cd97: menções negadas ("no "/"not " antes do termo) deixam de reprovar sinais proibidos em FingerprintMappingExpectations.matchesForbiddenSignal — não declarado na mensagem
- 3d4cd97: falha de leitura do catálogo JSON descarta os sites parciais já coletados (sites.clear()) e cai no fallback em FingerprintMappingCatalog.loadSites — não declarado na mensagem
- 3d4cd97: Runtime.evaluate com exceptionDetails passa a registrar "Runtime.evaluate failed" no stdout em PlainChromeCdpSupport.eval — não declarado na mensagem
- 3d4cd97: atingidos 64 iframes rastreados, iframes desconectados saem do rastreio (evição em SeleniumProxyScreenSurfaceScripts) — não declarado na mensagem
- 3d4cd97: getCdpEventIter sem socket passa a abrir a conexão via init() em vez de devolver lista vazia em TargetConnectionCore — não declarado na mensagem
