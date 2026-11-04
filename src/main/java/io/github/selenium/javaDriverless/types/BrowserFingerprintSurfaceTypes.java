package io.github.selenium.javaDriverless.types;

import java.util.List;

class BrowserFingerprintScreen {
    public String mode = "real";
    public int width = 1920;
    public int height = 1080;
    public int availWidth = 1920;
    public int availHeight = 1040;
    public int colorDepth = 24;
    public int pixelDepth = 24;
    public double deviceScaleFactor = 1;
    // PC real com taskbar já expõe work-area nativa correta. Quando false, a surface NÃO instala
    // o normalizador de work-area (que engancharia document.createElement / window.setTimeout —
    // tell de automação). Default true mantém o comportamento p/ Xvfb/headless (Docker intacto).
    public boolean workAreaPatch = true;
}

class BrowserFingerprintWebGl {
    public String mode = "real";
    public String vendor = "Google Inc. (NVIDIA Corporation)";
    public String renderer = "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3060/PCIe/SSE2, OpenGL 4.5.0)";
    public String unmaskedVendor = vendor;
    public String unmaskedRenderer = renderer;
}

class BrowserFingerprintWebRtc {
    public String mode = "altered";
    public boolean protectLocalIps = true;
    // Default fail-closed: relay-only é o único modo que impede a coleta de host/srflx via UDP
    // (o STUN ignora proxy HTTP/SOCKS e vazaria o IP público real). Consumidores que precisem de
    // WebRTC funcional devem setar mode="real" explicitamente.
    public boolean disableUdp = true;
    public String publicIp = "";
    public List<String> allowedIps = List.of();
}

class BrowserFingerprintGeolocation {
    public String mode = "prompt";
    public Double latitude = null;
    public Double longitude = null;
    public Double accuracy = null;
}

class BrowserFingerprintPermissions {
    public String geolocation = "prompt";
    public String notifications = "default";
}

class BrowserFingerprintMediaDevices {
    public String mode = "real";
    public int audioInputs = 1;
    public int audioOutputs = 1;
    public int videoInputs = 0;
}

class BrowserFingerprintSpeech {
    public String mode = "auto";
    public List<BrowserFingerprintProfile.Voice> voices = List.of();
}

class BrowserFingerprintVoice {
    public String name = "";
    public String lang = "";
    public String voiceURI = "";
    public boolean localService = true;
    public boolean isDefault = false;
}

class BrowserFingerprintAudio {
    public String mode = "real";
    public double noise = 0.0;
}

class BrowserFingerprintCanvas {
    public String mode = "real";
    public double noise = 0.0;
    /** all protege também canvas ligado ao DOM; detached preserva canvas visível, mas reduz isolamento. */
    public String scope = "all";
}

class BrowserFingerprintClientRects {
    public String mode = "real";
    public double noise = 0.0;
}

class BrowserFingerprintStorage {
    /**
     * {@code spoof} preserves the historical per-profile quota wrapper. {@code real} leaves the
     * native StorageManager method intact, which is required by strict prototype-integrity gates.
     */
    public String mode = "spoof";
}

class BrowserFingerprintFonts {
    public String mode = "auto";
    public List<String> families = List.of();
    // Modo "subset": fontes OPCIONAIS escondidas por conta. Cada driver esconde uma fatia
    // seedada das não-core → enumeração de fontes ÚNICA por driver, sem tocar nas core.
    public List<String> hidden = List.of();
}

class BrowserFingerprintPorts {
    public String mode = "protect";
    public List<Integer> protectedPorts = List.of(3001, 3389, 5900, 5800, 7070, 6568, 5938, 63333);
}

class BrowserFingerprintConnection {
    public String mode = "real";
    public String effectiveType = "4g";
    public double downlink = 10;
    public int rtt = 50;
    public boolean saveData = false;
    // Vazio por padrão: um desktop real NÃO expõe navigator.connection.type. O
    // surface script só injeta .type quando não-vazio (ver SeleniumProxyNavigator
    // SurfaceScripts). Perfis mobile devem setar "cellular" explicitamente.
    public String type = "";
}
