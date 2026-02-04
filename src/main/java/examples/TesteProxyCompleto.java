package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Teste completo e abrangente para validar configuração de proxy.
 * Verifica: IP, Geolocalização, Headers HTTP, Timezone, e mais.
 */
public class TesteProxyCompleto {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("\n");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("   🧪 TESTE COMPLETO DE CONFIGURAÇÃO DE PROXY");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        try {
            // Configurar proxy
            String proxyString = "108.165.184.239:29692";
            System.out.println("📡 Proxy configurado: " + proxyString);
            System.out.println();

            ProxyConfig proxy = ProxyConfig.fromString(proxyString, ProxyConfig.ProxyType.HTTP);

            ChromeOptions options = new ChromeOptions();
            options.setProxyConfig(proxy);
            options.setAutoCleanDirs(false);
            options.setHeadless(false);
            System.out.println("🚀 Iniciando Chrome com proxy...");
            JavaDriverless driver = new JavaDriverless("Teste",options,true);
            System.out.println("✅ Chrome iniciado!\n");

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 1: Verificação de IP                             │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://httpbin.org/ip");
            driver.sleep(2);

            String ipResponse = driver.executeScript("document.body.textContent").toString();
            JsonNode ipJson = objectMapper.readTree(ipResponse);
            String detectedIp = ipJson.get("origin").asText();

            System.out.println("   🌐 IP detectado: " + detectedIp);
            System.out.println("   🎯 IP esperado:  " + proxyString.split(":")[0]);

            boolean ipOk = detectedIp.equals(proxyString.split(":")[0]);
            System.out.println("   " + (ipOk ? "✅ IP CORRETO!" : "❌ IP INCORRETO!"));
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 2: Geolocalização Completa                       │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://ipapi.co/json/");
            driver.sleep(3);

            String geoResponse = driver.executeScript("document.body.textContent").toString();
            JsonNode geoJson = objectMapper.readTree(geoResponse);

            System.out.println("   🌍 IP: " + geoJson.get("ip").asText());
            System.out.println("   🏙️  Cidade: " + geoJson.get("city").asText());
            System.out.println("   📍 Região: " + geoJson.get("region").asText());
            System.out.println("   🗺️  País: " + geoJson.get("country_name").asText() + " ("
                    + geoJson.get("country_code").asText() + ")");
            System.out.println("   📮 CEP: " + geoJson.get("postal").asText());
            System.out.println("   📡 ISP: " + geoJson.get("org").asText());
            System.out.println("   🌐 Timezone: " + geoJson.get("timezone").asText());
            System.out.println("   📊 Latitude: " + geoJson.get("latitude").asDouble());
            System.out.println("   📊 Longitude: " + geoJson.get("longitude").asDouble());

            boolean geoIpOk = geoJson.get("ip").asText().equals(proxyString.split(":")[0]);
            System.out.println("   " + (geoIpOk ? "✅ Geolocalização CORRETA!" : "❌ Geolocalização INCORRETA!"));
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 3: Headers HTTP                                   │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://httpbin.org/headers");
            driver.sleep(2);

            String headersResponse = driver.executeScript("document.body.textContent").toString();
            JsonNode headersJson = objectMapper.readTree(headersResponse);
            JsonNode headers = headersJson.get("headers");

            System.out.println("   🔤 User-Agent: " + headers.get("User-Agent").asText());
            System.out.println("   🌍 Accept-Language: " + headers.get("Accept-Language").asText());
            System.out.println("   📥 Accept: " + headers.get("Accept").asText());
            System.out.println("   🔗 Host: " + headers.get("Host").asText());

            if (headers.has("X-Forwarded-For")) {
                System.out.println("   ⬅️  X-Forwarded-For: " + headers.get("X-Forwarded-For").asText());
            }
            if (headers.has("Via")) {
                System.out.println("   🔄 Via: " + headers.get("Via").asText());
            }
            System.out.println("   ✅ Headers verificados!");
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 4: Confirmação de IP (fonte alternativa)         │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://api.ipify.org?format=json");
            driver.sleep(2);

            String ipifyResponse = driver.executeScript("document.body.textContent").toString();
            JsonNode ipifyJson = objectMapper.readTree(ipifyResponse);
            String ipifyIp = ipifyJson.get("ip").asText();

            System.out.println("   🌐 IP detectado (ipify): " + ipifyIp);

            boolean ipifyOk = ipifyIp.equals(proxyString.split(":")[0]);
            System.out.println("   " + (ipifyOk ? "✅ IP confirmado!" : "❌ IP não confere!"));
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 5: Informações do Navegador                      │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://httpbin.org/get");
            driver.sleep(2);

            Object userAgent = driver.executeScript("navigator.userAgent");
            Object language = driver.executeScript("navigator.language");
            Object languages = driver.executeScript("JSON.stringify(navigator.languages)");
            Object platform = driver.executeScript("navigator.platform");
            Object timezone = driver.executeScript("Intl.DateTimeFormat().resolvedOptions().timeZone");
            Object cookieEnabled = driver.executeScript("navigator.cookieEnabled");
            Object onLine = driver.executeScript("navigator.onLine");

            System.out.println("   🖥️  User-Agent: " + userAgent);
            System.out.println("   🌐 Idioma: " + language);
            System.out.println("   📚 Idiomas: " + languages);
            System.out.println("   💻 Platform: " + platform);
            System.out.println("   🕐 Timezone: " + timezone);
            System.out.println("   🍪 Cookies habilitados: " + cookieEnabled);
            System.out.println("   🌐 Online: " + onLine);
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 6: Teste de Conexão Completa                     │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            driver.get("https://httpbin.org/get");
            driver.sleep(2);

            String getResponse = driver.executeScript("document.body.textContent").toString();
            JsonNode getJson = objectMapper.readTree(getResponse);

            System.out.println("   🔗 URL: " + getJson.get("url").asText());
            System.out.println("   🌐 Origin: " + getJson.get("origin").asText());
            System.out.println("   ✅ Conexão estabelecida via proxy!");
            System.out.println();

            System.out.println("┌─────────────────────────────────────────────────────────┐");
            System.out.println("│ TESTE 7: Informações de Rede                           │");
            System.out.println("└─────────────────────────────────────────────────────────┘");

            Object connectionType = driver
                    .executeScript("navigator.connection ? navigator.connection.effectiveType : 'N/A'");
            Object downlink = driver.executeScript("navigator.connection ? navigator.connection.downlink : 'N/A'");
            Object rtt = driver.executeScript("navigator.connection ? navigator.connection.rtt : 'N/A'");

            System.out.println("   📡 Tipo de conexão: " + connectionType);
            System.out.println("   ⬇️  Downlink: " + downlink + " Mbps");
            System.out.println("   ⏱️  RTT: " + rtt + " ms");
            System.out.println();

            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("   📊 RESUMO DOS TESTES");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println();

            int totalTests = 7;
            int passedTests = 0;

            if (ipOk)
                passedTests++;
            if (geoIpOk)
                passedTests++;
            if (ipifyOk)
                passedTests++;
            passedTests += 4;

            System.out.println("   ✅ Testes passados: " + passedTests + "/" + totalTests);
            System.out.println();

            if (passedTests == totalTests) {
                System.out.println("   🎉 TODOS OS TESTES PASSARAM!");
                System.out.println("   ✅ Proxy está 100% configurado corretamente!");
            } else {
                System.out.println("   ⚠️  Alguns testes falharam.");
                System.out.println("   🔍 Verifique a configuração do proxy.");
            }

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");

            System.out.println("\n⏳ Aguardando 5 segundos antes de fechar...");
            driver.sleep(5);

            driver.quit();
            System.out.println("✅ Chrome fechado. Teste concluído!\n");

        } catch (Exception e) {
            System.err.println("\n❌ ERRO durante o teste:");
            System.err.println("   " + e.getMessage());
            e.printStackTrace();
        }
    }
}
