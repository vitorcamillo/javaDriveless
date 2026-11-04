package io.github.selenium.javaDriverless.profiles;

import static io.github.selenium.javaDriverless.profiles.SdkProfileArchiveSupport.*;
import static io.github.selenium.javaDriverless.profiles.SdkProfileCatalogOperations.*;
import static io.github.selenium.javaDriverless.profiles.SdkProfileCloneSupport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.utils.OwnerOnlyFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SdkProfileCatalogWorkflows {
    public enum ImportTrust {
        UNTRUSTED,
        TRUSTED
    }

    // Limites anti-OOM do restoreSnapshot: tamanho do arquivo e nº de perfis.
    static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024;
    static final int MAX_SNAPSHOT_PROFILES = 10_000;

    private final SdkProfileCatalog catalog;

    SdkProfileCatalogWorkflows(SdkProfileCatalog catalog) {
        this.catalog = catalog;
    }

    public SdkProfileCloneResult cloneProfiles(SdkProfileCloneRequest request) {
        return SdkProfileCatalogLock.with(catalog.root, () -> cloneProfilesUnlocked(request));
    }

    private SdkProfileCloneResult cloneProfilesUnlocked(SdkProfileCloneRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.count < 1 || request.count > 100) {
            throw new IllegalArgumentException("Clone count must be between 1 and 100");
        }
        SdkProfile source = catalog.select(request.sourceSelector);
        String idPrefix = SdkProfileCatalog.normalizeId(request.idPrefix);
        String namePrefix = requireName(request.namePrefix);
        long baseSeed = request.seed == null ? stableSeed(source.id + ":" + idPrefix) : request.seed;

        SdkProfileCloneResult result = new SdkProfileCloneResult();
        result.dryRun = request.dryRun;
        List<SdkProfile> catalogProfiles = catalog.list();
        Set<String> plannedIds = new HashSet<>();
        Set<Integer> plannedNoiseSeeds = new HashSet<>();
        Set<String> reservedStoragePaths = new HashSet<>();
        catalogProfiles.stream()
                .map(profile -> profile.fingerprint.noiseSeed)
                .forEach(plannedNoiseSeeds::add);
        for (SdkProfile profile : catalogProfiles) {
            reserveCatalogStoragePath(
                    profile.id, "userDataDir", profile.storage.userDataDir, reservedStoragePaths);
            reserveCatalogStoragePath(
                    profile.id, "downloadsDir", profile.storage.downloadsDir, reservedStoragePaths);
        }
        for (int index = 1; index <= request.count; index++) {
            String nextId = SdkProfileCatalog.normalizeId(idPrefix + "-" + index);
            String nextName = namePrefix + " " + index;
            long isolationSeed = baseSeed + index;
            int noiseSeed = uniqueNoiseSeed(isolationSeed, plannedNoiseSeeds);
            Long itemSeed = request.randomize ? isolationSeed : null;
            String nextUserDataDir = isolatedSibling(source.storage.userDataDir, nextId);
            String nextDownloadsDir = isolatedSibling(source.storage.downloadsDir, nextId);
            String status;
            if (catalog.load(nextId).isPresent() || !plannedIds.add(nextId)) {
                status = "conflict";
            } else if (!reserveCloneStoragePaths(nextUserDataDir, nextDownloadsDir, reservedStoragePaths)) {
                status = "storage_conflict";
            } else {
                status = "planned";
            }
            result.items.add(new SdkProfileCloneResult.SdkProfileCloneItem(nextId, nextName, status, itemSeed));
            if (!request.dryRun && "planned".equals(status)) {
                SdkProfile clone = catalog.deepCopy(source);
                clone.id = nextId;
                clone.name = nextName;
                clone.metadata.createdAt = Instant.now().toString();
                clone.metadata.updatedAt = clone.metadata.createdAt;
                clone.metadata.deletedAt = null;
                clone.metadata.status = "active";
                clone.metadata.seeds.put("isolation", isolationSeed);
                if (itemSeed != null) {
                    clone.metadata.seeds.put("clone", itemSeed);
                    applyDeterministicDiversity(clone, itemSeed);
                }
                isolateCloneState(clone, nextUserDataDir, nextDownloadsDir, noiseSeed);
                catalog.saveUnlocked(clone);
            }
        }
        return result;
    }

    public List<SdkProfile> list(SdkProfileListQuery query) {
        if (query == null) {
            return catalog.list();
        }
        Comparator<SdkProfile> comparator = comparatorFor(query.sortBy);
        if (query.descending) {
            comparator = comparator.reversed();
        }
        return catalog.list().stream()
                .filter(profile -> matchesQuery(profile, query))
                .sorted(comparator)
                .skip(Math.max(0, query.offset))
                .limit(query.limit <= 0 ? Long.MAX_VALUE : query.limit)
                .toList();
    }

    public SdkProfileBulkPatchResult bulkPatch(SdkProfileBulkPatchRequest request) {
        Objects.requireNonNull(request, "request");
        // Leitura, checagem de locked, patch e gravacao na mesma secao critica:
        // fora dela, uma gravacao concorrente (ex.: bloquear o perfil) seria
        // sobrescrita pela copia desatualizada selecionada antes do lock.
        return SdkProfileCatalogLock.with(catalog.root, () -> bulkPatchUnlocked(request));
    }

    private SdkProfileBulkPatchResult bulkPatchUnlocked(SdkProfileBulkPatchRequest request) {
        SdkProfileBulkPatchResult result = new SdkProfileBulkPatchResult();
        result.dryRun = request.dryRun;
        List<SdkProfile> pendentes = new ArrayList<>();
        for (String selector : request.selectors) {
            SdkProfile profile;
            try {
                profile = catalog.select(selector);
            } catch (ProfileSelectionException e) {
                result.items.add(new SdkProfileBulkPatchResult.SdkProfileBulkPatchItem(selector, null,
                        e.getReason().name().toLowerCase(Locale.ROOT)));
                continue;
            }
            if (profile.metadata.locked) {
                result.items.add(new SdkProfileBulkPatchResult.SdkProfileBulkPatchItem(selector, profile.id, "locked"));
                continue;
            }
            if (!request.dryRun) {
                applyPatch(profile, request);
                pendentes.add(profile);
            }
            result.items.add(new SdkProfileBulkPatchResult.SdkProfileBulkPatchItem(selector, profile.id,
                    request.dryRun ? "planned" : "updated"));
        }
        // Ja sob o lock (bulkPatch): valida tudo antes de gravar, entao falha
        // de validacao nao deixa nada gravado.
        if (!request.dryRun && !pendentes.isEmpty()) {
            catalog.validateStorageBatchUnlocked(pendentes);
            pendentes.forEach(catalog::saveUnlocked);
        }
        return result;
    }

    public SdkProfile trash(String selector) {
        SdkProfile profile = catalog.select(selector);
        if (profile.metadata.locked) {
            throw new ProfileCatalogException("Profile '" + profile.id + "' is locked");
        }
        profile.metadata.status = "deleted";
        profile.metadata.deletedAt = Instant.now().toString();
        profile.metadata.updatedAt = profile.metadata.deletedAt;
        return catalog.save(profile);
    }

    public SdkProfile restore(String selector) {
        SdkProfile profile = catalog.select(selector);
        profile.metadata.status = "active";
        profile.metadata.deletedAt = null;
        profile.metadata.updatedAt = Instant.now().toString();
        return catalog.save(profile);
    }

    public boolean purge(String selector) {
        SdkProfile profile = catalog.select(selector);
        if (!"deleted".equalsIgnoreCase(profile.metadata.status)) {
            throw new ProfileCatalogException("Profile '" + profile.id + "' is not in trash");
        }
        return catalog.delete(profile.id);
    }

    public void snapshot(Path destination) {
        catalog.writeJson(destination, catalog.list());
    }

    public List<SdkProfile> restoreSnapshot(Path source, boolean dryRun) {
        return restoreSnapshot(source, dryRun, ImportTrust.UNTRUSTED);
    }

    public List<SdkProfile> restoreSnapshot(Path source, boolean dryRun, ImportTrust trust) {
        Objects.requireNonNull(trust, "trust");
        try {
            // Barreira anti-OOM: rejeita arquivo grande antes de parsear.
            if (Files.size(source) > MAX_SNAPSHOT_BYTES) {
                throw new ProfileCatalogException("Profile snapshot " + source
                        + " exceeds size limit (" + MAX_SNAPSHOT_BYTES + " bytes)");
            }
            SdkProfile[] profiles = SdkProfileCatalog.OBJECT_MAPPER.readValue(source.toFile(), SdkProfile[].class);
            // Barreira anti-OOM: rejeita lote grande após parsear.
            if (profiles.length > MAX_SNAPSHOT_PROFILES) {
                throw new ProfileCatalogException("Profile snapshot " + source
                        + " exceeds profile limit (" + profiles.length + " > " + MAX_SNAPSHOT_PROFILES + ")");
            }
            List<SdkProfile> restored = new ArrayList<>();
            for (SdkProfile profile : profiles) {
                profile.id = SdkProfileCatalog.normalizeId(profile.id);
                profile.name = requireName(profile.name);
                profile.applyDefaults();
                if (trust == ImportTrust.UNTRUSTED) {
                    stripLaunchSurface(profile);
                }
                restored.add(profile);
            }
            SdkProfileCatalogLock.with(catalog.root, () -> {
                catalog.validateStorageBatchUnlocked(restored);
                if (!dryRun) {
                    restored.forEach(catalog::saveUnlocked);
                }
                return null;
            });
            return restored;
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to restore profile snapshot " + source, e);
        }
    }

    public void exportArchive(Path destination) {
        try {
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OwnerOnlyFiles.createOwnerOnly(destination);
            try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                Map<String, String> manifest = new LinkedHashMap<>();
                for (SdkProfile profile : catalog.list()) {
                    String id = SdkProfileCatalog.normalizeId(profile.id);
                    String entryName = "profiles/" + id + ".json";
                    byte[] json = toJson(profile).getBytes(StandardCharsets.UTF_8);
                    manifest.put(entryName, sha256(json));
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(json);
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("manifest.sha256"));
                for (Map.Entry<String, String> entry : manifest.entrySet()) {
                    zip.write((entry.getValue() + "  " + entry.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to export profile archive " + destination, e);
        }
    }

    public SdkProfileArchiveReport verifyArchive(Path source) {
        return readVerifiedArchive(source).report();
    }

    /** Relatório de verificação + bytes dos perfis já cobertos pelo checksum do manifest. */
    private record VerifiedArchive(SdkProfileArchiveReport report, List<byte[]> profileBytes) {}

    private VerifiedArchive readVerifiedArchive(Path source) {
        SdkProfileArchiveReport report = new SdkProfileArchiveReport();
        Set<String> seen = new HashSet<>();
        Map<String, String> checksums = new LinkedHashMap<>();
        Map<String, String> manifest = new LinkedHashMap<>();
        List<byte[]> profileBytes = new ArrayList<>();
        ArchiveBudget budget = new ArchiveBudget();
        try (InputStream input = Files.newInputStream(source);
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = readEntryBytes(zip, budget);
                if (entry.isDirectory()) {
                    continue;
                }
                if ("manifest.sha256".equals(name)) {
                    parseManifest(new String(bytes, StandardCharsets.UTF_8), manifest, report);
                    continue;
                }
                if (!name.startsWith("profiles/") || !name.endsWith(".json") || name.contains("..")) {
                    report.addError("Unsafe archive entry: " + name);
                    continue;
                }
                checksums.put(name, sha256(bytes));
                profileBytes.add(bytes);
                try {
                    SdkProfile profile = SdkProfileCatalog.OBJECT_MAPPER.readValue(bytes, SdkProfile.class);
                    addLaunchSurfaceWarnings(report, profile);
                } catch (IOException ignored) {
                    // Parse errors did not affect archive verification before launch-surface warnings.
                }
                String id = name.substring("profiles/".length(), name.length() - ".json".length());
                try {
                    SdkProfileCatalog.normalizeId(id);
                    if (!seen.add(id)) {
                        report.addError("Duplicate profile entry: " + id);
                    } else {
                        report.profileIds.add(id);
                    }
                } catch (IllegalArgumentException e) {
                    report.addError("Unsafe profile id: " + id);
                }
            }
            if (manifest.isEmpty() && !checksums.isEmpty()) {
                report.addError("Missing archive manifest");
            }
            for (Map.Entry<String, String> checksumEntry : checksums.entrySet()) {
                String expected = manifest.get(checksumEntry.getKey());
                if (expected == null) {
                    report.addError("Missing checksum for " + checksumEntry.getKey());
                } else if (!expected.equalsIgnoreCase(checksumEntry.getValue())) {
                    report.addError("Checksum mismatch for " + checksumEntry.getKey());
                }
            }
            for (String manifestPath : manifest.keySet()) {
                if (!checksums.containsKey(manifestPath)) {
                    report.addError("Manifest references missing entry: " + manifestPath);
                }
            }
            return new VerifiedArchive(report, profileBytes);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to verify profile archive " + source, e);
        }
    }

    public List<SdkProfile> importArchive(Path source, boolean dryRun) {
        return importArchive(source, dryRun, ImportTrust.UNTRUSTED);
    }

    public List<SdkProfile> importArchive(Path source, boolean dryRun, ImportTrust trust) {
        Objects.requireNonNull(trust, "trust");
        // Passe único: bytes já verificados contra o manifest; reler o zip reintroduziria TOCTOU entre os passes.
        VerifiedArchive verified = readVerifiedArchive(source);
        SdkProfileArchiveReport report = verified.report();
        if (!report.valid) {
            throw new ProfileCatalogException("Invalid profile archive: " + String.join("; ", report.errors));
        }
        List<SdkProfile> imported = new ArrayList<>();
        for (byte[] bytes : verified.profileBytes()) {
            SdkProfile profile;
            try {
                profile = SdkProfileCatalog.OBJECT_MAPPER.readValue(bytes, SdkProfile.class);
            } catch (IOException e) {
                throw new ProfileCatalogException("Failed to import profile archive " + source, e);
            }
            profile.id = SdkProfileCatalog.normalizeId(profile.id);
            profile.name = requireName(profile.name);
            profile.applyDefaults();
            if (trust == ImportTrust.UNTRUSTED) {
                stripLaunchSurface(profile);
            }
            imported.add(profile);
        }
        SdkProfileCatalogLock.with(catalog.root, () -> {
            catalog.validateStorageBatchUnlocked(imported);
            if (!dryRun) {
                imported.forEach(catalog::saveUnlocked);
            }
            return null;
        });
        return imported;
    }

    public void exportProfile(String id, Path destination) {
        SdkProfile profile = catalog.load(id).orElseThrow(() -> new ProfileSelectionException(
                ProfileSelectionException.Reason.MISSING,
                "No profile found for id '" + id + "'"));
        catalog.writeJson(destination, profile);
    }

    public SdkProfile importProfile(Path source) {
        return importProfile(source, ImportTrust.UNTRUSTED);
    }

    public SdkProfile importProfile(Path source, ImportTrust trust) {
        Objects.requireNonNull(trust, "trust");
        SdkProfile profile = catalog.readProfile(source, false);
        if (trust == ImportTrust.UNTRUSTED) {
            stripLaunchSurface(profile);
        }
        return catalog.save(profile);
    }

    private static void stripLaunchSurface(SdkProfile profile) {
        if (profile.browser != null && profile.browser.binaryPath != null
                && !profile.browser.binaryPath.isEmpty()) {
            profile.browser.binaryPath = null;
        }
        if (profile.browser != null && profile.browser.extraArgs != null
                && !profile.browser.extraArgs.isEmpty()) {
            // extraArgs reintroduz superfície de execução (ex.: --load-extension repovoa
            // as extensões removidas acima).
            profile.browser.extraArgs.clear();
        }
        if (profile.extensionPaths != null && !profile.extensionPaths.isEmpty()) {
            profile.extensionPaths.clear();
        }
        if (profile.resourcePolicy != null && profile.resourcePolicy.preferences != null
                && !profile.resourcePolicy.preferences.isEmpty()) {
            profile.resourcePolicy.preferences.clear();
        }
    }

    private static void addLaunchSurfaceWarnings(SdkProfileArchiveReport report, SdkProfile profile) {
        if (profile.browser != null && profile.browser.binaryPath != null
                && !profile.browser.binaryPath.isEmpty()) {
            report.addWarning("profile '" + profile.id + "': browser.binaryPath presente em perfil importado");
        }
        if (profile.extensionPaths != null && !profile.extensionPaths.isEmpty()) {
            report.addWarning("profile '" + profile.id + "': extensionPaths presente em perfil importado");
        }
        if (profile.resourcePolicy != null && profile.resourcePolicy.preferences != null
                && !profile.resourcePolicy.preferences.isEmpty()) {
            report.addWarning("profile '" + profile.id + "': resourcePolicy.preferences presente em perfil importado");
        }
    }

    public String toJson(SdkProfile profile) {
        Objects.requireNonNull(profile, "profile");
        profile.id = SdkProfileCatalog.normalizeId(profile.id);
        profile.name = requireName(profile.name);
        profile.applyDefaults();
        try {
            return SdkProfileCatalog.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to serialize profile '" + profile.id + "'", e);
        }
    }

    public SdkProfile fromJson(String json) {
        try {
            SdkProfile profile = SdkProfileCatalog.OBJECT_MAPPER.readValue(json, SdkProfile.class);
            profile.id = SdkProfileCatalog.normalizeId(profile.id);
            profile.name = requireName(profile.name);
            return profile.applyDefaults();
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to parse profile JSON", e);
        }
    }

}
