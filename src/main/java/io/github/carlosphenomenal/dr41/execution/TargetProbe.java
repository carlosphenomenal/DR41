package io.github.carlosphenomenal.dr41.execution;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.plan.ResolvedEndpoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Cheap pre-flight: DNS + TCP connect per unique host:port, in parallel. No HTTP request is sent, so
 * non-idempotent endpoints are never touched and the target's own metrics stay clean.
 * Catches typos and down services in ~3s instead of producing a 100%-error "load test".
 * Disable with app.safety.preflight-targets=false (e.g. when traffic must go through an HTTP proxy).
 */
@Component
public class TargetProbe {
    private final ExecutorService executor;
    private final AppProperties props;

    public TargetProbe(@Qualifier("runExecutor") ExecutorService executor, AppProperties props) {
        this.executor = executor;
        this.props = props;
    }

    public List<String> unreachable(Collection<ResolvedEndpoint> endpoints) throws InterruptedException {
        if (!props.safety().preflightTargets()) return List.of();
        Map<String, Object[]> targets = new LinkedHashMap<>();
        for (ResolvedEndpoint ep : endpoints) {
            URI u = ep.uri();
            int port = u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            targets.putIfAbsent(u.getHost().toLowerCase(Locale.ROOT) + ":" + port, new Object[]{u.getHost(), port});
        }
        List<Callable<String>> tasks = new ArrayList<>();
        targets.forEach((key, hp) -> tasks.add(() -> check((String) hp[0], (Integer) hp[1])));
        List<String> problems = new ArrayList<>();
        for (Future<String> f : executor.invokeAll(tasks)) {
            try {
                String p = f.get();
                if (p != null) problems.add(p);
            } catch (ExecutionException e) {
                problems.add("Probe failed: " + e.getCause());
            }
        }
        return problems;
    }

    private String check(String host, int port) {
        int timeout = (int) props.safety().connectTimeout().toMillis();
        try {
            InetAddress addr = InetAddress.getByName(host);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(addr, port), timeout);
            }
            return null;
        } catch (UnknownHostException e) {
            return host + ": DNS lookup failed (unknown host)";
        } catch (SocketTimeoutException e) {
            return host + ":" + port + ": connection timed out after " + timeout + " ms";
        } catch (ConnectException e) {
            return host + ":" + port + ": connection refused or network unreachable (" + e.getMessage() + ")";
        } catch (IOException e) {
            return host + ":" + port + ": " + e.getMessage();
        }
    }
}
