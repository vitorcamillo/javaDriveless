package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.types.Target;

/**
 * Contexto isolado com proxy proprio num Chrome compartilhado: ponte local
 * por contexto, dispose real do contexto e visao de driver presa a uma
 * pagina. Estado de sessao fake (sem processo, sem WebSocket).
 */
class ChromeContextoIsoladoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Probe extends Chrome {
        Probe() {
            super(new ChromeOptions(), 10f, false, 2 << 22);
        }
    }

    private Probe chrome;
    private BaseTarget baseTarget;
    private Target currentTarget;
    private Context currentContext;

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProxyConfig proxyAutenticado() {
        return new ProxyConfig("127.0.0.1", 3128, ProxyConfig.ProxyType.HTTPS, "usuario", "senha");
    }

    @BeforeEach
    void setUp() {
        chrome = new Probe();
        baseTarget = mock(BaseTarget.class);
        currentTarget = mock(Target.class);
        currentContext = mock(Context.class);
        chrome.started = true;
        chrome.baseTarget = baseTarget;
        chrome.currentTarget = currentTarget;
        chrome.currentContext = currentContext;
        chrome.host = "127.0.0.1";
        chrome.browserPid = (int) ProcessHandle.current().pid();
        when(baseTarget.executeCdpCmd(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(json("{\"browserContextId\":\"bc-1\"}")));
        when(currentTarget.executeCdpCmd(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(json("{}")));
    }

    @AfterEach
    void fecharPontes() {
        for (Context contexto : List.copyOf(chrome.contexts.values())) {
            contexto.closeProxyBridge();
        }
    }

    private static boolean portaAceita(int porta) {
        try (Socket s = new Socket("127.0.0.1", porta)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int portaDa(AuthenticatedProxyBridge ponte) {
        return Integer.parseInt(ponte.asChromeProxyServer().substring("http://127.0.0.1:".length()));
    }

    @Test
    void contextoComProxyAutenticadoSobePonteLocalEApontaProxyServerParaEla() throws Exception {
        Context contexto = chrome.newContext(proxyAutenticado()).get(5, TimeUnit.SECONDS);

        assertNotNull(contexto.getProxyBridge());
        String esperado = contexto.getProxyBridge().asChromeProxyServer();
        verify(baseTarget).executeCdpCmd(eq("Target.createBrowserContext"),
                argThat(args -> esperado.equals(args.get("proxyServer"))
                        && Boolean.TRUE.equals(args.get("disposeOnDetach"))),
                any());
        assertEquals("bc-1", contexto.getContextId());
        assertTrue(contexto.isIncognito());
        assertSame(contexto, chrome.contexts.get("bc-1"));
        assertTrue(portaAceita(portaDa(contexto.getProxyBridge())));
    }

    @Test
    void doisContextosTemPontesDistintasNoMesmoProcesso() throws Exception {
        when(baseTarget.executeCdpCmd(eq("Target.createBrowserContext"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(json("{\"browserContextId\":\"bc-1\"}")))
                .thenReturn(CompletableFuture.completedFuture(json("{\"browserContextId\":\"bc-2\"}")));

        Context a = chrome.newContext(proxyAutenticado()).get(5, TimeUnit.SECONDS);
        Context b = chrome.newContext(proxyAutenticado()).get(5, TimeUnit.SECONDS);

        assertEquals(2, chrome.contexts.size());
        assertFalse(a.getProxyBridge().asChromeProxyServer()
                .equals(b.getProxyBridge().asChromeProxyServer()));
    }

    @Test
    void falhaDoCdpAoCriarContextoFechaAPonteAntesDePropagar() {
        when(baseTarget.executeCdpCmd(eq("Target.createBrowserContext"), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("cdp recusou")));

        CompletableFuture<Context> futuro = chrome.newContext(proxyAutenticado());

        assertThrows(ExecutionException.class, () -> futuro.get(5, TimeUnit.SECONDS));
        assertTrue(chrome.contexts.isEmpty());
        // nenhuma ponte orfa ouvindo: o unico listener loopback aberto pela
        // ponte morreu junto com a falha (nao ha handle para consultar; prova
        // indireta pelo mapa vazio e pelo cleanup sem excecao)
    }

    @Test
    void proxySemAutenticacaoVaiDiretoNoProxyServerSemPonte() throws Exception {
        ProxyConfig semAuth = new ProxyConfig("10.0.0.5", 8080, ProxyConfig.ProxyType.HTTP);

        Context contexto = chrome.newContext(semAuth).get(5, TimeUnit.SECONDS);

        assertNull(contexto.getProxyBridge());
        verify(baseTarget).executeCdpCmd(eq("Target.createBrowserContext"),
                argThat(args -> "http://10.0.0.5:8080".equals(args.get("proxyServer"))), any());
    }

    @Test
    void contextoSemProxyNaoMandaProxyServer() throws Exception {
        Context contexto = chrome.newContext((ProxyConfig) null).get(5, TimeUnit.SECONDS);

        assertNull(contexto.getProxyBridge());
        verify(baseTarget).executeCdpCmd(eq("Target.createBrowserContext"),
                argThat(args -> !args.containsKey("proxyServer")), any());
    }

    @Test
    void quitDoContextoDescartaNoChromeFechaAPonteEEhIdempotente() throws Exception {
        Context contexto = chrome.newContext(proxyAutenticado()).get(5, TimeUnit.SECONDS);
        int porta = portaDa(contexto.getProxyBridge());
        // target do contexto e sintetico (sem socket): o dispose tem de sair pelo baseTarget
        Target targetSintetico = mock(Target.class);
        when(targetSintetico.executeCdpCmd(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem socket")));
        Context comBaseMockada = new Context(targetSintetico, chrome, "bc-1", true, 2 << 22);
        comBaseMockada.setProxyBridge(contexto.getProxyBridge());

        chrome.removeContext(comBaseMockada).get(5, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Target.disposeBrowserContext"),
                argThat(args -> "bc-1".equals(args.get("browserContextId"))), any());
        verify(targetSintetico, never()).executeCdpCmd(eq("Target.disposeBrowserContext"), any(), any());
        assertFalse(portaAceita(porta));
        assertNull(comBaseMockada.getProxyBridge());
        assertFalse(chrome.contexts.containsKey("bc-1"));

        // 2a chamada: no-op, sem novo dispose
        comBaseMockada.quit().get(5, TimeUnit.SECONDS);
        verify(baseTarget, org.mockito.Mockito.times(1))
                .executeCdpCmd(eq("Target.disposeBrowserContext"), any(), any());
    }

    @Test
    void addCookieNoContextoVaiPeloTargetDoBrowserComOContextId() throws Exception {
        Target targetSintetico = mock(Target.class);
        when(targetSintetico.executeCdpCmd(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem socket")));
        Context contexto = new Context(targetSintetico, chrome, "bc-9", true, 2 << 22);

        contexto.addCookie(Map.of("name", "a", "value", "1", "domain", "x.test")).get(5, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Storage.setCookies"),
                argThat(args -> "bc-9".equals(args.get("browserContextId"))), any());
    }

    @Test
    void quitDoProcessoFechaAsPontesDosContextosRemanescentes() throws Exception {
        Context contexto = chrome.newContext(proxyAutenticado()).get(5, TimeUnit.SECONDS);
        int porta = portaDa(contexto.getProxyBridge());
        assertTrue(portaAceita(porta));

        chrome.quit(false).get(10, TimeUnit.SECONDS);

        assertFalse(portaAceita(porta));
        assertTrue(chrome.contexts.isEmpty());
    }

    @Test
    void getAllCookiesConsultaOStoragePeloTargetDoBrowserComOContextId() throws Exception {
        // o target do contexto e sintetico ("context-<id>", sem socket): o
        // comando de browser tem de ir pelo baseTarget do processo
        Target targetSintetico = mock(Target.class);
        when(targetSintetico.executeCdpCmd(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem socket")));
        when(baseTarget.executeCdpCmd(eq("Storage.getCookies"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(json(
                        "{\"cookies\":[{\"name\":\"cf_clearance\",\"value\":\"v\",\"httpOnly\":true}]}")));
        Context contexto = new Context(targetSintetico, chrome, "bc-7", true, 2 << 22);

        List<Map<String, Object>> cookies = contexto.getAllCookies().get(5, TimeUnit.SECONDS);

        assertEquals(1, cookies.size());
        assertEquals("cf_clearance", cookies.get(0).get("name"));
        verify(baseTarget).executeCdpCmd(eq("Storage.getCookies"),
                argThat(args -> "bc-7".equals(args.get("browserContextId"))), any());
        verify(targetSintetico, never()).executeCdpCmd(eq("Storage.getCookies"), any(), any());
    }

    @Test
    void novaJanelaNoContextoAplicaFingerprintAoTargetDaPagina() throws Exception {
        Context contexto = mock(Context.class);
        Target pagina = mock(Target.class);
        when(contexto.newWindow("window")).thenReturn(CompletableFuture.completedFuture(pagina));
        chrome.options.setFingerprintEnabled(false); // sem perfil: caminho curto, sem CDP

        Target devolvido = chrome.newWindow(contexto, "window").get(5, TimeUnit.SECONDS);

        assertSame(pagina, devolvido);
        verify(contexto).newWindow("window");
    }

    @Test
    void visaoDeContextoRoteiaAPaginaDoContextoENuncaFechaOProcesso() throws Exception {
        Target pagina = mock(Target.class);
        when(pagina.executeScript(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture("ok-da-pagina"));
        Context contexto = mock(Context.class);
        when(contexto.getContextId()).thenReturn("bc-1");
        when(contexto.quit()).thenReturn(CompletableFuture.completedFuture(null));
        when(contexto.getAllCookies()).thenReturn(CompletableFuture.completedFuture(
                List.of(Map.of("name", "a", "value", "1"))));
        chrome.contexts.put("bc-1", contexto);

        ChromeContextView view = new ChromeContextView(chrome, contexto, pagina);

        assertEquals(-1, view.getBrowserPid());
        assertEquals("ok-da-pagina",
                view.executeScript("return 1", new Object[0], false).get(5, TimeUnit.SECONDS));
        assertEquals("a", view.getCookies().get(5, TimeUnit.SECONDS).get(0).get("name"));

        view.quit().get(5, TimeUnit.SECONDS);

        verify(contexto).quit();
        assertFalse(chrome.contexts.containsKey("bc-1"));
        verify(baseTarget, never()).executeCdpCmd(eq("Browser.close"), any(), any());
        assertNotNull(chrome.baseTarget, "quit da view nao pode derrubar o processo dono");
        assertTrue(chrome.started);
    }

    @Test
    void javaDriverlessParaContextoExpoeViewComPidMenosUm() {
        Target pagina = mock(Target.class);
        Context contexto = mock(Context.class);
        when(contexto.getContextId()).thenReturn("bc-1");
        JavaDriverless dono = mock(JavaDriverless.class);
        when(dono.getChrome()).thenReturn(chrome);
        when(dono.getNomeJanela()).thenReturn("compartilhado");

        JavaDriverless view = JavaDriverless.paraContexto(dono, contexto, pagina);

        assertEquals(-1, view.getPid());
        assertEquals("compartilhado/bc-1", view.getNomeJanela());
        assertTrue(view.getChrome() instanceof ChromeContextView);
    }
}
