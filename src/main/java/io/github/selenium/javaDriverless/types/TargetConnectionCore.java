package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeRegistry;
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
import java.util.function.BiConsumer;
import java.util.Base64;

abstract class TargetConnectionCore implements CDPCommandExecutor {

    protected static final Logger logger = JavaDriverlessLogger.getLogger(Target.class);
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    // Mapeamento de teclas para eventos CDP
    protected static final Map<Character, KeyInfo> KEY_MAPPING = createKeyMapping();
    protected static final String SHIFT_KEY_NEEDED = "~!@#$%^&*()_+{}|:\"<>?";

    protected static class KeyInfo {
        String code;
        int keyCode;

        KeyInfo(String code, int keyCode) {
            this.code = code;
            this.keyCode = keyCode;
        }
    }

    // Campos de instância
    protected Target parentTarget;
    protected final Object context;
    protected Integer windowId;
    protected Pointer pointer;
    protected Boolean pageEnabled;
    protected Boolean domEnabled;
    protected final int maxWsSize;

    protected final Map<String, Object> globalThis = new ConcurrentHashMap<>();
    protected Object documentElem;
    protected Object alert;

    protected CDPSocket socket;
    protected String isolatedContextId;
    protected String execContextId;
    protected final Map<String, Target> targets = new ConcurrentHashMap<>();

    protected final boolean isRemote;
    protected final String host;
    protected final String id;
    protected String contextId;
    protected final String type;
    protected final float timeout;

    protected final boolean startSocket;
    protected final List<Runnable> onClosed = new ArrayList<>();

    // Limite de eventos acumulados por coletor de getCdpEventIter
    protected static final int MAX_EVENT_ITER_EVENTS = 4096;

    // Um coletor vivo por nome de evento (chamadas repetidas reusam a mesma lista/listener)
    protected final Map<String, List<JsonNode>> cdpEventIters = new ConcurrentHashMap<>();

    protected final Object driver;
    protected final ReentrantLock sendKeyLock = new ReentrantLock();
    // Cauda da digitação, guardada por sendKeyLock; serializa chamadas concorrentes de sendKeys.
    protected CompletableFuture<Void> sendKeyTail;

    /**
     * Cria um novo Target.
     *
     * @param host endereço do host (ex: "localhost:9222")
     * @param targetId ID do target
     * @param driver instância do driver
     * @param context contexto do navegador
     * @param isRemote se é conexão remota
     * @param timeout timeout em segundos
     * @param type tipo do target
     * @param startSocket se deve iniciar socket automaticamente
     * @param maxWsSize tamanho máximo de mensagem WebSocket
     */
    protected TargetConnectionCore(String host, String targetId, Object driver, Object context,
                 boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        this.host = host;
        this.id = targetId;
        this.driver = driver;
        this.context = context;
        this.isRemote = isRemote;
        this.timeout = timeout;
        this.type = type;
        this.startSocket = startSocket;
        this.maxWsSize = maxWsSize;
    }

    /**
     * Retorna o ID do target.
     *
     * @return ID do target
     */
    public String getId() {
        return id;
    }

    /**
     * Obtém se o target está attached (tem cliente conectado).
     *
     * @return CompletableFuture com true se attached
     */
    public CompletableFuture<Boolean> getAttached() {
        return getInfo().thenApply(info -> (Boolean) info.get("attached"));
    }

    /**
     * Obtém o ID do opener (target que abriu este target).
     *
     * @return CompletableFuture com o opener ID
     */
    public CompletableFuture<String> getOpenerId() {
        return getInfo().thenApply(info -> (String) info.get("openerId"));
    }

    /**
     * Obtém o frame ID do opener.
     *
     * @return CompletableFuture com o opener frame ID
     */
    public CompletableFuture<String> getOpenerFrameId() {
        return getInfo().thenApply(info -> (String) info.get("openerFrameId"));
    }

    /**
     * Obtém se o target pode acessar o opener.
     *
     * @return CompletableFuture com true se pode acessar
     */
    public CompletableFuture<Boolean> getCanAccessOpener() {
        return getInfo().thenApply(info -> (Boolean) info.get("canAccessOpener"));
    }

    /**
     * Obtém o subtipo do target (ex: "portal", "prerender").
     *
     * @return CompletableFuture com o subtipo
     */
    public CompletableFuture<String> getSubtype() {
        return getInfo().thenApply(info -> (String) info.get("subtype"));
    }

    /**
     * Retorna o socket CDP.
     *
     * @return socket CDP
     */
    public CDPSocket getSocket() {
        return socket;
    }

    /**
     * Retorna o ponteiro para interações.
     *
     * @return ponteiro
     */
    public Pointer getPointer() {
        if (pointer == null) {
            pointer = new Pointer((Target) this);
        }
        return pointer;
    }

    /**
     * Inicializa o target e a conexão WebSocket.
     *
     * @return CompletableFuture com o target inicializado
     */
    public CompletableFuture<Target> init() {
        if (socket != null) {
            return CompletableFuture.completedFuture((Target) this);
        }

        return CompletableFuture.supplyAsync(() -> {
            if (CdpPipeRegistry.isPipeHost(host)) {
                socket = CdpPipeRegistry.lookup(host)
                        .orElseThrow(() -> new IllegalStateException("Canal CDP pipe indisponível"))
                        .attachToTarget(id, timeout).join();
            } else {
                String wsUrl = String.format("ws://%s/devtools/page/%s", host, id);
                socket = new CDPSocket(wsUrl, timeout, maxWsSize);
                socket.connectAsync().join();
            }

            pointer = new Pointer((Target) this);

            // Configurar listeners de eventos
            addCdpListener("Page.javascriptDialogOpening", params -> {
                // TODO: Criar objeto Alert
                this.alert = params;
            }).join();

            addCdpListener("Page.javascriptDialogClosed", params -> {
                this.alert = null;
            }).join();

            addCdpListener("Page.loadEventFired", params -> onLoaded()).join();
            addCdpListener("Page.windowOpen", params -> onLoaded()).join();

            // Adicionar callbacks de fechamento
            socket.getOnClosed().addAll(onClosed);

            return (Target) this;
        });
    }

    /**
     * Callback executado quando a página é carregada.
     * Sincronizado para evitar race conditions com operações assíncronas em andamento.
     */
    protected synchronized void onLoaded() {
        globalThis.clear();
        documentElem = null;
        isolatedContextId = null;
        execContextId = null;
    }

    public abstract CompletableFuture<Map<String, Object>> getInfo();

    public abstract CompletableFuture<String> getTitleViaCDP();

    public abstract CompletableFuture<String> getUrlViaCDP();

    /**
     * Fecha o target.
     *
     * @return CompletableFuture que completa quando o target é fechado
     */
    public CompletableFuture<Void> close() {
        return close(2.0f);
    }

    /**
     * Fecha o target com timeout.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture que completa quando o target é fechado
     */
    public CompletableFuture<Void> close(float timeout) {
        if (socket != null && socket.isConnected()) {
            return executeCdpCmd("Target.closeTarget", Map.of("targetId", id), timeout)
                .thenCompose(v -> disconnect())
                .exceptionally(e -> {
                    // Ignorar erros de fechamento
                    return null;
                });
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Fecha somente a conexão CDP, preservando a página. Use ao anexar a um Chrome
     * cuja vida útil pertence ao chamador.
     *
     * @return futuro concluído quando o websocket tiver sido fechado
     */
    public CompletableFuture<Void> disconnect() {
        if (socket == null || !socket.isConnected()) {
            return CompletableFuture.completedFuture(null);
        }
        return socket.closeAsync().exceptionally(e -> null);
    }

    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento
     * @param timeout timeout em segundos (null para infinito)
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        if (socket == null) {
            return init().thenCompose(t -> socket.waitFor(event, timeout));
        }
        return socket.waitFor(event, timeout);
    }

    /**
     * Adiciona um listener para um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser chamada
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        if (socket == null) {
            return init().thenAccept(t -> socket.addListener(event, callback));
        }
        socket.addListener(event, callback);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> addCdpSessionListener(
            String event, BiConsumer<String, JsonNode> callback) {
        if (socket == null) {
            return init().thenAccept(t -> socket.addSessionListener(event, callback));
        }
        socket.addSessionListener(event, callback);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Remove um listener de um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser removida
     * @return CompletableFuture que completa quando o listener é removido
     */
    public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        if (socket == null) {
            return CompletableFuture.completedFuture(null);
        }
        socket.removeListener(event, callback);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Obtém um iterador de eventos CDP.
     * Retorna a lista viva que acumula eventos deste tipo (preenchida conforme chegam).
     * <p>
     * Há um único coletor por nome de evento nesta instância: chamadas repetidas
     * para o mesmo evento devolvem a MESMA lista e registram UM único listener,
     * sem acumular listeners (sem leak). A lista é sincronizada e limitada a
     * {@value #MAX_EVENT_ITER_EVENTS} itens — ao exceder, o mais antigo é descartado.
     * </p>
     *
     * @param event nome do evento para iterar
     * @return CompletableFuture com lista de eventos acumulados
     */
    public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        if (socket == null) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        List<JsonNode> events = cdpEventIters.computeIfAbsent(event, name -> {
            List<JsonNode> collector = Collections.synchronizedList(new ArrayList<>());
            // Listener único que alimenta a lista viva com teto de tamanho
            socket.addListener(name, params -> {
                synchronized (collector) {
                    collector.add(params);
                    while (collector.size() > MAX_EVENT_ITER_EVENTS) {
                        collector.remove(0);
                    }
                }
            });
            return collector;
        });

        // Retornar lista viva (eventos serão adicionados conforme chegam)
        return CompletableFuture.completedFuture(events);
    }

    /**
     * Executa um comando CDP.
     *
     * @param cmd nome do comando
     * @param cmdArgs argumentos do comando
     * @param timeout timeout em segundos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout) {
        if (socket == null) {
            return init().thenCompose(t -> socket.exec(cmd, cmdArgs, timeout));
        }
        return socket.exec(cmd, cmdArgs, timeout);
    }

    /**
     * Executa um comando CDP roteado para uma sessão anexada (flatten mode),
     * por exemplo uma sessão de worker/service worker obtida via
     * {@code Target.attachedToTarget}.
     *
     * @param cmd nome do método CDP
     * @param cmdArgs parâmetros do comando
     * @param timeout timeout em segundos (null para o padrão)
     * @param sessionId sessionId da sessão anexada; null para a sessão atual
     * @return CompletableFuture com o resultado do comando
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout, String sessionId) {
        if (socket == null) {
            return init().thenCompose(t -> socket.exec(cmd, cmdArgs, timeout, sessionId));
        }
        return socket.exec(cmd, cmdArgs, timeout, sessionId);
    }

    /**
     * Aguarda em segundos.
     *
     * @param seconds segundos para aguardar
     * @return CompletableFuture que completa após o tempo
     */
    public CompletableFuture<Void> sleep(double seconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep((long) (seconds * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public String toString() {
        return String.format("<%s.%s (target_id=\"%s\", host=\"%s\")>",
            getClass().getPackage().getName(),
            getClass().getSimpleName(),
            id,
            host
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Target)) return false;
        Target other = (Target) obj;
        return Objects.equals(socket, other.socket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(socket);
    }

    /**
     * Cria o mapeamento de teclas.
     */
    private static Map<Character, KeyInfo> createKeyMapping() {
        Map<Character, KeyInfo> map = new HashMap<>();

        // Letras minúsculas
        for (char c = 'a'; c <= 'z'; c++) {
            map.put(c, new KeyInfo("Key" + Character.toUpperCase(c), 65 + (c - 'a')));
        }

        // Letras maiúsculas
        for (char c = 'A'; c <= 'Z'; c++) {
            map.put(c, new KeyInfo("Key" + c, 65 + (c - 'A')));
        }

        // Números
        for (char c = '0'; c <= '9'; c++) {
            map.put(c, new KeyInfo("Digit" + c, 48 + (c - '0')));
        }

        // Caracteres especiais
        map.put(' ', new KeyInfo("Space", 32));
        map.put('\r', new KeyInfo("Enter", 13));
        map.put('\n', new KeyInfo("Enter", 13));
        map.put('.', new KeyInfo("Period", 190));
        map.put(',', new KeyInfo("Comma", 188));
        map.put('-', new KeyInfo("Minus", 189));
        map.put('=', new KeyInfo("Equal", 187));
        map.put('[', new KeyInfo("BracketLeft", 219));
        map.put(']', new KeyInfo("BracketRight", 221));
        map.put('\\', new KeyInfo("Backslash", 220));
        map.put(';', new KeyInfo("Semicolon", 186));
        map.put('\'', new KeyInfo("Quote", 222));
        map.put('/', new KeyInfo("Slash", 191));
        map.put('`', new KeyInfo("Backquote", 192));

        return Collections.unmodifiableMap(map);
    }

}
