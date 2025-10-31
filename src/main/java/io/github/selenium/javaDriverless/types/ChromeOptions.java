// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Modificado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless
// Todas as modificações são licenciadas sob a licença fornecida em LICENSE.md

package io.github.selenium.javaDriverless.types;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import io.github.selenium.javaDriverless.scripts.Prefs;
import io.github.selenium.javaDriverless.utils.Utils;

/**
 * Classe webdriver.ChromeOptions
 * <p>
 * <strong>Aviso:</strong> as opções não devem ser reutilizadas
 * </p>
 */
public class ChromeOptions {
    
    /**
     * Define se a extensão Chrome deve ser adicionada por padrão.
     * <p>
     * <strong>Nota:</strong> Este campo não é mais usado para autenticação de proxy.
     * Proxies com autenticação não são suportados.
     * </p>
     */
    @Deprecated
    public boolean useExtension = false;
    
    private String singleProxy;
    private String proxy;
    private ProxyConfig proxyConfig;
    private Map<String, Object> mobileOptions;
    private String binaryLocation;
    private Map<String, String> env;
    private List<String> extensionPaths;
    private List<String> extensions;
    private Map<String, Object> experimentalOptions;
    private String debuggerAddress;
    private String userDataDir;
    private String downloadsDir;
    private List<String> arguments;
    private Map<String, Object> prefs;
    private boolean ignoreLocalProxy;
    private boolean autoCleanDirs;
    private boolean headless;
    private String startupUrl;
    private boolean isRemote;
    
    /**
     * Construtor padrão que inicializa as opções com valores padrão.
     */
    public ChromeOptions() {
        this.singleProxy = null;
        this.proxy = null;
        this.proxyConfig = null;
        this.mobileOptions = null;
        this.binaryLocation = null;
        this.env = new HashMap<>(System.getenv());
        this.extensionPaths = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.experimentalOptions = new HashMap<>();
        this.debuggerAddress = null;
        this.userDataDir = null;
        this.downloadsDir = null;
        this.arguments = new ArrayList<>();
        this.prefs = new HashMap<>();
        this.ignoreLocalProxy = false;
        this.autoCleanDirs = true;
        this.headless = false;
        this.startupUrl = "about:blank";
        this.isRemote = true;
        
        // Inicializar preferências padrão
        initializeDefaultPrefs();
        
        // Adicionar argumentos padrão
        addArguments(
            "--no-first-run",  // desabilita página de primeira execução
            "--no-service-autorun",  // não iniciar um serviço
            "--disable-auto-reload",  // não recarregar páginas automaticamente em erros de rede
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-background-timer-throttling",
            "--disable-background-networking",
            "--no-pings",
            "--disable-infobars",
            "--disable-breakpad",
            "--no-default-browser-check",  // desabilita mensagem de navegador padrão
            "--homepage=about:blank",  // define homepage
            "--wm-window-animations-disabled",
            "--animation-duration-scale=0",  // desabilita animações
            "--enable-privacy-sandbox-ads-apis",
            "--disable-search-engine-choice-screen"  // para chrome>=127
        );
        
        if (Utils.isPosix()) {
            addArgument("--password-store=basic");
        }
    }
    
    /**
     * Inicializa as preferências padrão do Chrome.
     */
    @SuppressWarnings("unchecked")
    private void initializeDefaultPrefs() {
        Map<String, Object> devtools = new HashMap<>();
        Map<String, Object> devtoolsPrefs = new HashMap<>();
        devtoolsPrefs.put("currentDockState", "\"undocked\"");
        devtoolsPrefs.put("panel-selectedTab", "\"console\"");
        devtools.put("preferences", devtoolsPrefs);
        prefs.put("devtools", devtools);
        
        Map<String, Object> downloadBubble = new HashMap<>();
        downloadBubble.put("partial_view_enabled", false);
        prefs.put("download_bubble", downloadBubble);
        
        Map<String, Object> inProductHelp = new HashMap<>();
        Map<String, Object> snoozedFeature = new HashMap<>();
        Map<String, Object> highEfficiencyMode = new HashMap<>();
        highEfficiencyMode.put("is_dismissed", true);
        snoozedFeature.put("IPH_HighEfficiencyMode", highEfficiencyMode);
        inProductHelp.put("snoozed_feature", snoozedFeature);
        prefs.put("in_product_help", inProductHelp);
        
        prefs.put("credentials_enable_service", false);
        
        Map<String, Object> profile = new HashMap<>();
        profile.put("password_manager_enabled", false);
        prefs.put("profile", profile);
    }
    
    /**
     * Retorna os argumentos usados para o executável do Chrome.
     *
     * @return lista de argumentos
     */
    public List<String> getArguments() {
        return new ArrayList<>(arguments);
    }
    
    /**
     * Adiciona um argumento para iniciar o Chrome.
     *
     * @param argument argumento a adicionar
     */
    public void addArgument(String argument) {
        if (argument == null || argument.isEmpty()) {
            throw new IllegalArgumentException("Argumento não pode ser nulo ou vazio");
        }
        
        if (argument.startsWith("--user-data-dir=")) {
            String userDataDir = argument.substring(16);
            File dir = new File(userDataDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            this.userDataDir = userDataDir;
        } else if (argument.startsWith("--remote-debugging-port=")) {
            int port = Integer.parseInt(argument.substring(24));
            if (this.debuggerAddress == null) {
                this.debuggerAddress = "127.0.0.1:" + port;
            }
            this.isRemote = false;
        } else if (argument.startsWith("--load-extension=")) {
            String[] extensions = argument.substring(17).split(",");
            this.extensionPaths.addAll(Arrays.asList(extensions));
            return;
        } else if (argument.startsWith("--headless")) {
            this.headless = true;
            if (argument.length() <= 10 || !argument.substring(11).equals("new")) {
                System.err.println("AVISO: headless sem '--headless=new' pode ser bugado, " +
                    "torna você detectável e quebra proxies");
            }
        }
        
        this.arguments.add(argument);
    }
    
    /**
     * Adiciona múltiplos argumentos.
     *
     * @param arguments argumentos a adicionar
     */
    public void addArguments(String... arguments) {
        for (String arg : arguments) {
            addArgument(arg);
        }
    }
    
    /**
     * Retorna as preferências como JSON.
     *
     * @return mapa de preferências
     */
    public Map<String, Object> getPrefs() {
        return new HashMap<>(prefs);
    }
    
    /**
     * Atualiza uma preferência.
     *
     * @param pref nome da preferência (caminho com ponto)
     * @param value valor para definir a preferência
     */
    public void updatePref(String pref, Object value) {
        Map<String, Object> prefMap = new HashMap<>();
        prefMap.put(pref, value);
        Map<String, Object> converted = Prefs.prefsToJson(prefMap);
        mergePrefs(this.prefs, converted);
    }
    
    /**
     * Mescla preferências recursivamente.
     */
    @SuppressWarnings("unchecked")
    private void mergePrefs(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (target.containsKey(key) && target.get(key) instanceof Map && value instanceof Map) {
                mergePrefs((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }
    
    /**
     * Retorna o diretório para salvar todos os dados do navegador.
     * {@code null} (padrão) criará temporariamente um diretório em $temp.
     *
     * @return caminho do diretório de dados do usuário
     */
    public String getUserDataDir() {
        return userDataDir;
    }
    
    /**
     * Define o diretório de dados do usuário.
     *
     * @param dir caminho do diretório
     */
    public void setUserDataDir(String dir) {
        this.userDataDir = dir;
        if (dir != null) {
            addArgument("--user-data-dir=" + dir);
        }
    }
    
    /**
     * Retorna o diretório padrão para baixar arquivos.
     * <p>
     * <strong>Aviso:</strong> o caminho deve ser absoluto
     * </p>
     *
     * @return caminho do diretório de downloads
     */
    public String getDownloadsDir() {
        return downloadsDir;
    }
    
    /**
     * Define o diretório de downloads.
     *
     * @param directoryPath caminho do diretório
     */
    public void setDownloadsDir(String directoryPath) {
        if (directoryPath == null) {
            this.downloadsDir = null;
        } else {
            Path path = Paths.get(directoryPath);
            if (Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Caminho não pode apontar para um arquivo");
            }
            
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao criar diretório de downloads", e);
                }
            }
            
            this.downloadsDir = path.toAbsolutePath().toString();
        }
    }
    
    /**
     * Indica se o Chrome inicia em modo headless.
     * Padrão é {@code false}.
     *
     * @return true se headless
     */
    public boolean isHeadless() {
        return headless;
    }
    
    /**
     * Define o modo headless.
     *
     * @param value true para ativar headless
     */
    public void setHeadless(boolean value) {
        if (!value && this.headless) {
            throw new UnsupportedOperationException(
                "Definir headless=true não pode ser desfeito nas opções no momento"
            );
        }
        if (value) {
            addArgument("--headless=new");
        }
    }
    
    /**
     * Retorna a URL que a primeira aba carrega.
     * Padrão é {@code about:blank}.
     *
     * @return URL de inicialização
     */
    public String getStartupUrl() {
        return startupUrl;
    }
    
    /**
     * Define a URL de inicialização.
     *
     * @param url URL para carregar
     */
    public void setStartupUrl(String url) {
        this.startupUrl = (url != null) ? url : "";
    }
    
    /**
     * Define um único proxy a ser aplicado.
     * <p>
     * <strong>Nota:</strong> Apenas proxies sem autenticação são suportados.
     * Use formato: "http://host:port/" ou "socks5://host:port/"
     * </p>
     *
     * @return URL do proxy
     */
    public String getSingleProxy() {
        return singleProxy;
    }
    
    /**
     * Define o proxy único.
     * <p>
     * <strong>Nota:</strong> Proxies com autenticação não são suportados.
     * Use apenas formato: "http://host:port/" ou "socks5://host:port/"
     * </p>
     *
     * @param proxy URL do proxy (ex: "http://proxy.com:5001/" ou "socks5://proxy.com:1080/")
     */
    public void setSingleProxy(String proxy) {
        this.singleProxy = proxy;
        if (proxy != null) {
            try {
                this.proxyConfig = ProxyConfig.fromUrl(proxy);
            } catch (IllegalArgumentException e) {
                System.err.println("Aviso: Não foi possível fazer parse do proxy: " + e.getMessage());
            }
        }
    }
    
    /**
     * Define a configuração de proxy.
     *
     * @param proxyConfig configuração do proxy
     */
    public void setProxyConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
        if (proxyConfig != null) {
            this.singleProxy = proxyConfig.toUrl();
        }
    }
    
    /**
     * Retorna a configuração de proxy.
     *
     * @return configuração do proxy ou null se não configurado
     */
    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }
    
    /**
     * Configura proxy a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     * @param type tipo do proxy (HTTP, HTTPS, SOCKS5)
     */
    public void setProxyFromString(String proxyString, ProxyConfig.ProxyType type) {
        try {
            ProxyConfig config = ProxyConfig.fromString(proxyString, type);
            setProxyConfig(config);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Formato de proxy inválido: " + e.getMessage());
        }
    }
    
    /**
     * Configura proxy HTTP a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setHttpProxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.HTTP);
    }
    
    /**
     * Configura proxy HTTPS a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setHttpsProxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.HTTPS);
    }
    
    /**
     * Configura proxy SOCKS5 a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setSocks5Proxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.SOCKS5);
    }
    
    /**
     * Retorna o caminho para o binário do Chromium.
     *
     * @return caminho do executável
     */
    public String getBinaryLocation() {
        if (binaryLocation == null) {
            binaryLocation = Utils.findChromeExecutable();
        }
        return binaryLocation;
    }
    
    /**
     * Define o caminho do binário do Chrome.
     *
     * @param value caminho do executável
     */
    public void setBinaryLocation(String value) {
        this.binaryLocation = value;
    }
    
    /**
     * Retorna o ambiente para subprocess.Popen, {@code System.getenv()} por padrão.
     *
     * @return variáveis de ambiente
     */
    public Map<String, String> getEnv() {
        return new HashMap<>(env);
    }
    
    /**
     * Define as variáveis de ambiente.
     *
     * @param env mapa de variáveis
     */
    public void setEnv(Map<String, String> env) {
        this.env = env;
    }
    
    /**
     * Adiciona uma extensão ao Chrome.
     * A extensão pode ser um arquivo compactado (zip, crx, etc.) ou extraído em um diretório.
     * <p>
     * <strong>Nota:</strong> Extensões não são mais usadas para autenticação de proxy.
     * </p>
     *
     * @param path caminho para a extensão
     */
    public void addExtension(String path) {
        File extension = new File(path);
        if (!extension.exists()) {
            throw new IllegalArgumentException("Caminho para a extensão não existe: " + path);
        }
        this.extensionPaths.add(extension.getAbsolutePath());
    }
    
    /**
     * Retorna o endereço da instância remota do devtools no formato "host:port".
     * Definir este valor faz o driver conectar a uma instância remota do navegador
     * (a menos que você também defina user-data-dir).
     *
     * @return endereço do debugger
     */
    public String getDebuggerAddress() {
        return debuggerAddress;
    }
    
    /**
     * Define o endereço do debugger.
     *
     * @param value endereço no formato "host:port"
     */
    public void setDebuggerAddress(String value) {
        this.debuggerAddress = value;
    }
    
    /**
     * Indica se o user-data-dir deve ser limpo automaticamente.
     * Padrão é true.
     *
     * @return true se deve limpar automaticamente
     */
    public boolean isAutoCleanDirs() {
        return autoCleanDirs;
    }
    
    /**
     * Define se deve limpar diretórios automaticamente.
     *
     * @param enabled true para ativar
     */
    public void setAutoCleanDirs(boolean enabled) {
        this.autoCleanDirs = enabled;
    }
    
    /**
     * Habilita o uso de navegador móvel para navegadores que suportam.
     * <p>
     * <strong>Aviso:</strong> Não implementado ainda
     * </p>
     *
     * @param androidPackage nome do pacote Android
     * @param androidActivity atividade Android
     * @param deviceSerial serial do dispositivo
     */
    public void enableMobile(String androidPackage, String androidActivity, String deviceSerial) {
        throw new UnsupportedOperationException("Não implementado ainda");
    }
    
    /**
     * Ignora variáveis de ambiente HTTP_PROXY e HTTPS_PROXY.
     * <p>
     * <strong>Aviso:</strong> Não implementado ainda
     * </p>
     */
    public void ignoreLocalProxyEnvironmentVariables() {
        throw new UnsupportedOperationException("Não implementado ainda");
    }
    
    /**
     * Adiciona uma opção experimental que é passada ao Chromium.
     * <p>
     * <strong>Aviso:</strong> Apenas {@code name="prefs"} é suportado.
     * Este método está deprecated e será removido. Use {@link #updatePref} em vez disso.
     * </p>
     *
     * @param name nome da opção experimental
     * @param value valor da opção
     */
    @Deprecated
    public void addExperimentalOption(String name, Object value) {
        if ("prefs".equals(name) && value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prefsMap = (Map<String, Object>) value;
            Map<String, Object> converted = Prefs.prefsToJson(prefsMap);
            mergePrefs(this.prefs, converted);
        } else {
            throw new UnsupportedOperationException("Apenas opção 'prefs' é suportada");
        }
    }
    
    /**
     * Retorna a lista de caminhos de extensões.
     *
     * @return lista de caminhos
     */
    public List<String> getExtensionPaths() {
        return new ArrayList<>(extensionPaths);
    }
    
    /**
     * Indica se está configurado como remoto.
     *
     * @return true se remoto
     */
    public boolean isRemote() {
        return isRemote;
    }
}

