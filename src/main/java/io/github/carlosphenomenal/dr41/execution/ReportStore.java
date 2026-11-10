package io.github.carlosphenomenal.dr41.execution;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.report.TestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persists finished reports as one JSON file each (atomic write), so history survives restarts and the
 * in-memory registry can stay small. Reports are secret-free by construction (see PlanView).
 */
@Component
public class ReportStore {
    private static final Logger log = LoggerFactory.getLogger(ReportStore.class);
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final Path dir;

    public ReportStore(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.dir = props.storage().reportsPath();
    }

    public void save(TestReport report) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(report.id() + ".json.tmp");
        Path target = dir.resolve(report.id() + ".json");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            mapper.writeValue(out, report);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<TestReport> load(UUID id) {
        Path p = dir.resolve(id + ".json");
        if (!Files.isRegularFile(p)) return Optional.empty();
        try (InputStream in = Files.newInputStream(p)) {
            return Optional.of(mapper.readValue(in, TestReport.class));
        } catch (Exception e) {
            log.warn("Stored report {} is unreadable: {}", p, e.toString());
            return Optional.empty();
        }
    }

    public int purgeExpired() {
        if (!Files.isDirectory(dir)) return 0;
        Instant cutoff = Instant.now().minus(props.storage().reportRetention());
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".json") && !n.endsWith(".tmp")) continue;
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (IOException ignored) { /* best effort */ }
            }
        } catch (IOException e) {
            log.debug("Report purge skipped: {}", e.toString());
        }
        return deleted;
    }
}
