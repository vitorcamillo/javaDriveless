package io.github.selenium.javaDriverless.cdp.pipe;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Localiza canais pipe por sentinela; '~' evita colisão com host:port real. */
public final class CdpPipeRegistry {
    public static final String HOST_PREFIX = "pipe~";

    private static final ConcurrentMap<String, CdpPipeChannel> CHANNELS = new ConcurrentHashMap<>();

    private CdpPipeRegistry() {
    }

    public static boolean isPipeHost(String host) {
        return host != null && host.startsWith(HOST_PREFIX);
    }

    public static Optional<CdpPipeChannel> lookup(String host) {
        return Optional.ofNullable(host == null ? null : CHANNELS.get(host));
    }

    static void register(CdpPipeChannel channel) {
        CHANNELS.put(channel.getHost(), channel);
    }

    static void unregister(String host) {
        CHANNELS.remove(host);
    }
}
