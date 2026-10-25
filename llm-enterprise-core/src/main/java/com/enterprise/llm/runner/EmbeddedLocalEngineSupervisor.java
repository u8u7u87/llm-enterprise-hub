package com.enterprise.llm.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Embedded local LLM inference engine process supervisor.
 * Automatically spawns, health-checks, monitors, and cleanly terminates
 * a local lightweight LLM process (e.g. llama-server or ollama).
 */
public class EmbeddedLocalEngineSupervisor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedLocalEngineSupervisor.class);

    private final EmbeddedRunnerProperties properties;
    private final Function<List<String>, Process> processLauncher;
    private Process managedProcess;
    private volatile boolean healthy = false;

    public EmbeddedLocalEngineSupervisor(
            EmbeddedRunnerProperties properties,
            Function<List<String>, Process> processLauncher
    ) {
        this.properties = properties != null ? properties : new EmbeddedRunnerProperties();
        this.processLauncher = processLauncher != null ? processLauncher : this::defaultLaunch;
    }

    public EmbeddedLocalEngineSupervisor(EmbeddedRunnerProperties properties) {
        this(properties, null);
    }

    public synchronized void start() {
        if (!properties.isEnabled()) {
            log.debug("Embedded local LLM runner is disabled");
            return;
        }

        List<String> command = buildCommand();
        log.info("Spawning embedded local LLM inference engine: {}", command);

        try {
            this.managedProcess = processLauncher.apply(command);
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "embedded-llm-shutdown-hook"));
            pollHealthUntilReady();
        } catch (Exception e) {
            log.error("Failed to start embedded local LLM process: {}", e.getMessage(), e);
            stop();
        }
    }

    public synchronized void stop() {
        if (managedProcess != null && managedProcess.isAlive()) {
            log.info("Gracefully stopping embedded local LLM process (PID={})...", managedProcess.pid());
            managedProcess.destroy();
            try {
                if (!managedProcess.waitFor(3, TimeUnit.SECONDS)) {
                    log.warn("Process did not exit within 3s, destroying forcibly...");
                    managedProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                managedProcess.destroyForcibly();
            }
            managedProcess = null;
            healthy = false;
        }
    }

    public boolean isRunning() {
        return managedProcess != null && managedProcess.isAlive();
    }

    public boolean isHealthy() {
        return isRunning() && healthy;
    }

    public Process getProcess() {
        return managedProcess;
    }

    public String getLocalBaseUrl() {
        return "http://localhost:" + properties.getPort() + "/v1";
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getBinaryPath());

        String binary = properties.getBinaryPath().toLowerCase();
        if (binary.contains("llama-server") || binary.contains("server")) {
            if (properties.getModelPath() != null && !properties.getModelPath().isBlank()) {
                cmd.add("-m");
                cmd.add(properties.getModelPath());
            }
            cmd.add("--port");
            cmd.add(String.valueOf(properties.getPort()));
            cmd.add("-c");
            cmd.add(String.valueOf(properties.getContextSize()));
            cmd.add("-ngl");
            cmd.add(String.valueOf(properties.getGpuLayers()));
        } else if (binary.contains("ollama")) {
            cmd.add("serve");
        }

        if (properties.getAdditionalArgs() != null && !properties.getAdditionalArgs().isBlank()) {
            for (String arg : properties.getAdditionalArgs().trim().split("\\s+")) {
                if (!arg.isBlank()) {
                    cmd.add(arg);
                }
            }
        }
        return cmd;
    }

    private Process defaultLaunch(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Cannot launch executable " + cmd.get(0) + ": " + e.getMessage(), e);
        }
    }

    private void pollHealthUntilReady() {
        int timeoutSec = Math.max(1, properties.getHealthTimeoutSeconds());
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        String probeUrl = "http://localhost:" + properties.getPort() + "/health";

        while (System.currentTimeMillis() < deadline) {
            if (managedProcess == null || !managedProcess.isAlive()) {
                log.warn("Embedded LLM process terminated prematurely");
                healthy = false;
                return;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(probeUrl))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    healthy = true;
                    log.info("Embedded local LLM engine is ready on port {}", properties.getPort());
                    return;
                }
            } catch (Exception ignored) {
                // not ready yet
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Embedded local LLM health check timed out after {}s (process still alive)", timeoutSec);
    }
}
