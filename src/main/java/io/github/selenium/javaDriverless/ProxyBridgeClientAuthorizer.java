package io.github.selenium.javaDriverless;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Autoriza somente sockets pertencentes à árvore do Chrome vinculada à ponte. */
final class ProxyBridgeClientAuthorizer {
    private static final Path PROC = Path.of("/proc");
    private static final Path PROC_NET_TCP = Path.of("/proc/net/tcp");
    private static final Path PROC_NET_TCP6 = Path.of("/proc/net/tcp6");
    private static final boolean PROCESS_GATE_AVAILABLE = detectProcessGateAvailable();

    private ProxyBridgeClientAuthorizer() {
    }

    static boolean processGateAvailable() {
        return PROCESS_GATE_AVAILABLE;
    }

    static void requireProcessGateAvailable(boolean available) {
        if (!available) {
            throw new UnsupportedOperationException(
                    "Ponte de proxy autenticado requer /proc legível para autorizar o processo proprietário");
        }
    }

    record ProcessIdentity(long pid, Instant startInstant) {}

    static Optional<ProcessIdentity> captureIdentity(long pid) {
        if (pid <= 0) {
            return Optional.empty();
        }
        return ProcessHandle.of(pid).flatMap(ProxyBridgeClientAuthorizer::captureIdentity);
    }

    private static Optional<ProcessIdentity> captureIdentity(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return Optional.empty();
        }
        return handle.info().startInstant()
                .map(start -> new ProcessIdentity(handle.pid(), start));
    }

    static boolean isCurrentIdentity(ProcessIdentity identity) {
        return identity != null && captureIdentity(identity.pid())
                .filter(identity::equals)
                .isPresent();
    }

    static boolean authorize(Socket client, ProcessIdentity ownerIdentity, int bridgePort) {
        InetAddress peer = client.getInetAddress();
        if (peer == null || !peer.isLoopbackAddress() || !isCurrentIdentity(ownerIdentity)) {
            return false;
        }
        if (!PROCESS_GATE_AVAILABLE) {
            // Sem um mapa confiável socket -> processo não existe como provar ownership.
            return false;
        }
        long inode = peerSocketInode(
                client.getInetAddress(),
                client.getPort(),
                client.getLocalAddress(),
                bridgePort);
        return inode >= 0 && treeOwnsInode(ownerIdentity, inode);
    }

    private static boolean detectProcessGateAvailable() {
        try {
            if (!Files.isDirectory(PROC)
                    || (!Files.isReadable(PROC_NET_TCP) && !Files.isReadable(PROC_NET_TCP6))
                    || ProcessHandle.current().info().startInstant().isEmpty()) {
                return false;
            }

            Path ownDescriptors = PROC.resolve(Long.toString(ProcessHandle.current().pid())).resolve("fd");
            if (!Files.isDirectory(ownDescriptors) || !Files.isReadable(ownDescriptors)) {
                return false;
            }
            try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(ownDescriptors)) {
                for (Path descriptor : descriptors) {
                    try {
                        Files.readSymbolicLink(descriptor);
                        return true;
                    } catch (IOException unreadableDescriptor) {
                        // A descriptor can close while /proc is being inspected; try another one.
                    }
                }
                return false;
            }
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static long peerSocketInode(
            InetAddress peerAddress,
            int peerPort,
            InetAddress bridgeAddress,
            int bridgePort) {
        long inode = findInode(
                PROC_NET_TCP, peerAddress, peerPort, bridgeAddress, bridgePort);
        return inode >= 0
                ? inode
                : findInode(PROC_NET_TCP6, peerAddress, peerPort, bridgeAddress, bridgePort);
    }

    private static long findInode(
            Path procNetTcp,
            InetAddress peerAddress,
            int peerPort,
            InetAddress bridgeAddress,
            int bridgePort) {
        try {
            for (String line : Files.readAllLines(procNetTcp)) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 10
                        || !socketTupleMatches(
                                fields[1], fields[2],
                                peerAddress, peerPort,
                                bridgeAddress, bridgePort)) {
                    continue;
                }
                try {
                    return Long.parseLong(fields[9]);
                } catch (NumberFormatException malformed) {
                    return -1;
                }
            }
        } catch (IOException unreadable) {
            // Fail closed when the process/socket map cannot be read.
        }
        return -1;
    }

    static boolean socketTupleMatches(
            String procLocal,
            String procRemote,
            InetAddress expectedLocalAddress,
            int expectedLocalPort,
            InetAddress expectedRemoteAddress,
            int expectedRemotePort) {
        ProcEndpoint local = parseProcEndpoint(procLocal);
        ProcEndpoint remote = parseProcEndpoint(procRemote);
        return local != null
                && remote != null
                && local.port() == expectedLocalPort
                && remote.port() == expectedRemotePort
                && local.address().equals(expectedLocalAddress)
                && remote.address().equals(expectedRemoteAddress);
    }

    private static ProcEndpoint parseProcEndpoint(String encoded) {
        int colon = encoded == null ? -1 : encoded.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= encoded.length()) {
            return null;
        }
        try {
            String addressHex = encoded.substring(0, colon);
            if (!(addressHex.length() == 8 || addressHex.length() == 32)) {
                return null;
            }
            byte[] encodedBytes = new byte[addressHex.length() / 2];
            for (int index = 0; index < encodedBytes.length; index++) {
                encodedBytes[index] = (byte) Integer.parseInt(
                        addressHex.substring(index * 2, index * 2 + 2), 16);
            }
            byte[] networkOrder = new byte[encodedBytes.length];
            int wordSize = 4;
            for (int base = 0; base < encodedBytes.length; base += wordSize) {
                for (int offset = 0; offset < wordSize; offset++) {
                    networkOrder[base + offset] = encodedBytes[base + wordSize - 1 - offset];
                }
            }
            return new ProcEndpoint(
                    InetAddress.getByAddress(networkOrder),
                    Integer.parseInt(encoded.substring(colon + 1), 16));
        } catch (RuntimeException | IOException malformed) {
            return null;
        }
    }

    private record ProcEndpoint(InetAddress address, int port) {}

    private static boolean treeOwnsInode(ProcessIdentity ownerIdentity, long inode) {
        String socketLink = "socket:[" + inode + "]";
        return identitiesOwnSocket(
                processTree(ownerIdentity),
                socketLink,
                ProxyBridgeClientAuthorizer::isCurrentIdentity,
                ProxyBridgeClientAuthorizer::pidOwnsSocket);
    }

    static boolean identitiesOwnSocket(
            Iterable<ProcessIdentity> identities,
            String socketLink,
            Predicate<ProcessIdentity> identityValidator,
            BiPredicate<ProcessIdentity, String> descriptorInspector) {
        for (ProcessIdentity identity : identities) {
            if (identityValidator.test(identity)
                    && descriptorInspector.test(identity, socketLink)
                    && identityValidator.test(identity)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ProcessIdentity> processTree(ProcessIdentity ownerIdentity) {
        Set<ProcessIdentity> identities = new HashSet<>();
        if (!isCurrentIdentity(ownerIdentity)) {
            return identities;
        }
        identities.add(ownerIdentity);
        ProcessHandle.of(ownerIdentity.pid())
                .filter(handle -> isCurrentIdentity(ownerIdentity))
                .ifPresent(handle -> handle.descendants()
                        .map(ProxyBridgeClientAuthorizer::captureIdentity)
                        .flatMap(Optional::stream)
                        .forEach(identities::add));
        return identities;
    }

    private static boolean pidOwnsSocket(ProcessIdentity identity, String socketLink) {
        if (!isCurrentIdentity(identity)) {
            return false;
        }
        Path fdDir = Path.of("/proc/" + identity.pid() + "/fd");
        try (DirectoryStream<Path> fds = Files.newDirectoryStream(fdDir)) {
            for (Path fd : fds) {
                if (!isCurrentIdentity(identity)) {
                    return false;
                }
                try {
                    if (socketLink.equals(Files.readSymbolicLink(fd).toString())) {
                        return true;
                    }
                } catch (IOException unreadableFd) {
                    // Descriptor closed between directory listing and readlink.
                }
            }
        } catch (IOException | SecurityException noSuchProcess) {
            // Process exited or is not readable: it cannot authorize this peer.
        }
        return false;
    }
}
