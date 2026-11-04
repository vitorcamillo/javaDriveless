package io.github.selenium.javaDriverless.types;

/**
 * Dados do IP de egress (proxy/rede de saída) usados para tornar o fingerprint
 * coerente com o IP que o site enxerga.
 *
 * <p>Preenchido pelo consumidor (ex.: a partir de um proxy-check ou de um lookup
 * de IP-geo) e aplicado via {@link EgressCoherence#apply}. Campos ausentes são
 * tratados com segurança (sem inventar valor).
 */
public class EgressInfo {
    public String timezone = "";     // IANA, ex: "America/Manaus"
    public String countryCode = "";  // ISO-3166-1 alpha-2, ex: "BR"
    public Double latitude;
    public Double longitude;
    public Double accuracy;          // metros; null → 50 ao aplicar
    public String publicIp = "";
    public String city = "";
    public boolean mobile = false;   // egress de operadora móvel (4G/5G)

    public EgressInfo() {
    }

    /** @return copia independente dos dados de egress */
    public EgressInfo copy() {
        EgressInfo copy = new EgressInfo();
        copy.timezone = timezone;
        copy.countryCode = countryCode;
        copy.latitude = latitude;
        copy.longitude = longitude;
        copy.accuracy = accuracy;
        copy.publicIp = publicIp;
        copy.city = city;
        copy.mobile = mobile;
        return copy;
    }
}
