package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Caracterizacao do portao de autorizacao da ponte de proxy: parsing de
 * /proc/net/tcp, fail-closed em peer nao-loopback/identidade morta e
 * dupla validacao de identidade (anti-TOCTOU).
 */
class ProxyBridgeClientAuthorizerTest {

    @Test
    void socketTupleMatchesIpv4LoopbackTuple() throws UnknownHostException {
        // /proc/net/tcp codifica 127.0.0.1 como 0100007F (little-endian por word).
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0100007F:1F90",
                "0100007F:CE04",
                InetAddress.getByName("127.0.0.1"), 8080,
                InetAddress.getByName("127.0.0.1"), 52740))
                .isTrue();
    }

    @Test
    void socketTupleRejectsMismatchedPortOrAddress() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0100007F:1F90", "0100007F:CE04",
                loopback, 9999, loopback, 52740))
                .isFalse();
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0100007F:1F90", "0100007F:CE04",
                loopback, 8080, loopback, 9999))
                .isFalse();
    }

    @Test
    void socketTupleRejectsMalformedEndpoints() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "nocolon", "0100007F:CE04", loopback, 8080, loopback, 52740))
                .isFalse();
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "12345:1F90", "0100007F:CE04", loopback, 8080, loopback, 52740))
                .isFalse();
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0100007F:ZZZZ", "0100007F:CE04", loopback, 8080, loopback, 52740))
                .isFalse();
    }

    @Test
    void socketTupleMatchesIpv6LoopbackTuple() throws UnknownHostException {
        // ::1 em /proc/net/tcp6: 15 words zeradas + word final 01000000.
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "00000000000000000000000001000000:1F90",
                "00000000000000000000000001000000:CE04",
                InetAddress.getByName("::1"), 8080,
                InetAddress.getByName("::1"), 52740))
                .isTrue();
    }

    @Test
    void captureIdentityRejectsNonPositivePid() {
        assertThat(ProxyBridgeClientAuthorizer.captureIdentity(0)).isEmpty();
        assertThat(ProxyBridgeClientAuthorizer.captureIdentity(-1)).isEmpty();
    }

    @Test
    void captureIdentityReturnsEmptyForUnknownPid() {
        long unknown = firstAbsentPid();
        assertThat(ProxyBridgeClientAuthorizer.captureIdentity(unknown)).isEmpty();
    }

    @Test
    void captureIdentityCapturesCurrentProcess() {
        long current = ProcessHandle.current().pid();
        Optional<ProxyBridgeClientAuthorizer.ProcessIdentity> identity =
                ProxyBridgeClientAuthorizer.captureIdentity(current);
        assertThat(identity).isPresent();
        assertThat(identity.get().pid()).isEqualTo(current);
        assertThat(ProxyBridgeClientAuthorizer.isCurrentIdentity(identity.get())).isTrue();
    }

    @Test
    void isCurrentIdentityFailsClosedForNullAndDeadIdentity() {
        assertThat(ProxyBridgeClientAuthorizer.isCurrentIdentity(null)).isFalse();
        ProxyBridgeClientAuthorizer.ProcessIdentity dead =
                new ProxyBridgeClientAuthorizer.ProcessIdentity(firstAbsentPid(), Instant.now());
        assertThat(ProxyBridgeClientAuthorizer.isCurrentIdentity(dead)).isFalse();
    }

    @Test
    void identitiesOwnSocketValidatesIdentityBeforeAndAfterInspection() {
        ProxyBridgeClientAuthorizer.ProcessIdentity identity =
                ProxyBridgeClientAuthorizer.captureIdentity(ProcessHandle.current().pid()).orElseThrow();
        AtomicInteger validations = new AtomicInteger();

        boolean owned = ProxyBridgeClientAuthorizer.identitiesOwnSocket(
                List.of(identity),
                "socket:[42]",
                checked -> {
                    validations.incrementAndGet();
                    return true;
                },
                (checked, link) -> true);

        assertThat(owned).isTrue();
        assertThat(validations.get()).isEqualTo(2);
    }

    @Test
    void identitiesOwnSocketFailsClosedWhenValidationRejects() {
        ProxyBridgeClientAuthorizer.ProcessIdentity identity =
                new ProxyBridgeClientAuthorizer.ProcessIdentity(ProcessHandle.current().pid(), Instant.now());
        AtomicInteger inspections = new AtomicInteger();

        boolean owned = ProxyBridgeClientAuthorizer.identitiesOwnSocket(
                List.of(identity),
                "socket:[42]",
                checked -> false,
                (checked, link) -> {
                    inspections.incrementAndGet();
                    return true;
                });

        assertThat(owned).isFalse();
        assertThat(inspections.get()).isZero();
    }

    @Test
    void identitiesOwnSocketFailsClosedForEmptyTree() {
        assertThat(ProxyBridgeClientAuthorizer.identitiesOwnSocket(
                List.of(), "socket:[42]", checked -> true, (checked, link) -> true))
                .isFalse();
    }

    @Test
    void authorizeFailsClosedForSocketWithoutPeer() {
        Socket unconnected = new Socket();
        ProxyBridgeClientAuthorizer.ProcessIdentity identity =
                ProxyBridgeClientAuthorizer.captureIdentity(ProcessHandle.current().pid()).orElseThrow();

        assertThat(ProxyBridgeClientAuthorizer.authorize(unconnected, identity, 8080)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void authorizeFailsClosedForDeadOwnerEvenWithLoopbackPeer() {
        assumeTrue(ProxyBridgeClientAuthorizer.processGateAvailable(),
                "porta /proc indisponivel neste ambiente");
        Socket peer = loopbackPeerSocket(40000);
        ProxyBridgeClientAuthorizer.ProcessIdentity dead =
                new ProxyBridgeClientAuthorizer.ProcessIdentity(firstAbsentPid(), Instant.now());

        assertThat(ProxyBridgeClientAuthorizer.authorize(peer, dead, 8080)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void authorizeFailsClosedWhenSocketIsAbsentFromProcTable() {
        assumeTrue(ProxyBridgeClientAuthorizer.processGateAvailable(),
                "porta /proc indisponivel neste ambiente");
        Socket peer = loopbackPeerSocket(41234);
        ProxyBridgeClientAuthorizer.ProcessIdentity identity =
                ProxyBridgeClientAuthorizer.captureIdentity(ProcessHandle.current().pid()).orElseThrow();

        assertThat(ProxyBridgeClientAuthorizer.authorize(peer, identity, 54321)).isFalse();
    }

    @Test
    void requireProcessGateAvailableFailsClosedOnlyWhenUnavailable() {
        assertThatThrownBy(() -> ProxyBridgeClientAuthorizer.requireProcessGateAvailable(false))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("/proc");
        assertThatCode(() -> ProxyBridgeClientAuthorizer.requireProcessGateAvailable(true))
                .doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void processGateIsAvailableOnLinuxWithReadableProc() {
        // @EnabledOnOs garante Linux, nao /proc montado/legivel: ambientes
        // restritos (alguns containers) pulam em vez de falhar.
        assumeTrue(java.nio.file.Files.exists(java.nio.file.Path.of("/proc/self")),
                "/proc nao disponivel neste ambiente");
        assertThat(ProxyBridgeClientAuthorizer.processGateAvailable()).isTrue();
    }

    private static Socket loopbackPeerSocket(int peerPort) {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Socket peer = org.mockito.Mockito.mock(Socket.class);
        org.mockito.Mockito.when(peer.getInetAddress()).thenReturn(loopback);
        org.mockito.Mockito.when(peer.getPort()).thenReturn(peerPort);
        org.mockito.Mockito.when(peer.getLocalAddress()).thenReturn(loopback);
        return peer;
    }

    private static long firstAbsentPid() {
        long candidate = 4_000_000;
        while (candidate > 1 && ProcessHandle.of(candidate).isPresent()) {
            candidate--;
        }
        return candidate;
    }
}
