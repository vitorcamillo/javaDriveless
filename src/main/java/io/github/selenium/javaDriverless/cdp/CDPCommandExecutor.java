package io.github.selenium.javaDriverless.cdp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface para componentes capazes de executar comandos CDP.
 * <p>
 * Substitui o uso de reflexão em classes como {@code Pointer} e {@code Context},
 * provendo type-safety e performance ao eliminar {@code Method.invoke()}.
 * </p>
 */
public interface CDPCommandExecutor {

    /**
     * Executa um comando CDP e retorna o resultado.
     *
     * @param cmd     nome do comando CDP (ex: "Page.navigate")
     * @param cmdArgs argumentos do comando
     * @param timeout timeout em segundos (null para usar o padrão)
     * @return CompletableFuture com o resultado do comando
     */
    CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout);
}
