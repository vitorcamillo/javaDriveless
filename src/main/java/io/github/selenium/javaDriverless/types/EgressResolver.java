package io.github.selenium.javaDriverless.types;

/**
 * Resolve o {@link EgressInfo} (geo do IP de saída) a partir do proxy configurado, para tornar o
 * fingerprint coerente com o IP que o site enxerga (timezone, idioma, geolocalização e WebRTC).
 *
 * <p>A engine é AGNÓSTICA à fonte: o consumidor (ex.: phantomPass) implementa do jeito que preferir
 * — a partir de dado próprio do proxy, de uma base geo-IP offline, de um provider HTTP, etc. Para
 * o caso mais comum (o consumidor já conhece o egress) basta usar
 * {@code ChromeOptions.setEgressInfo(...)} em vez de um resolver.
 *
 * <p>Deve falhar de forma segura: retornar {@code null} quando não souber o egress — a engine então
 * mantém os defaults já coerentes por locale (timezone derivado, etc.), sem vazar o host real.
 */
@FunctionalInterface
public interface EgressResolver {

    /**
     * @param proxy proxy configurado da sessão (pode ser {@code null} quando não há proxy)
     * @return o egress resolvido, ou {@code null} se desconhecido
     * @throws Exception falhas de rede/IO são tratadas pela engine (mantém defaults)
     */
    EgressInfo resolve(ProxyConfig proxy) throws Exception;
}
