package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsActivePortWaiterSecurityTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void restrictTempDirWhenPosixIsAvailable() throws Exception {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(tempDir, privatePermissions());
        }
    }

    @Test
    void readsPortFromRegularFile() throws Exception {
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));
    }

    @Test
    void deleteStaleRemovesGroupAndOtherWritePermissions() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "Este sistema de arquivos não suporta permissões POSIX");
        Files.setPosixFilePermissions(tempDir, permissions0775());
        Path portFile = Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "old");

        DevToolsActivePortWaiter.deleteStale(tempDir);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE), Files.getPosixFilePermissions(tempDir));
        assertTrue(Files.notExists(portFile));
    }

    @Test
    void rejectsGroupWritableUserDataDirWithoutDeleteStale() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "Este sistema de arquivos não suporta permissões POSIX");
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");
        Files.setPosixFilePermissions(tempDir, permissions0775());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));

        assertTrue(error.getMessage().contains("gravável por terceiros"));
    }

    @Test
    void acceptsPortInOwnerPrivateDirectory() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "Este sistema de arquivos não suporta permissões POSIX");
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");
        Files.setPosixFilePermissions(tempDir, privatePermissions());

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));
    }

    @Test
    void acceptsSymbolicLinkUserDataDirWhenTargetIsOwnerPrivate() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("profile-target"));
        if (Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(target, privatePermissions());
        }
        Files.writeString(target.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");
        Path userDataDir = tempDir.resolve("profile-link");
        try {
            Files.createSymbolicLink(userDataDir, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Este sistema de arquivos não permite criar symlinks");
        }

        assertEquals(9222, DevToolsActivePortWaiter.await(userDataDir, 9222, null, 1f));
    }

    private DevToolsActivePortWaiter.ListenerPids savedListenerPids;

    @BeforeEach
    void saveListenerPids() {
        savedListenerPids = DevToolsActivePortWaiter.listenerPids;
    }

    @AfterEach
    void restoreListenerPids() {
        DevToolsActivePortWaiter.listenerPids = savedListenerPids;
    }

    @Test
    void ownerIsCurrentUserAcceptsSupportedOwnerNames() {
        assertTrue(DevToolsActivePortWaiter.ownerIsCurrentUser("bob", "bob", null));
        assertTrue(DevToolsActivePortWaiter.ownerIsCurrentUser("BOB", "bob", null));
        assertTrue(DevToolsActivePortWaiter.ownerIsCurrentUser("PC\\bob", "bob", null));
        assertFalse(DevToolsActivePortWaiter.ownerIsCurrentUser("BUILTIN\\Administrators", "bob", null));
        assertFalse(DevToolsActivePortWaiter.ownerIsCurrentUser("PC\\alice", "bob", null));
        assertFalse(DevToolsActivePortWaiter.ownerIsCurrentUser("alice", "bob", null));
    }

    @Test
    void ownerIsCurrentUserAcceptsProcessDefaultOwner() {
        assertTrue(DevToolsActivePortWaiter.ownerIsCurrentUser("BUILTIN\\Administrators", "bob",
                "BUILTIN\\Administrators"));
        assertTrue(DevToolsActivePortWaiter.ownerIsCurrentUser("builtin\\administrators", "bob",
                "BUILTIN\\Administrators"));
        assertFalse(DevToolsActivePortWaiter.ownerIsCurrentUser("PC\\alice", "bob",
                "BUILTIN\\Administrators"));
    }

    @Test
    void listenSocketOwnedByAcceptsMatchingListenSocketInProcessFd() throws Exception {
        Path procRoot = fakeProcRoot("0A", "12345", "socket:[12345]");

        assertTrue(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), procRoot));
    }

    @Test
    void listenSocketOwnedByRejectsDifferentSocketInProcessFd() throws Exception {
        Path procRoot = fakeProcRoot("0A", "12345", "socket:[99999]");

        assertFalse(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), procRoot));
    }

    @Test
    void listenSocketOwnedByRejectsNonListeningSocket() throws Exception {
        Path procRoot = fakeProcRoot("01", "12345", "socket:[12345]");

        assertFalse(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), procRoot));
    }

    @Test
    void listenSocketOwnedByAcceptsCurrentProcessWithoutProcNetTcp() {
        DevToolsActivePortWaiter.listenerPids = port -> Set.of(ProcessHandle.current().pid());

        assertTrue(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), tempDir));
    }

    @Test
    void listenSocketOwnedByRejectsForeignPidWithoutProcNetTcp() {
        DevToolsActivePortWaiter.listenerPids = port -> Set.of(Long.MAX_VALUE);

        assertFalse(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), tempDir));
    }

    @Test
    void listenSocketOwnedByRejectsLookupFailureWithoutProcNetTcp() {
        DevToolsActivePortWaiter.listenerPids = port -> {
            throw new IOException("sem consulta ao SO");
        };

        assertFalse(DevToolsActivePortWaiter.listenSocketOwnedBy(9222, ProcessHandle.current(), tempDir));
    }

    @Test
    void parseLsofPidsExtractsPids() {
        assertEquals(Set.of(123L, 456L), DevToolsActivePortWaiter.parseLsofPids("p123\nf7\np456\n"));
    }

    @Test
    void parseLsofPidsIgnoresGarbage() {
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseLsofPids("lixo\n\nf7\npxyz\n"));
    }

    @Test
    void parseNetstatListenerPidsExtractsListeningPids() {
        String output = "  Proto  Endereço local         Endereço externo       Estado           PID\n"
                + "  TCP    127.0.0.1:9222         0.0.0.0:0              LISTENING       4321\n"
                + "  TCP    [::1]:9222             [::]:0                 LISTENING       99\n"
                + "  TCP    127.0.0.1:9222         127.0.0.1:5000         ESTABLISHED     7\n"
                + "  TCP    127.0.0.1:92220        0.0.0.0:0              LISTENING       8\n";

        assertEquals(Set.of(4321L, 99L), DevToolsActivePortWaiter.parseNetstatListenerPids(output, 9222));
    }

    @Test
    void parseNetstatListenerPidsIgnoresLocalizedState() {
        String output = "  Proto  Endereço local         Endereço externo       Estado           PID\n"
                + "  TCP    127.0.0.1:9222         0.0.0.0:0              ESCUTANDO        4321\n"
                + "  TCP    [::1]:9222             [::]:0                 ESCUTANDO        99\n"
                + "  TCP    127.0.0.1:9222         127.0.0.1:5000         ESTABELECIDA     7\n"
                + "  TCP    127.0.0.1:92220        0.0.0.0:0              ESCUTANDO        8\n"
                + "  UDP    0.0.0.0:9222           *:*                                    55\n";

        assertEquals(Set.of(4321L, 99L), DevToolsActivePortWaiter.parseNetstatListenerPids(output, 9222));
    }

    @Test
    void parseLsofPidsHandlesNullEmptyAndShortLines() {
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseLsofPids(null));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseLsofPids(""));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseLsofPids("p"));
    }

    @Test
    void parseLsofPidsTrimsAndSkipsNonNumeric() {
        assertEquals(Set.of(123L, 456L),
                DevToolsActivePortWaiter.parseLsofPids("p123\nf7\npabc\n p456 \nx9"));
        assertEquals(Set.of(1L, 2L), DevToolsActivePortWaiter.parseLsofPids("p1\r\np2"));
    }

    @Test
    void parseNetstatListenerPidsIgnoresNonTcpAndPortSuffix() {
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids(null, 9222));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids("TCP 127.0.0.1:9222", 9222));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "UDP 0.0.0.0:9222 0.0.0.0:0 LISTENING 10", 9222));
        // "0.0.0.0:19222" termina com ":19222", não com ":9222".
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "TCP 0.0.0.0:19222 0.0.0.0:0 LISTENING 11", 9222));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "TCP 0.0.0.0:9222 1.2.3.4:5555 ESTABLISHED 12", 9222));
        assertEquals(Set.of(), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "TCP 127.0.0.1:9222 0.0.0.0:0 LISTENING abc", 9222));
    }

    @Test
    void parseNetstatListenerPidsAcceptsBracketedAndLocalizedState() {
        assertEquals(Set.of(13L), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "TCP [::]:9222 [::]:0 LISTENING 13", 9222));
        assertEquals(Set.of(14L), DevToolsActivePortWaiter.parseNetstatListenerPids(
                "tcp 127.0.0.1:9222 0.0.0.0:0 ABHÖREN 14", 9222));
    }

    @Test
    void rejectsUserDataDirWritableByOthers() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "Este sistema de arquivos não suporta permissões POSIX");
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");
        Files.setPosixFilePermissions(tempDir, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));

        assertTrue(error.getMessage().contains("gravável por terceiros"));
    }

    @Test
    void rejectsSymbolicLink() throws Exception {
        Path target = tempDir.resolve("valid-port-file");
        Path portFile = tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME);
        Files.writeString(target, "9222\n/devtools/browser/x\n");
        try {
            Files.createSymbolicLink(portFile, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Este sistema de arquivos não permite criar symlinks");
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));

        assertTrue(error.getMessage().contains("link simbólico"));
    }

    @Test
    void oversizedFileIsIgnoredUntilTimeout() throws Exception {
        Files.write(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), new byte[4097]);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 0, null, 0.3f));

        assertTrue(error.getMessage().contains("Timeout"));
    }

    @Test
    void acceptsFileAtExactly4096Bytes() throws Exception {
        byte[] prefix = "9222\n/devtools/browser/x\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[4096];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        java.util.Arrays.fill(content, prefix.length, content.length, (byte) ' ');
        Files.write(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), content);

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));
    }

    @Test
    void rejectsFileAt4097BytesWithTimeout() throws Exception {
        byte[] prefix = "9222\n/devtools/browser/x\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = new byte[4097];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        java.util.Arrays.fill(content, prefix.length, content.length, (byte) '\n');
        Files.write(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), content);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 9222, null, 0.3f));

        assertTrue(error.getMessage().contains("Timeout"));
    }

    @Test
    void rejectsPortDifferentFromExpectedPort() throws Exception {
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x\n");

        assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 1234, null, 1f));
    }

    private static Set<PosixFilePermission> privatePermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
    }

    private static Set<PosixFilePermission> permissions0775() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    }

    private Path fakeProcRoot(String state, String inode, String fdTarget) throws Exception {
        Path procRoot = Files.createDirectory(tempDir.resolve("fake-proc-" + state + inode));
        Path net = Files.createDirectory(procRoot.resolve("net"));
        String header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
        String row = "   0: 0100007F:2406 00000000:0000 " + state
                + " 00000000:00000000 00:00000000 00000000  1000        0 " + inode
                + " 1 0000000000000000 100 0 0 10 0\n";
        Files.writeString(net.resolve("tcp"), header + row);
        Path fdDir = Files.createDirectories(procRoot.resolve(Long.toString(ProcessHandle.current().pid()))
                .resolve("fd"));
        Files.createSymbolicLink(fdDir.resolve("3"), Path.of(fdTarget));
        return procRoot;
    }

}
