package com.enterprise.llm.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for embedded local LLM process supervisor.
 * Allows LLM Enterprise Hub to automatically spawn, supervise, and cleanly terminate
 * a local lightweight inference engine (e.g., llama-server or ollama) without Ray/vLLM.
 */
@ConfigurationProperties(prefix = "llm.embedded-runner")
public class EmbeddedRunnerProperties {

    /**
     * Whether to enable automatic management of local LLM inference process.
     */
    private boolean enabled = false;

    /**
     * Path to the local binary executable (e.g. "llama-server", "ollama", "/usr/local/bin/llama-server").
     */
    private String binaryPath = "llama-server";

    /**
     * Path to the local model GGUF file (e.g. "/models/qwen2.5-0.5b-instruct.gguf").
     */
    private String modelPath = "";

    /**
     * Local port for the inference engine to bind to (e.g. 8081).
     */
    private int port = 8081;

    /**
     * Number of layers to offload to GPU (0 for pure CPU mode).
     */
    private int gpuLayers = 0;

    /**
     * Context window size (in tokens).
     */
    private int contextSize = 4096;

    /**
     * Maximum seconds to wait for the local engine to become healthy before timing out.
     */
    private int healthTimeoutSeconds = 15;

    /**
     * Additional command line arguments to pass to the engine binary.
     */
    private String additionalArgs = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getGpuLayers() {
        return gpuLayers;
    }

    public void setGpuLayers(int gpuLayers) {
        this.gpuLayers = gpuLayers;
    }

    public int getContextSize() {
        return contextSize;
    }

    public void setContextSize(int contextSize) {
        this.contextSize = contextSize;
    }

    public int getHealthTimeoutSeconds() {
        return healthTimeoutSeconds;
    }

    public void setHealthTimeoutSeconds(int healthTimeoutSeconds) {
        this.healthTimeoutSeconds = healthTimeoutSeconds;
    }

    public String getAdditionalArgs() {
        return additionalArgs;
    }

    public void setAdditionalArgs(String additionalArgs) {
        this.additionalArgs = additionalArgs;
    }
}
