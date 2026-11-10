package io.github.carlosphenomenal.dr41.k6;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates and verifies the k6 binary. Handles: not installed, installed but not on PATH (GUI-launched apps often
 * get a minimal PATH), not executable, hanging/crashing binary, unparsable output, and versions that are too old.
 * A successful check is cached and re-validated cheaply (file still executable) on every use.
 */
@Component
public class K6Runtime {
    private static final Logger log = LoggerFactory.getLogger(K6Runtime.class);
    private static final Pattern VERSION = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final String DOCS = "https://grafana.com/docs/k6/latest/set-up/install-k6/";

    public record K6Info(Path executable, String version) {}

    public record Status(boolean available, String version, String executable,
                         String problemCode, String problem, List<String> hints) {}

    private final AppProperties props;
    private volatile K6Info cached;

    public K6Runtime(AppProperties props) { this.props = props; }

    /** @throws ApiException (503) with install guidance when k6 cannot be used */
    public K6Info require() {
        K6Info c = cached;
        if (c != null && Files.isExecutable(c.executable())) return c;
        K6Info fresh = resolve();
        cached = fresh;
        return fresh;
    }

    public Status status() {
        try {
            K6Info i = require();
            return new Status(true, i.version(), i.executable().toString(), null, null, List.of());
        } catch (ApiException e) {
            return new Status(false, null, null, e.getCode(), e.getMessage(), e.getDetails());
        }
    }

    private K6Info resolve() {
        List<Path> searched = new ArrayList<>();
        Path exe = locate(searched);
        if (exe == null) {
            List<String> details = new ArrayList<>(installHints());
            details.add("Looked in: " + searched.stream().limit(12).map(Path::toString).toList());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "K6_NOT_INSTALLED",
                    "k6 was not found on this machine, so load tests cannot run.", details);
        }
        String output = version(exe);
        Matcher m = VERSION.matcher(output);
        if (!m.find()) {
            log.warn("Could not parse k6 version from '{}'; continuing without a version check", output);
            return new K6Info(exe, "unknown");
        }
        String version = m.group(1) + "." + m.group(2) + "." + m.group(3);
        if (compare(version, props.k6().minVersion()) < 0) {
            List<String> d = new ArrayList<>();
            d.add("Found k6 " + version + " at " + exe + "; this tool needs " + props.k6().minVersion() + " or newer.");
            d.addAll(installHints());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "K6_UNSUPPORTED_VERSION",
                    "The installed k6 is too old.", d);
        }
        log.info("Using k6 {} at {}", version, exe);
        return new K6Info(exe, version);
    }

    private Path locate(List<Path> searched) {
        String configured = props.k6().executable();
        boolean windows = isWindows();
        List<Path> candidates = new ArrayList<>();
        if (configured.contains("/") || configured.contains("\\")) {
            add(candidates, configured);
        } else {
            String[] names = windows ? new String[]{configured + ".exe", configured} : new String[]{configured};
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (dir.isBlank()) continue;
                    for (String n : names) add(candidates, dir, n);
                }
            }
            if (configured.equals("k6")) {
                if (windows) {
                    String pf = System.getenv("ProgramFiles");
                    String local = System.getenv("LOCALAPPDATA");
                    if (pf != null) add(candidates, pf, "k6", "k6.exe");
                    if (local != null) add(candidates, local, "Microsoft", "WinGet", "Links", "k6.exe");
                } else {
                    for (String d : new String[]{"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin"}) add(candidates, d, "k6");
                }
            }
        }
        for (Path c : candidates) {
            searched.add(c);
            if (Files.isRegularFile(c) && Files.isExecutable(c)) return c.toAbsolutePath();
        }
        return null;
    }

    private static void add(List<Path> out, String first, String... more) {
        try { out.add(Path.of(first, more)); } catch (InvalidPathException ignored) { /* skip junk PATH entries */ }
    }

    private String version(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "version").redirectErrorStream(true).start();
            if (!p.waitFor(props.k6().versionCheckTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw unusable(exe, "'k6 version' did not respond within " + props.k6().versionCheckTimeout().toSeconds() + "s");
            }
            String out;
            try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (p.exitValue() != 0) throw unusable(exe, "'k6 version' exited with code " + p.exitValue() + ": " + out.strip());
            return out;
        } catch (IOException e) {
            throw unusable(exe, "could not be executed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unusable(exe, "check was interrupted");
        }
    }

    private ApiException unusable(Path exe, String why) {
        List<String> d = new ArrayList<>();
        d.add(exe + " " + why);
        d.add("Check file permissions and that the binary matches this OS/CPU architecture.");
        d.addAll(installHints());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "K6_UNUSABLE", "k6 was found but cannot be used.", d);
    }

    private static List<String> installHints() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> h = new ArrayList<>();
        if (os.contains("win")) {
            h.add("Windows: winget install k6 --source winget   (or: choco install k6)");
        } else if (os.contains("mac")) {
            h.add("macOS: brew install k6");
        } else {
            h.add("Linux: install the official package for your distro, or: sudo snap install k6");
        }
        h.add("Install guide: " + DOCS);
        h.add("If k6 is installed somewhere unusual, set K6_EXECUTABLE (or app.k6.executable) to its full path and retry.");
        return h;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
    }
}
