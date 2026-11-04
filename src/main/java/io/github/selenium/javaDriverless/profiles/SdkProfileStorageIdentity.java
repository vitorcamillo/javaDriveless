package io.github.selenium.javaDriverless.profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Canonical and physical filesystem identity used to isolate profile storage. */
record SdkProfileStorageIdentity(String canonicalKey, String physicalKey) {
boolean collidesWith(SdkProfileStorageIdentity other) {
        Path current = Path.of(canonicalKey);
        Path candidate = Path.of(other.canonicalKey);
        return current.startsWith(candidate) || candidate.startsWith(current)
                || (physicalKey != null && physicalKey.equals(other.physicalKey));
    }

Set<String> reservationKeys() {
        Set<String> keys = new HashSet<>();
        keys.add("canonical:" + canonicalKey);
        if (physicalKey != null) {
            keys.add("physical:" + physicalKey);
        }
        return keys;
    }


static SdkProfileStorageIdentity resolve(String value, String profileId, String field) {
    if (value == null || value.isBlank()) {
        return null;
    }
    Path absolute = Path.of(value).toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    if (current == null) {
        throw new ProfileCatalogException("Profile '" + profileId + "' storage " + field
                + " has no filesystem root");
    }

    Path deepestExisting = current;
    BasicFileAttributes finalAttributes = null;
    List<Path> missingSuffix = new ArrayList<>();
    boolean missing = false;
    for (Path component : absolute) {
        current = current.resolve(component);
        if (missing) {
            missingSuffix.add(component);
            continue;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new ProfileCatalogException("Profile '" + profileId + "' storage " + field
                        + " contains a symbolic link component");
            }
            if (!attributes.isDirectory()) {
                String message = "downloadsDir".equals(field)
                        ? "Downloads path is not a directory"
                        : "Profile '" + profileId + "' storage " + field + " is not a directory";
                throw new ProfileCatalogException(message);
            }
            deepestExisting = current;
            finalAttributes = attributes;
        } catch (java.nio.file.NoSuchFileException e) {
            missing = true;
            missingSuffix.add(component);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to inspect profile '" + profileId
                    + "' storage " + field, e);
        }
    }

    try {
        Path canonical = deepestExisting.toRealPath(LinkOption.NOFOLLOW_LINKS);
        for (Path component : missingSuffix) {
            canonical = canonical.resolve(component);
        }
        String canonicalKey = canonical.normalize().toString().toLowerCase(Locale.ROOT);
        String physicalKey = missing || finalAttributes == null || finalAttributes.fileKey() == null
                ? null : finalAttributes.fileKey().toString();
        return new SdkProfileStorageIdentity(canonicalKey, physicalKey);
    } catch (IOException e) {
        throw new ProfileCatalogException("Failed to resolve profile '" + profileId
                + "' storage " + field, e);
    }
}
}
