package io.github.selenium.javaDriverless.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.selenium.javaDriverless.types.Target;

class AuditoriaScriptsRegressaoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void addCookieConverteExpiryParaExpiresSemAlterarMapaOriginal() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Storage.setCookies"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        Map<String, Object> cookieDict = new HashMap<>();
        cookieDict.put("name", "sessao");
        cookieDict.put("value", "123");
        cookieDict.put("expiry", 1700000000L);

        DriverUtils.addCookie(target, cookieDict, null).join();

        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Storage.setCookies"), args.capture(), isNull());
        Map<String, Object> cookieEnviado =
                ((List<Map<String, Object>>) args.getValue().get("cookies")).get(0);
        assertThat(cookieEnviado)
                .containsEntry("expires", 1700000000L)
                .doesNotContainKey("expiry");
        assertThat(cookieDict)
                .containsKey("expiry")
                .doesNotContainKey("expires");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addCookieComExpiresExplitoMantemValorDeExpires() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Storage.setCookies"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        Map<String, Object> cookieDict = new HashMap<>();
        cookieDict.put("name", "sessao");
        cookieDict.put("value", "123");
        cookieDict.put("expires", 1111111111L);
        cookieDict.put("expiry", 2222222222L);

        DriverUtils.addCookie(target, cookieDict, null).join();

        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Storage.setCookies"), args.capture(), isNull());
        Map<String, Object> cookieEnviado =
                ((List<Map<String, Object>>) args.getValue().get("cookies")).get(0);
        assertThat(cookieEnviado)
                .containsEntry("expires", 1111111111L)
                .doesNotContainKey("expiry");
    }

    @Test
    void getBoundsComCoordenadasNegativasRetornaLimitesCorretos() {
        double[][] vertices = {{-50, -50}, {-10, -10}};

        double[] bounds = Geometry.getBounds(vertices);

        assertThat(bounds).containsExactly(-50.0, -50.0, -10.0, -10.0);
    }

    @Test
    void caminhoCurtoGeraNoMinimoDoisPontosSemOrigemZero() {
        List<int[]> path = Geometry.generatePath(
                new double[]{100, 100}, new double[]{100.05, 100}, 10, 0.5);

        assertThat(path).hasSizeGreaterThanOrEqualTo(2);
        assertThat(path).noneMatch(ponto -> ponto[0] == 0 && ponto[1] == 0);
    }

    @Test
    void caminhoDeDezPixelsGeraCemPontosComInicioEFimExatos() {
        // distance = 10.0 => numPoints = max(2, (int)(10 * 10)) = 100
        List<int[]> path = Geometry.generatePath(
                new double[]{0, 0}, new double[]{10, 0}, 10, 0.5);

        assertThat(path).hasSize(100);
        assertThat(path.get(0)).containsExactly(0, 0);
        assertThat(path.get(path.size() - 1)).containsExactly(10, 0);
    }

    @Test
    void segundaContinuacaoDaMesmaRequisicaoLancaRequestDoneException() throws Exception {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        JsonNode request = mapper.readTree("""
                {
                  "requestId":"requisicao-duplicada",
                  "request":{
                    "url":"https://example.invalid/",
                    "method":"GET"
                  }
                }
                """);
        NetworkInterceptor.InterceptedRequest requisicao =
                new NetworkInterceptor.InterceptedRequest("requisicao-duplicada", "frame-1", request.get("request"), target);

        requisicao.continueRequest().join();
        assertThatThrownBy(requisicao::continueRequest)
                .isInstanceOf(NetworkInterceptor.RequestDoneException.class);
        assertThat(requisicao.isDone()).isTrue();

        verify(target, times(1)).executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull());
    }

    @Test
    void scriptDeSurfaceLimpaFramesAntigosDeTrackedFrames() {
        assertThat(SeleniumProxyScreenSurfaceScripts.SCRIPT)
                .contains("trackedFrames.delete(old)");
    }
}
