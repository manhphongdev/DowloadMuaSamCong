package vn.muasamcong.downloader.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.util.Utils;

public final class JsonPackageRepository implements PackageRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int SCHEMA_VERSION = 1;
    private static final Path STORAGE_FILE = Utils.dataFile("bid_packages.json");
    private final Object lock = new Object();

    @Override
    public Optional<BidPackage> findById(String id) {
        if (isBlank(id)) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(loadInternal().get(id));
        }
    }

    @Override
    public List<BidPackage> findAll() {
        synchronized (lock) {
            return List.copyOf(loadInternal().values());
        }
    }

    @Override
    public void save(BidPackage pkg) {
        if (pkg == null || isBlank(pkg.id())) {
            return;
        }
        synchronized (lock) {
            LinkedHashMap<String, BidPackage> packages = loadInternal();
            packages.put(pkg.id(), pkg);
            saveInternal(packages);
        }
    }

    @Override
    public void saveAll(List<BidPackage> packages) {
        if (packages == null || packages.isEmpty()) {
            return;
        }
        synchronized (lock) {
            LinkedHashMap<String, BidPackage> existing = loadInternal();
            for (BidPackage pkg : packages) {
                if (pkg != null && !isBlank(pkg.id())) {
                    existing.put(pkg.id(), pkg);
                }
            }
            saveInternal(existing);
        }
    }

    @Override
    public int deleteByIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            LinkedHashMap<String, BidPackage> packages = loadInternal();
            int before = packages.size();
            packages.keySet().removeIf(ids::contains);
            int removed = before - packages.size();
            if (removed > 0) {
                saveOrDeleteInternal(packages);
            }
            return removed;
        }
    }

    private LinkedHashMap<String, BidPackage> loadInternal() {
        if (!Files.exists(STORAGE_FILE)) {
            LinkedHashMap<String, BidPackage> migrated = PackageStoreMigration.migrateFromLegacyStores();
            if (!migrated.isEmpty()) {
                saveInternal(migrated);
            }
            return migrated;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(STORAGE_FILE));
            JsonNode recordsNode = root == null ? null : root.get("packages");
            LinkedHashMap<String, BidPackage> packages = new LinkedHashMap<>();
            if (recordsNode == null || !recordsNode.isArray()) {
                return packages;
            }
            for (JsonNode node : recordsNode) {
                BidPackage pkg = MAPPER.treeToValue(node, BidPackage.class);
                if (pkg != null && !isBlank(pkg.id())) {
                    packages.put(pkg.id(), pkg);
                }
            }
            return packages;
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read bid packages: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private void saveOrDeleteInternal(LinkedHashMap<String, BidPackage> packages) {
        if (packages.isEmpty()) {
            try {
                Files.deleteIfExists(STORAGE_FILE);
            } catch (IOException ex) {
                throw new RuntimeException("Unable to update bid packages: " + STORAGE_FILE.toAbsolutePath(), ex);
            }
            return;
        }
        saveInternal(packages);
    }

    private void saveInternal(LinkedHashMap<String, BidPackage> packages) {
        Utils.ensureDirectory(STORAGE_FILE.toAbsolutePath().getParent());
        Payload payload = new Payload(
            SCHEMA_VERSION,
            LocalDateTime.now().format(TS),
            new ArrayList<>(packages.values())
        );
        try {
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(STORAGE_FILE, content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write bid packages: " + STORAGE_FILE.toAbsolutePath(), ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Payload(int schemaVersion, String updatedAt, List<BidPackage> packages) {
    }
}
