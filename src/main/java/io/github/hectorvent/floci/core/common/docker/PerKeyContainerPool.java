package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily starts, health-checks, and reuses one container per arbitrary key. Pulled out of
 * {@code VerdaccioSidecarManager} (one Verdaccio container per CodeArtifact npm repository) since
 * that lifecycle logic (map of running containers, per-key start lock so concurrent first-use of
 * two different keys never blocks on each other, health poll, restart-on-unhealthy, stop-all on
 * shutdown) has nothing Verdaccio-specific in it; only how to actually build and start a container
 * for a given key does. A future per-repository sidecar (pypiserver, BaGet) can reuse this
 * directly instead of re-deriving the same map/lock/poll skeleton.
 *
 * <p>Callers own the health-check path and the actual container creation (image, config, env),
 * supplied per call since those are exactly the format-specific parts.
 */
public class PerKeyContainerPool {

    private static final Logger LOG = Logger.getLogger(PerKeyContainerPool.class);
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;

    /** What a {@link Starter} hands back once its container is created and started. */
    public record StartedContainer(String containerId, String baseUrl) {}

    /** Builds and starts a fresh container for the key {@link #ensureReady} was called with. */
    @FunctionalInterface
    public interface Starter {
        StartedContainer start();
    }

    private final ContainerLifecycleManager lifecycleManager;
    private final String healthPath;
    private final ConcurrentHashMap<String, StartedContainer> containers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

    /**
     * @param healthPath path (e.g. {@code /-/ping}) appended to a container's base URL to probe
     *                   readiness; expected to return HTTP 200 once the container can serve
     */
    public PerKeyContainerPool(ContainerLifecycleManager lifecycleManager, String healthPath) {
        this.lifecycleManager = lifecycleManager;
        this.healthPath = healthPath;
    }

    /**
     * Base URL of a ready container for {@code key}, starting one via {@code starter} if this is
     * the first use or the previous container is no longer healthy. Concurrent calls for
     * different keys never block on each other; concurrent calls for the same key serialize so
     * only one container is ever started for it.
     */
    public String ensureReady(String key, Starter starter) {
        StartedContainer existing = containers.get(key);
        if (existing != null && probeHealth(existing.baseUrl())) {
            return existing.baseUrl();
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = containers.get(key);
            if (existing != null && probeHealth(existing.baseUrl())) {
                return existing.baseUrl();
            }
            if (existing != null) {
                LOG.warnv("Sidecar container for key {0} is no longer healthy; restarting it", key);
                lifecycleManager.stopAndRemove(existing.containerId(), null);
            }
            StartedContainer started = starter.start();
            waitForHealth(started.baseUrl());
            containers.put(key, started);
            return started.baseUrl();
        }
    }

    /** Stops and removes the container for one key, if one was ever started. No-op otherwise. */
    public void stopContainer(String key) {
        if (key == null) {
            return;
        }
        StartedContainer container = containers.remove(key);
        if (container != null) {
            lifecycleManager.stopAndRemove(container.containerId(), null);
        }
    }

    /** Stops and removes every container this pool has started, then forgets them all. */
    public void stopAll() {
        if (containers.isEmpty()) {
            return;
        }
        LOG.infov("Stopping {0} pooled sidecar container(s)", containers.size());
        containers.forEach((key, container) -> {
            try {
                lifecycleManager.stopAndRemove(container.containerId(), null);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to stop pooled sidecar container for key {0}", key);
            }
        });
        containers.clear();
    }

    private boolean probeHealth(String baseUrl) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + healthPath).toURL()
                    .openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            LOG.debugv(e, "Sidecar health probe failed for {0}", baseUrl);
            return false;
        }
    }

    private void waitForHealth(String baseUrl) {
        long deadline = System.currentTimeMillis() + HEALTH_POLL_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            if (probeHealth(baseUrl)) {
                return;
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for sidecar container", e);
            }
        }
        throw new IllegalStateException("Sidecar container did not become healthy within " + HEALTH_POLL_MAX_MS
                + " ms");
    }
}
