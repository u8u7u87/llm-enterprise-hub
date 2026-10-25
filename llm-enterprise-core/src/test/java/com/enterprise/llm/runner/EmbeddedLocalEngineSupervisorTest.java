package com.enterprise.llm.runner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedLocalEngineSupervisorTest {

    @Test
    void shouldNotLaunchWhenDisabled() {
        EmbeddedRunnerProperties props = new EmbeddedRunnerProperties();
        props.setEnabled(false);

        AtomicBoolean launched = new AtomicBoolean(false);
        EmbeddedLocalEngineSupervisor supervisor = new EmbeddedLocalEngineSupervisor(props, cmd -> {
            launched.set(true);
            return new MockProcess();
        });

        supervisor.start();
        assertFalse(launched.get());
        assertFalse(supervisor.isRunning());
    }

    @Test
    void shouldBuildLlamaServerCommandProperly() {
        EmbeddedRunnerProperties props = new EmbeddedRunnerProperties();
        props.setEnabled(true);
        props.setBinaryPath("llama-server");
        props.setModelPath("/models/qwen2.5-0.5b.gguf");
        props.setPort(9090);
        props.setContextSize(2048);
        props.setGpuLayers(28);
        props.setHealthTimeoutSeconds(0); // instant timeout to avoid blocking

        List<String> capturedCmd = new ArrayList<>();
        EmbeddedLocalEngineSupervisor supervisor = new EmbeddedLocalEngineSupervisor(props, cmd -> {
            capturedCmd.addAll(cmd);
            return new MockProcess();
        });

        supervisor.start();
        assertTrue(supervisor.isRunning());
        assertEquals("http://localhost:9090/v1", supervisor.getLocalBaseUrl());

        assertTrue(capturedCmd.contains("llama-server"));
        assertTrue(capturedCmd.contains("-m"));
        assertTrue(capturedCmd.contains("/models/qwen2.5-0.5b.gguf"));
        assertTrue(capturedCmd.contains("--port"));
        assertTrue(capturedCmd.contains("9090"));
        assertTrue(capturedCmd.contains("-c"));
        assertTrue(capturedCmd.contains("2048"));
        assertTrue(capturedCmd.contains("-ngl"));
        assertTrue(capturedCmd.contains("28"));

        supervisor.stop();
        assertFalse(supervisor.isRunning());
    }

    @Test
    void shouldBuildOllamaCommandProperly() {
        EmbeddedRunnerProperties props = new EmbeddedRunnerProperties();
        props.setEnabled(true);
        props.setBinaryPath("ollama");
        props.setHealthTimeoutSeconds(0);

        List<String> capturedCmd = new ArrayList<>();
        EmbeddedLocalEngineSupervisor supervisor = new EmbeddedLocalEngineSupervisor(props, cmd -> {
            capturedCmd.addAll(cmd);
            return new MockProcess();
        });

        supervisor.start();
        assertTrue(capturedCmd.contains("ollama"));
        assertTrue(capturedCmd.contains("serve"));

        supervisor.stop();
    }

    static class MockProcess extends Process {
        private boolean alive = true;

        @Override
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        @Override
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }

        @Override
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }

        @Override
        public int waitFor() { return 0; }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            this.alive = false;
            return true;
        }

        @Override
        public int exitValue() { return 0; }

        @Override
        public void destroy() { this.alive = false; }

        @Override
        public boolean isAlive() { return alive; }

        @Override
        public long pid() { return 12345L; }
    }
}
