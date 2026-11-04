package io.github.selenium.javaDriverless.profiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Deterministic clone diversity and collision-free storage reservation. */
final class SdkProfileCloneSupport {
    private SdkProfileCloneSupport() {}

    static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value);
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static long stableSeed(String value) {
        String digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        return Long.parseUnsignedLong(digest.substring(0, Long.BYTES * 2), 16) & Long.MAX_VALUE;
    }

    static int uniqueNoiseSeed(long seed, Set<Integer> reserved) {
        int candidate = Long.hashCode(seed);
        if (candidate == 0) {
            candidate = 1;
        }
        while (!reserved.add(candidate)) {
            candidate = candidate == Integer.MAX_VALUE ? Integer.MIN_VALUE : candidate + 1;
            if (candidate == 0) {
                candidate = 1;
            }
        }
        return candidate;
    }

    static void isolateCloneState(SdkProfile clone,
                                          String userDataDir,
                                          String downloadsDir,
                                          int noiseSeed) {
        clone.storage.userDataDir = userDataDir;
        clone.storage.downloadsDir = downloadsDir;
        clone.fingerprint.noiseSeed = noiseSeed;
    }

    static void reserveCatalogStoragePath(String profileId,
                                                  String field,
                                                  String value,
                                                  Set<String> reserved) {
        if (value == null || value.isBlank()) {
            return;
        }
        SdkProfileStorageIdentity identity = SdkProfileStorageIdentity.resolve(value, profileId, field);
        if (identity != null) {
            reserved.addAll(identity.reservationKeys());
        }
    }

    static boolean reserveCloneStoragePaths(String userDataDir,
                                                    String downloadsDir,
                                                    Set<String> reserved) {
        Set<String> candidates = new HashSet<>();
        for (String value : new String[]{userDataDir, downloadsDir}) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Path path = Path.of(value).toAbsolutePath().normalize();
            SdkProfileStorageIdentity identity;
            try {
                identity = SdkProfileStorageIdentity.resolve(value, "clone", "storage");
            } catch (ProfileCatalogException e) {
                return false;
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Set<String> keys = identity.reservationKeys();
            if (keys.stream().anyMatch(reserved::contains)
                    || keys.stream().anyMatch(candidates::contains)) {
                return false;
            }
            candidates.addAll(keys);
        }
        reserved.addAll(candidates);
        return true;
    }
}
