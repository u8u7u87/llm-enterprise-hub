package com.enterprise.llm.backend;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.enterprise.llm.backend.adapter.DirectInferenceAdapter;
import com.enterprise.llm.backend.adapter.RayServeInferenceAdapter;
import com.enterprise.llm.backend.adapter.SglangInferenceAdapter;
import com.enterprise.llm.backend.adapter.TritonInferenceAdapter;
import com.enterprise.llm.backend.adapter.VllmInferenceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry and dispatching factory for pluggable inference backend adapters.
 * Manages built-in adapters (DIRECT, VLLM, SGLANG, TRITON, RAY) and dynamically
 * registered 3rd-party custom adapters.
 */
public class InferenceBackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(InferenceBackendRegistry.class);

    private final Map<String, InferenceBackendAdapter> adapters = new ConcurrentHashMap<>();
    private final String defaultEngine;

    public InferenceBackendRegistry(List<InferenceBackendAdapter> adapterList, String defaultEngine) {
        this.defaultEngine = defaultEngine != null ? defaultEngine.toLowerCase().trim() : "direct";

        // Register standard defaults first
        registerAdapter(new DirectInferenceAdapter());
        registerAdapter(new VllmInferenceAdapter());
        registerAdapter(new SglangInferenceAdapter());
        registerAdapter(new TritonInferenceAdapter());
        registerAdapter(new RayServeInferenceAdapter());

        // Then register any custom or injected adapters (can override defaults)
        if (adapterList != null) {
            for (InferenceBackendAdapter adapter : adapterList) {
                registerAdapter(adapter);
            }
        }
    }

    public InferenceBackendRegistry(String defaultEngine) {
        this(Collections.emptyList(), defaultEngine);
    }

    public InferenceBackendRegistry() {
        this(Collections.emptyList(), "direct");
    }

    public void registerAdapter(InferenceBackendAdapter adapter) {
        if (adapter != null && adapter.getEngineType() != null) {
            String key = adapter.getEngineType().toLowerCase().trim();
            adapters.put(key, adapter);
            log.info("Registered InferenceBackendAdapter for engine [{}] -> {}", key, adapter.getClass().getSimpleName());
        }
    }

    public InferenceBackendAdapter getAdapter(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            engineType = defaultEngine;
        }
        String key = engineType.toLowerCase().trim();
        InferenceBackendAdapter adapter = adapters.get(key);
        if (adapter == null) {
            log.warn("No InferenceBackendAdapter found for engine [{}], falling back to default [{}]", engineType, defaultEngine);
            adapter = adapters.get(defaultEngine);
        }
        return adapter != null ? adapter : adapters.get("direct");
    }

    public InferenceBackendAdapter getDefaultAdapter() {
        return getAdapter(defaultEngine);
    }

    public String getDefaultEngine() {
        return defaultEngine;
    }

    public Collection<InferenceBackendAdapter> getAllAdapters() {
        return Collections.unmodifiableCollection(adapters.values());
    }
}
