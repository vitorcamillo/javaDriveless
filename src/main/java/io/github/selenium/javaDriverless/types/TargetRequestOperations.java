package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.TypesExceptions.NoSuchIframe;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Base64;

abstract class TargetRequestOperations extends TargetStateOperations {

    protected TargetRequestOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Executa uma requisição fetch JavaScript dentro do target.
     *
     * @param url URL para fazer a requisição
     * @param method método HTTP (GET, POST, etc)
     * @param headers headers da requisição
     * @param body corpo da requisição
     * @param timeout timeout em segundos
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> fetch(
        String url,
        String method,
        Map<String, String> headers,
        Object body,
        float timeout
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> options = new HashMap<>();

                if (method != null) {
                    options.put("method", method);
                }
                if (headers != null) {
                    options.put("headers", headers);
                }
                if (body != null) {
                    byte[] bodyBytes;
                    if (body instanceof Map) {
                        String json = objectMapper.writeValueAsString(body);
                        bodyBytes = json.getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof String) {
                        bodyBytes = ((String) body).getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof byte[]) {
                        bodyBytes = (byte[]) body;
                    } else {
                        throw new IllegalArgumentException("Body deve ser Map, String ou byte[]");
                    }
                    options.put("body", Base64.getEncoder().encodeToString(bodyBytes));
                }

                String script = """
                    async function bufferTobase64(array) {
                      return new Promise((resolve) => {
                        const blob = new Blob([array]);
                        const reader = new FileReader();

                        reader.onload = (event) => {
                          const dataUrl = event.target.result;
                          const [_, base64] = dataUrl.split(',');

                          resolve(base64);
                        };

                        reader.readAsDataURL(blob);
                      });
                    };
                    async function base64ToBuffer(base64) {
                      const dataUrl = "data:application/octet-binary;base64," + base64;

                      const res = await fetch(dataUrl)
                      return await res.arrayBuffer()
                    };

                    function headers2dict(headers){
                        var my_dict = {};
                        for (var pair of headers.entries()) {
                                my_dict[pair[0]] = pair[1]};
                        return my_dict}

                    async function get(url, options){
                        if(options.body){options.body = await base64ToBuffer(options.body)}
                        var response = await fetch(url, options);
                        var buffer = await response.arrayBuffer()
                        var b64 = await bufferTobase64(buffer)
                        var res = {
                                "b64":b64,
                                "headers":headers2dict(response.headers),
                                "ok":response.ok,
                                "status_code":response.status,
                                "redirected":response.redirected,
                                "status_text":response.statusText,
                                "type":response.type,
                                "url":response.url
                                };
                        return res;
                    }
                    return await get(arguments[0], arguments[1])
                """;

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) evalAsync(
                    script,
                    timeout,
                    true,
                    url,
                    options
                ).join();

                // Decodificar body de base64 para bytes
                String b64 = (String) result.get("b64");
                byte[] bodyResult = Base64.getDecoder().decode(b64);
                result.put("body", bodyResult);
                result.remove("b64");

                return result;
            } catch (Exception e) {
                throw new RuntimeException("Erro no fetch: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Executa uma requisição XMLHttpRequest JavaScript dentro do target.
     *
     * @param url URL para fazer a requisição
     * @param method método HTTP (GET, POST, etc)
     * @param headers headers da requisição
     * @param body corpo da requisição
     * @param timeout timeout em segundos
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> xhr(
        String url,
        String method,
        Map<String, String> headers,
        Object body,
        float timeout
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String bodyB64 = null;
                if (body != null) {
                    byte[] bodyBytes;
                    if (body instanceof Map) {
                        String json = objectMapper.writeValueAsString(body);
                        bodyBytes = json.getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof String) {
                        bodyBytes = ((String) body).getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof byte[]) {
                        bodyBytes = (byte[]) body;
                    } else {
                        throw new IllegalArgumentException("Body deve ser Map, String ou byte[]");
                    }
                    bodyB64 = Base64.getEncoder().encodeToString(bodyBytes);
                }

                String script = """
                    async function bufferTobase64(array) {
                      return new Promise((resolve) => {
                        const blob = new Blob([array]);
                        const reader = new FileReader();

                        reader.onload = (event) => {
                          const dataUrl = event.target.result;
                          const [_, base64] = dataUrl.split(',');

                          resolve(base64);
                        };

                        reader.readAsDataURL(blob);
                      });
                    };
                    async function base64ToBuffer(base64) {
                      const dataUrl = "data:application/octet-binary;base64," + base64;

                      const res = await fetch(dataUrl)
                      return await res.arrayBuffer()
                    };

                    function headers2dict(headers){
                        var my_dict = {};
                        var pairs = headers.trim().split('\\r\\n')
                        for(let pair of pairs){
                            if(pair){
                                var idx = pair.indexOf(":")
                                var key = pair.slice(0, idx)
                                var value = pair.slice(idx+1).trim()
                                my_dict[key] = value
                            }
                        };
                        return my_dict}

                    async function get(url, method, headers, body){
                        return new Promise(async(resolve, reject) => {
                            const xhr = new XMLHttpRequest();
                            xhr.open(method, url, true);
                            if(headers){
                                for (const [header, value] of Object.entries(headers)) {
                                    xhr.setRequestHeader(header, value)
                                }
                            }
                            xhr.responseType = 'arraybuffer';

                            xhr.onload = async() => {
                                var b64 = await bufferTobase64(xhr.response)
                                resolve({
                                    b64: b64,
                                    headers: headers2dict(xhr.getAllResponseHeaders()),
                                    status_code: xhr.status,
                                    status_text: xhr.statusText
                                });
                            };
                            xhr.onerror = () => {
                                reject({
                                    status: xhr.status,
                                    statusText: xhr.statusText
                                });
                            };
                            if(body){
                                body = await base64ToBuffer(body)
                                xhr.send(body);
                            }
                            else{
                                xhr.send();
                            }
                        });
                    }
                    return await get(arguments[0], arguments[1], arguments[2], arguments[3])
                """;

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) evalAsync(
                    script,
                    timeout,
                    true,
                    url,
                    method,
                    headers,
                    bodyB64
                ).join();

                // Decodificar body de base64 para bytes
                String b64 = (String) result.get("b64");
                byte[] bodyResult = Base64.getDecoder().decode(b64);
                result.put("body", bodyResult);
                result.remove("b64");

                return result;
            } catch (Exception e) {
                throw new RuntimeException("Erro no xhr: " + e.getMessage(), e);
            }
        });
    }

}
