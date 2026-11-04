package io.github.selenium.javaDriverless.profiles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

/** Pure query, patch and naming operations for the profile catalog. */
final class SdkProfileCatalogOperations {
    private SdkProfileCatalogOperations() {}

    static String isolatedSibling(String sourcePath, String cloneId) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return sourcePath;
        }
        Path source = Path.of(sourcePath).normalize();
        Path name = source.getFileName();
        if (name == null) {
            return source.resolve(cloneId).toString();
        }
        Path parent = source.getParent();
        String isolatedName = name + "-" + cloneId;
        return parent == null ? isolatedName : parent.resolve(isolatedName).toString();
    }

    static void applyDeterministicDiversity(SdkProfile profile, long seed) {
        Random random = new Random(seed);
        profile.fingerprint.canvas.mode = "noise";
        profile.fingerprint.canvas.noise = 0.001 + random.nextDouble(0.004);
        profile.fingerprint.audio.mode = "noise";
        profile.fingerprint.audio.noise = 0.001 + random.nextDouble(0.004);
        profile.fingerprint.clientRects.mode = "noise";
        profile.fingerprint.clientRects.noise = 0.01 + random.nextDouble(0.04);
    }

    static boolean matchesQuery(SdkProfile profile, SdkProfileListQuery query) {
        profile.applyDefaults();
        if (query.folder != null && !query.folder.equalsIgnoreCase(profile.metadata.folder)) {
            return false;
        }
        if (query.status != null && !query.status.equalsIgnoreCase(profile.metadata.status)) {
            return false;
        }
        if (query.pinned != null && query.pinned != profile.metadata.pinned) {
            return false;
        }
        if (query.locked != null && query.locked != profile.metadata.locked) {
            return false;
        }
        if (query.tags != null && !query.tags.isEmpty()
                && !profile.metadata.tags.stream().map(tag -> tag.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(query.tags.stream().map(tag -> tag.toLowerCase(Locale.ROOT)).toList())) {
            return false;
        }
        if (query.query != null && !query.query.isBlank()) {
            String needle = query.query.toLowerCase(Locale.ROOT);
            String haystack = (profile.id + " " + profile.name + " " + profile.metadata.notes + " "
                    + profile.metadata.mainWebsite).toLowerCase(Locale.ROOT);
            return haystack.contains(needle);
        }
        return true;
    }

    static Comparator<SdkProfile> comparatorFor(String sortBy) {
        String key = sortBy == null ? "id" : sortBy.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "name" -> Comparator.comparing(profile -> nullSafe(profile.name), String.CASE_INSENSITIVE_ORDER);
            case "createdat" -> Comparator.comparing(profile -> nullSafe(profile.metadata.createdAt));
            case "updatedat" -> Comparator.comparing(profile -> nullSafe(profile.metadata.updatedAt));
            case "folder" -> Comparator.comparing(profile -> nullSafe(profile.metadata.folder), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(profile -> nullSafe(profile.id), String.CASE_INSENSITIVE_ORDER);
        };
    }

    static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    static void applyPatch(SdkProfile profile, SdkProfileBulkPatchRequest request) {
        if (request.folder != null) {
            profile.metadata.folder = request.folder;
        }
        if (request.status != null) {
            profile.metadata.status = request.status;
        }
        if (request.pinned != null) {
            profile.metadata.pinned = request.pinned;
        }
        if (request.locked != null) {
            profile.metadata.locked = request.locked;
        }
        if (request.notes != null) {
            profile.metadata.notes = request.notes;
        }
        if (request.mainWebsite != null) {
            profile.metadata.mainWebsite = request.mainWebsite;
        }
        if (request.addTags != null) {
            for (String tag : request.addTags) {
                if (tag != null && !tag.isBlank() && !profile.metadata.tags.contains(tag.trim())) {
                    profile.metadata.tags.add(tag.trim());
                }
            }
        }
        if (request.removeTags != null) {
            profile.metadata.tags.removeIf(tag -> request.removeTags.stream()
                    .anyMatch(remove -> remove != null && remove.equalsIgnoreCase(tag)));
        }
        profile.metadata.updatedAt = Instant.now().toString();
    }

    static String requireSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("Profile selector must not be blank");
        }
        return selector.trim();
    }

    static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        return name.trim();
    }
}
