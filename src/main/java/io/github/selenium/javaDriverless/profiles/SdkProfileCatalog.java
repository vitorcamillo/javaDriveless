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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
public class SdkProfileCatalog {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROFILE_LEASES_DIRECTORY = ".profile-leases";
    final Path root;
    private SdkProfileCatalog(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }
    public static SdkProfileCatalog at(Path root) {
        return new SdkProfileCatalog(root);
    }
    public Path getRoot() {
        return root;
    }
    public SdkProfile create(String id, String name) {
        return SdkProfileCatalogLock.with(root, () -> {
            String normalizedId = normalizeId(id);
            rejectCaseFoldAlias(normalizedId);
            if (Files.exists(profilePath(normalizedId), LinkOption.NOFOLLOW_LINKS)) {
                throw new ProfileCatalogException("Profile '" + normalizedId + "' already exists");
            }
            SdkProfile profile = new SdkProfile(normalizedId, requireName(name));
            return saveUnlocked(profile);
        });
    }
    public SdkProfile save(SdkProfile profile) {
        return SdkProfileCatalogLock.with(root, () -> saveUnlocked(profile));
    }
    public void validateStorageOwnership(SdkProfile profile) {
        Objects.requireNonNull(profile, "profile");
        SdkProfileCatalogLock.with(root, () -> {
            profile.applyDefaults();
            validateStorageOwnershipUnlocked(profile, list());
            return null;
        });
    }
    LaunchOwnershipToken issueLaunchOwnershipToken(SdkProfile expected) {
        Objects.requireNonNull(expected, "profile");
        return SdkProfileCatalogLock.with(root, () -> {
            expected.id = normalizeId(expected.id);
            expected.applyDefaults();
            SdkProfile current = loadUnlocked(expected.id)
                    .orElseThrow(() -> new ProfileCatalogException(
                            "Catalog profile launch ownership changed: " + expected.id));
            if (!profileHash(expected).equals(profileHash(current))) {
                throw launchOwnershipChanged(expected.id);
            }
            LaunchOwnershipToken token = launchToken(current);
            validateStorageOwnershipUnlocked(current, list());
            return token;
        });
    }
    ChromeOptions.CatalogLaunchLease acquireLaunchOwnership(LaunchOwnershipToken token,
                                                            String optionsUserDataDir,
                                                            String optionsDownloadsDir) {
        Objects.requireNonNull(token, "launch ownership token");
        return SdkProfileCatalogLock.with(root, () -> {
            SdkProfile current = loadUnlocked(token.profileId)
                    .orElseThrow(() -> launchOwnershipChanged(token.profileId));
            if (!token.profileHash.equals(profileHash(current))) {
                throw launchOwnershipChanged(token.profileId);
            }
            requireLaunchTokenMatches(token, current.storage.userDataDir, current.storage.downloadsDir);
            requireLaunchTokenMatches(token, optionsUserDataDir, optionsDownloadsDir);
            validateStorageOwnershipUnlocked(current, list());
            ProfileStorageLeaseManager.Lease lease = ProfileStorageLeaseManager.acquire(
                    leaseDirectories(token.profileId, current));
            return lease::close;
        });
    }
    SdkProfile saveUnlocked(SdkProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return saveAllUnlocked(List.of(profile)).get(0);
    }

    /** Lista o catálogo uma única vez: lote de N perfis não reparseia N vezes. */
    List<SdkProfile> saveAllUnlocked(List<SdkProfile> profiles) {
        Map<String, SdkProfile> view = new LinkedHashMap<>();
        for (SdkProfile existing : list()) {
            view.put(existing.id, existing);
        }
        Set<String> fileNames = catalogFileNames();
        Map<SdkProfile, List<StorageClaim>> claims = new IdentityHashMap<>();
        for (SdkProfile profile : profiles) {
            Objects.requireNonNull(profile, "profile");
            profile.id = normalizeId(profile.id);
            profile.name = requireName(profile.name);
            profile.applyDefaults();
            String fileName = profile.id + ".json";
            if (fileNames.stream().anyMatch(
                    name -> name.equalsIgnoreCase(fileName) && !name.equals(fileName))) {
                throw new ProfileCatalogException(
                        "Profile '" + profile.id + "' colide por maiúsculas/minúsculas com outro ID");
            }
            validateStorageOwnershipUnlocked(profile, view.values(), claims);
            writeProfileUnlocked(profile, view.get(profile.id));
            view.put(profile.id, profile);
            fileNames.add(fileName);
        }
        return profiles;
    }

    private Set<String> catalogFileNames() {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(root)) {
            return names;
        }
        try (var entries = Files.list(root)) {
            entries.map(path -> path.getFileName().toString()).forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to list profiles in " + root, e);
        }
    }

    private void writeProfileUnlocked(SdkProfile profile, SdkProfile persisted) {
        try (ProfileStorageLeaseManager.Lease ignored = ProfileStorageLeaseManager.acquire(
                leaseDirectories(profile.id, persisted, profile))) {
            Files.createDirectories(root);
            Path target = profilePath(profile.id);
            Path temp = Files.createTempFile(root, "profile-" + profile.id + ".", ".tmp");
            try {
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), profile);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to save profile '" + profile.id + "'", e);
        }
    }
    public Optional<SdkProfile> load(String id) {
        return SdkProfileCatalogLock.with(root, () -> loadUnlocked(id));
    }
    private Optional<SdkProfile> loadUnlocked(String id) {
        String normalizedId = normalizeId(id);
        rejectCaseFoldAlias(normalizedId);
        Path path = profilePath(normalizedId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(readProfile(path, true));
    }
    public SdkProfile select(String selector) {
        return SdkProfileCatalogLock.with(root, () -> selectUnlocked(selector));
    }
    private SdkProfile selectUnlocked(String selector) {
        String requested = requireSelector(selector);
        if (isSafeId(requested) && hasExactProfileFile(requested)) {
            Optional<SdkProfile> byId = loadUnlocked(requested);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        List<SdkProfile> matches = list().stream()
                .filter(profile -> profile.name != null && profile.name.equalsIgnoreCase(requested))
                .toList();
        if (matches.isEmpty()) {
            throw new ProfileSelectionException(
                    ProfileSelectionException.Reason.MISSING,
                    "No profile found for selector '" + requested + "'");
        }
        if (matches.size() > 1) {
            throw new ProfileSelectionException(
                    ProfileSelectionException.Reason.AMBIGUOUS,
                    "Selector '" + requested + "' matches " + matches.size() + " profiles");
        }
        return matches.get(0);
    }
    private boolean hasExactProfileFile(String id) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        String expected = normalizeId(id) + ".json";
        try (var entries = Files.list(root)) {
            return entries.map(path -> path.getFileName().toString())
                    .anyMatch(expected::equals);
        } catch (IOException e) {
            throw new ProfileCatalogException(
                    "Failed to resolve exact profile id '" + id + "'", e);
        }
    }
    public List<SdkProfile> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            List<SdkProfile> profiles = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> profiles.add(readProfile(path, true)));
            return profiles;
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to list profiles in " + root, e);
        }
    }
    public boolean delete(String id) {
        return SdkProfileCatalogLock.with(root, () -> deleteUnlocked(id));
    }
    private boolean deleteUnlocked(String id) {
        String normalizedId = normalizeId(id);
        rejectCaseFoldAlias(normalizedId);
        SdkProfile persisted = loadUnlocked(normalizedId).orElse(null);
        try (ProfileStorageLeaseManager.Lease ignored = ProfileStorageLeaseManager.acquire(
                leaseDirectories(normalizedId, persisted))) {
            return Files.deleteIfExists(profilePath(normalizedId));
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to delete profile '" + normalizedId + "'", e);
        }
    }
    private SdkProfileCatalogWorkflows workflows() {
        return new SdkProfileCatalogWorkflows(this);
    }
    public SdkProfileCloneResult cloneProfiles(SdkProfileCloneRequest request) {
        return workflows().cloneProfiles(request);
    }
    public List<SdkProfile> list(SdkProfileListQuery query) {
        return workflows().list(query);
    }
    public SdkProfileBulkPatchResult bulkPatch(SdkProfileBulkPatchRequest request) {
        return workflows().bulkPatch(request);
    }
    public SdkProfile trash(String selector) { return workflows().trash(selector); }
    public SdkProfile restore(String selector) { return workflows().restore(selector); }
    public boolean purge(String selector) { return workflows().purge(selector); }
    public void snapshot(Path destination) { workflows().snapshot(destination); }
    /** Trata o arquivo como não confiável: remove binaryPath, extensionPaths e preferences. */
    public List<SdkProfile> restoreSnapshot(Path source, boolean dryRun) {
        return restoreSnapshot(source, dryRun, SdkProfileCatalogWorkflows.ImportTrust.UNTRUSTED);
    }
    public List<SdkProfile> restoreSnapshot(Path source, boolean dryRun,
                                            SdkProfileCatalogWorkflows.ImportTrust trust) {
        return workflows().restoreSnapshot(source, dryRun, trust);
    }
    public void exportArchive(Path destination) { workflows().exportArchive(destination); }
    public SdkProfileArchiveReport verifyArchive(Path source) { return workflows().verifyArchive(source); }
    /** Trata o arquivo como não confiável: remove binaryPath, extensionPaths e preferences. */
    public List<SdkProfile> importArchive(Path source, boolean dryRun) {
        return importArchive(source, dryRun, SdkProfileCatalogWorkflows.ImportTrust.UNTRUSTED);
    }
    public List<SdkProfile> importArchive(Path source, boolean dryRun,
                                          SdkProfileCatalogWorkflows.ImportTrust trust) {
        return workflows().importArchive(source, dryRun, trust);
    }
    public void exportProfile(String id, Path destination) { workflows().exportProfile(id, destination); }
    /** Trata o arquivo como não confiável: remove binaryPath, extensionPaths e preferences. */
    public SdkProfile importProfile(Path source) {
        return importProfile(source, SdkProfileCatalogWorkflows.ImportTrust.UNTRUSTED);
    }
    public SdkProfile importProfile(Path source, SdkProfileCatalogWorkflows.ImportTrust trust) {
        return workflows().importProfile(source, trust);
    }
    public String toJson(SdkProfile profile) { return workflows().toJson(profile); }
    public SdkProfile fromJson(String json) { return workflows().fromJson(json); }
    public static String normalizeId(String id) {
        String value = requireSelector(id);
        if (!isSafeId(value)) {
            throw new IllegalArgumentException("Unsafe profile id: " + value);
        }
        return value;
    }
    private static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !id.contains("..");
    }
    private Path profilePath(String id) {
        Path path = root.resolve(normalizeId(id) + ".json").normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Profile path escapes catalog root: " + id);
        }
        return path;
    }
    private void rejectCaseFoldAlias(String id) {
        if (!Files.isDirectory(root)) {
            return;
        }
        String expected = normalizeId(id) + ".json";
        try (var entries = Files.list(root)) {
            boolean alias = entries.map(path -> path.getFileName().toString())
                    .anyMatch(existing -> existing.equalsIgnoreCase(expected)
                            && !existing.equals(expected));
            if (alias) {
                throw new ProfileCatalogException(
                        "Profile '" + id + "' colide por maiúsculas/minúsculas com outro ID");
            }
        } catch (IOException e) {
            throw new ProfileCatalogException(
                    "Failed to validate case-fold aliases for profile '" + id + "'", e);
        }
    }
    SdkProfile readProfile(Path path, boolean requireInsideRoot) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (requireInsideRoot && !normalized.startsWith(root)) {
                throw new IllegalArgumentException("Profile path escapes catalog root: " + path);
            }
            SdkProfile profile = OBJECT_MAPPER.readValue(normalized.toFile(), SdkProfile.class);
            profile.id = normalizeId(profile.id);
            profile.name = requireName(profile.name);
            return profile.applyDefaults();
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to read profile " + path, e);
        }
    }
    SdkProfile parseProfile(byte[] json) throws IOException {
        SdkProfile profile = OBJECT_MAPPER.readValue(json, SdkProfile.class);
        profile.id = normalizeId(profile.id);
        profile.name = requireName(profile.name);
        return profile.applyDefaults();
    }
    void writeJson(Path destination, SdkProfile profile) {
        writeJson(destination, (Object) profile);
    }
    void writeJson(Path destination, Object value) {
        try {
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            rejectProxyCredentials(json);
            OwnerOnlyFiles.writeString(destination, json);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to write profile JSON " + destination, e);
        }
    }
    void rejectProxyCredentials(String json) {
        if (json.contains("\"username\"") || json.contains("\"password\"")) {
            throw new ProfileCatalogException("Profile exports must not contain proxy credentials");
        }
    }
    SdkProfile deepCopy(SdkProfile profile) {
        return fromJson(toJson(profile));
    }








    private void validateStorageOwnershipUnlocked(SdkProfile candidate, List<SdkProfile> catalogProfiles) {
        validateStorageOwnershipUnlocked(candidate, catalogProfiles, new IdentityHashMap<>());
    }

    /** {@code claims} evita repetir, no mesmo lote, a resolução em disco de um mesmo perfil. */
    private void validateStorageOwnershipUnlocked(SdkProfile candidate,
                                                  Collection<SdkProfile> catalogProfiles,
                                                  Map<SdkProfile, List<StorageClaim>> claims) {
        List<StorageClaim> occupied = new ArrayList<>();
        for (SdkProfile existing : catalogProfiles) {
            if (candidate.id.equals(existing.id)) {
                continue;
            }
            occupied.addAll(claims.computeIfAbsent(existing, SdkProfileCatalog::storageClaims));
        }
        rejectStorageCollision(candidate, "userDataDir", candidate.storage.userDataDir, occupied);
        rejectStorageCollision(candidate, "downloadsDir", candidate.storage.downloadsDir, occupied);
    }

    private static List<StorageClaim> storageClaims(SdkProfile profile) {
        List<StorageClaim> claims = new ArrayList<>(2);
        addStorageClaim(claims, profile.id, "userDataDir", profile.storage.userDataDir);
        addStorageClaim(claims, profile.id, "downloadsDir", profile.storage.downloadsDir);
        return claims;
    }

    void validateStorageBatchUnlocked(List<SdkProfile> candidates) {
        List<SdkProfile> virtualCatalog = new ArrayList<>(list());
        Map<SdkProfile, List<StorageClaim>> claims = new IdentityHashMap<>();
        for (SdkProfile candidate : candidates) {
            validateStorageOwnershipUnlocked(candidate, virtualCatalog, claims);
            virtualCatalog.removeIf(existing -> candidate.id.equals(existing.id));
            virtualCatalog.add(candidate);
        }
    }
    private static void addStorageClaim(List<StorageClaim> claims,
                                        String profileId,
                                        String field,
                                        String value) {
        SdkProfileStorageIdentity identity = storageIdentity(value, profileId, field);
        if (identity != null) {
            claims.add(new StorageClaim(profileId, field, identity));
        }
    }
    private static void rejectStorageCollision(SdkProfile candidate,
                                               String field,
                                               String value,
                                               List<StorageClaim> occupied) {
        SdkProfileStorageIdentity identity = storageIdentity(value, candidate.id, field);
        if (identity == null) {
            return;
        }
        for (StorageClaim claim : occupied) {
            if (identity.collidesWith(claim.identity)) {
                throw new ProfileCatalogException("Profile '" + candidate.id + "' storage " + field
                        + " collides with profile '" + claim.profileId + "' " + claim.field);
            }
        }
    }

    private static SdkProfileStorageIdentity storageIdentity(String value, String profileId, String field) {
        return SdkProfileStorageIdentity.resolve(value, profileId, field);
    }
    private static LaunchOwnershipToken launchToken(SdkProfile profile) {
        return new LaunchOwnershipToken(
                profile.id,
                profileHash(profile),
                storageIdentity(profile.storage.userDataDir, profile.id, "userDataDir"),
                storageIdentity(profile.storage.downloadsDir, profile.id, "downloadsDir"));
    }
    private static String profileHash(SdkProfile profile) {
        try {
            return sha256(OBJECT_MAPPER.writeValueAsBytes(profile));
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to hash catalog profile", e);
        }
    }
    private List<Path> leaseDirectories(String profileId, SdkProfile... profiles) {
        List<Path> directories = new ArrayList<>();
        directories.add(root.resolve(PROFILE_LEASES_DIRECTORY).resolve(normalizeId(profileId)));
        for (SdkProfile profile : profiles) {
            if (profile == null) continue;
            profile.applyDefaults();
            addLeaseDirectory(directories, profile.storage.userDataDir);
            addLeaseDirectory(directories, profile.storage.downloadsDir);
        }
        return directories;
    }
    private static void addLeaseDirectory(List<Path> directories, String value) {
        if (value != null && !value.isBlank()) {
            directories.add(Path.of(value).toAbsolutePath().normalize());
        }
    }
    private static void requireLaunchTokenMatches(LaunchOwnershipToken token,
                                                  String userDataDir,
                                                  String downloadsDir) {
        SdkProfileStorageIdentity currentUserData = storageIdentity(userDataDir, token.profileId, "userDataDir");
        SdkProfileStorageIdentity currentDownloads = storageIdentity(downloadsDir, token.profileId, "downloadsDir");
        if (!sameIdentity(token.userDataDir, currentUserData)
                || !sameIdentity(token.downloadsDir, currentDownloads)) {
            throw launchOwnershipChanged(token.profileId);
        }
    }
    private static boolean sameIdentity(SdkProfileStorageIdentity expected, SdkProfileStorageIdentity current) {
        if (expected == null || current == null) {
            return expected == current;
        }
        return expected.canonicalKey().equals(current.canonicalKey())
                && Objects.equals(expected.physicalKey(), current.physicalKey());
    }
    private static ProfileCatalogException launchOwnershipChanged(String profileId) {
        return new ProfileCatalogException("Catalog profile launch ownership changed: " + profileId);
    }
    static Path requireSafeStoragePath(String profileId, String field, String value) {
        storageIdentity(value, profileId, field);
        return Path.of(value).toAbsolutePath().normalize();
    }
    private record StorageClaim(String profileId, String field, SdkProfileStorageIdentity identity) {
    }

    static final class LaunchOwnershipToken {
        private final String profileId;
        private final String profileHash;
        private final SdkProfileStorageIdentity userDataDir;
        private final SdkProfileStorageIdentity downloadsDir;
        private LaunchOwnershipToken(String profileId,
                                     String profileHash,
                                     SdkProfileStorageIdentity userDataDir,
                                     SdkProfileStorageIdentity downloadsDir) {
            this.profileId = profileId;
            this.profileHash = profileHash;
            this.userDataDir = userDataDir;
            this.downloadsDir = downloadsDir;
        }
    }









}
