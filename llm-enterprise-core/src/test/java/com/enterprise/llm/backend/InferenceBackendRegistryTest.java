package com.enterprise.llm.backend;

import java.net.http.HttpClient;
import com.enterprise.llm.backend.adapter.DirectInferenceAdapter;
import com.enterprise.llm.backend.adapter.RayServeInferenceAdapter;
import com.enterprise.llm.backend.adapter.SglangInferenceAdapter;
import com.enterprise.llm.backend.adapter.TritonInferenceAdapter;
import com.enterprise.llm.backend.adapter.VllmInferenceAdapter;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.RoundRobinLoadBalancer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InferenceBackendRegistryTest {

    @Test
    void shouldResolveBuiltInAdaptersCaseInsensitively() {
        InferenceBackendRegistry registry = new InferenceBackendRegistry("direct");

        assertInstanceOf(DirectInferenceAdapter.class, registry.getAdapter("direct"));
        assertInstanceOf(DirectInferenceAdapter.class, registry.getAdapter("DIRECT"));
        assertInstanceOf(VllmInferenceAdapter.class, registry.getAdapter("vllm"));
        assertInstanceOf(VllmInferenceAdapter.class, registry.getAdapter("VLLM"));
        assertInstanceOf(SglangInferenceAdapter.class, registry.getAdapter("sglang"));
        assertInstanceOf(SglangInferenceAdapter.class, registry.getAdapter("SgLang"));
        assertInstanceOf(TritonInferenceAdapter.class, registry.getAdapter("triton"));
        assertInstanceOf(TritonInferenceAdapter.class, registry.getAdapter("TRITON"));
        assertInstanceOf(RayServeInferenceAdapter.class, registry.getAdapter("ray"));
        assertInstanceOf(RayServeInferenceAdapter.class, registry.getAdapter("RAY"));
    }

    @Test
    void shouldFallbackToDefaultWhenEngineNotFound() {
        InferenceBackendRegistry registry = new InferenceBackendRegistry("vllm");

        assertEquals("vllm", registry.getDefaultEngine());
        InferenceBackendAdapter defaultAdapter = registry.getDefaultAdapter();
        assertInstanceOf(VllmInferenceAdapter.class, defaultAdapter);

        InferenceBackendAdapter unknown = registry.getAdapter("non_existing_engine_foo");
        assertInstanceOf(VllmInferenceAdapter.class, unknown);
    }

    @Test
    void shouldSupportCustomThirdPartyAdapterExtension() {
        InferenceBackendRegistry registry = new InferenceBackendRegistry("direct");

        InferenceBackendAdapter customAdapter = new InferenceBackendAdapter() {
            @Override
            public String getEngineType() {
                return "tgi";
            }

            @Override
            public ClusterLoadBalancer createDefaultLoadBalancer() {
                return new RoundRobinLoadBalancer();
            }

            @Override
            public boolean checkHealth(UpstreamNode node, HttpClient httpClient) {
                return true;
            }
        };

        registry.registerAdapter(customAdapter);

        InferenceBackendAdapter resolved = registry.getAdapter("tgi");
        assertNotNull(resolved);
        assertEquals("tgi", resolved.getEngineType());
        assertInstanceOf(RoundRobinLoadBalancer.class, resolved.createDefaultLoadBalancer());
    }

    @Test
    void shouldParseEngineTypeEnum() {
        assertEquals(EngineType.DIRECT, EngineType.fromCode("direct"));
        assertEquals(EngineType.DIRECT, EngineType.fromCode("DIRECT"));
        assertEquals(EngineType.VLLM, EngineType.fromCode("vllm"));
        assertEquals(EngineType.SGLANG, EngineType.fromCode("sglang"));
        assertEquals(EngineType.TRITON, EngineType.fromCode("triton"));
        assertEquals(EngineType.RAY, EngineType.fromCode("ray"));
        assertEquals(EngineType.CUSTOM, EngineType.fromCode("my-custom-engine"));
        assertEquals(EngineType.DIRECT, EngineType.fromCode(null));
        assertEquals(EngineType.DIRECT, EngineType.fromCode(""));
    }
}
