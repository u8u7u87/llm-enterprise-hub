package com.enterprise.llm.cluster.discovery;

import com.enterprise.llm.cluster.UpstreamNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterDiscoveryTest {

    @Nested
    @DisplayName("DnsClusterDiscoveryProvider Tests")
    class DnsClusterDiscoveryProviderTests {

        @Test
        @DisplayName("Should discover nodes from target with custom DNS resolver")
        void shouldDiscoverNodesWithCustomDnsResolver() throws UnknownHostException {
            InetAddress ip1 = InetAddress.getByName("10.244.1.15");
            InetAddress ip2 = InetAddress.getByName("10.244.2.28");

            DnsClusterDiscoveryProvider provider = new DnsClusterDiscoveryProvider(
                    8000,
                    host -> host.equals("vllm-headless.cluster.local") ? List.of(ip1, ip2) : Collections.emptyList()
            );

            assertThat(provider.getProviderType()).isEqualTo("DNS");

            List<UpstreamNode> nodes = provider.discoverNodes("vllm-headless.cluster.local:8080");
            assertThat(nodes).hasSize(2);

            UpstreamNode node1 = nodes.get(0);
            assertThat(node1.getId()).isEqualTo("dns-10.244.1.15:8080");
            assertThat(node1.getBaseUrl()).isEqualTo("http://10.244.1.15:8080/v1");
            assertThat(node1.isHealthy()).isTrue();

            UpstreamNode node2 = nodes.get(1);
            assertThat(node2.getId()).isEqualTo("dns-10.244.2.28:8080");
            assertThat(node2.getBaseUrl()).isEqualTo("http://10.244.2.28:8080/v1");
            assertThat(node2.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Should use default port 8000 when port is omitted in target")
        void shouldUseDefaultPortWhenOmitted() throws UnknownHostException {
            InetAddress ip = InetAddress.getByName("192.168.1.100");
            DnsClusterDiscoveryProvider provider = new DnsClusterDiscoveryProvider(
                    host -> List.of(ip)
            );

            List<UpstreamNode> nodes = provider.discoverNodes("vllm-service");
            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getId()).isEqualTo("dns-192.168.1.100:8000");
            assertThat(nodes.get(0).getBaseUrl()).isEqualTo("http://192.168.1.100:8000/v1");
        }

        @Test
        @DisplayName("Should simulate dynamic Pod IP additions and removals")
        void shouldSimulatePodIpAdditionsAndRemovals() throws UnknownHostException {
            InetAddress pod1 = InetAddress.getByName("10.0.0.1");
            InetAddress pod2 = InetAddress.getByName("10.0.0.2");
            InetAddress pod3 = InetAddress.getByName("10.0.0.3");

            AtomicReference<List<InetAddress>> currentPods = new AtomicReference<>(List.of(pod1, pod2));
            DnsClusterDiscoveryProvider provider = new DnsClusterDiscoveryProvider(
                    host -> currentPods.get()
            );

            // Phase 1: 2 pods initially
            List<UpstreamNode> nodesPhase1 = provider.discoverNodes("vllm-headless:8000");
            assertThat(nodesPhase1).extracting(UpstreamNode::getId)
                    .containsExactly("dns-10.0.0.1:8000", "dns-10.0.0.2:8000");

            // Phase 2: Pod 3 scale out
            currentPods.set(List.of(pod1, pod2, pod3));
            List<UpstreamNode> nodesPhase2 = provider.discoverNodes("vllm-headless:8000");
            assertThat(nodesPhase2).extracting(UpstreamNode::getId)
                    .containsExactly("dns-10.0.0.1:8000", "dns-10.0.0.2:8000", "dns-10.0.0.3:8000");

            // Phase 3: Pod 1 terminated (scale in)
            currentPods.set(List.of(pod2, pod3));
            List<UpstreamNode> nodesPhase3 = provider.discoverNodes("vllm-headless:8000");
            assertThat(nodesPhase3).extracting(UpstreamNode::getId)
                    .containsExactly("dns-10.0.0.2:8000", "dns-10.0.0.3:8000");
        }

        @Test
        @DisplayName("Should handle empty, blank, or invalid inputs gracefully")
        void shouldHandleInvalidInputsGracefully() {
            DnsClusterDiscoveryProvider provider = new DnsClusterDiscoveryProvider(
                    host -> {
                        if (host.equals("failing-dns")) {
                            throw new RuntimeException("DNS server timeout");
                        }
                        return Collections.emptyList();
                    }
            );

            assertThat(provider.discoverNodes(null)).isEmpty();
            assertThat(provider.discoverNodes("")).isEmpty();
            assertThat(provider.discoverNodes("   ")).isEmpty();
            assertThat(provider.discoverNodes("host:invalid-port")).isEmpty();
            assertThat(provider.discoverNodes("host:999999")).isEmpty();
            assertThat(provider.discoverNodes("failing-dns:8000")).isEmpty();
        }

        @Test
        @DisplayName("Should handle target with URL scheme and path")
        void shouldHandleTargetWithUrlSchemeAndPath() throws UnknownHostException {
            InetAddress ip = InetAddress.getByName("127.0.0.1");
            DnsClusterDiscoveryProvider provider = new DnsClusterDiscoveryProvider(
                    host -> host.equals("my-service") ? List.of(ip) : Collections.emptyList()
            );

            List<UpstreamNode> nodes = provider.discoverNodes("http://my-service:8080/v1");
            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getId()).isEqualTo("dns-127.0.0.1:8080");
            assertThat(nodes.get(0).getBaseUrl()).isEqualTo("http://127.0.0.1:8080/v1");
        }
    }

    @Nested
    @DisplayName("ModelCatalogDiscoveryProvider Tests")
    class ModelCatalogDiscoveryProviderTests {

        private MockWebServer server;
        private ModelCatalogDiscoveryProvider provider;

        @BeforeEach
        void setUp() throws IOException {
            server = new MockWebServer();
            server.start();
            provider = new ModelCatalogDiscoveryProvider();
        }

        @AfterEach
        void tearDown() throws IOException {
            server.shutdown();
        }

        @Test
        @DisplayName("Should extract model ids from OpenAI-compatible JSON format")
        void shouldExtractModelIdsFromOpenAiJson() {
            String json = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "qwen2.5:72b", "object": "model", "created": 1720000000, "owned_by": "vllm"},
                        {"id": "deepseek-r1", "object": "model", "created": 1720000001, "owned_by": "vllm"}
                      ]
                    }
                    """;

            List<String> models = provider.extractModelIds(json);
            assertThat(models).containsExactly("qwen2.5:72b", "deepseek-r1");
        }

        @Test
        @DisplayName("Should discover models via HTTP endpoint and include auth header")
        void shouldDiscoverModelsViaHttpEndpoint() throws InterruptedException {
            String modelsJson = """
                    {
                      "data": [
                        {"id": "qwen2.5:72b"},
                        {"id": "deepseek-r1"}
                      ]
                    }
                    """;

            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setResponseCode(200)
                    .setBody(modelsJson));

            String baseUrl = server.url("/v1").toString();
            List<String> models = provider.discoverModels(baseUrl, "test-api-key");

            assertThat(models).containsExactly("qwen2.5:72b", "deepseek-r1");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/models");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
        }

        @Test
        @DisplayName("Should check health confirming node health on 200")
        void shouldCheckHealthConfirmingNodeHealthOn200() {
            server.enqueue(new MockResponse().setResponseCode(200));

            String baseUrl = server.url("/").toString();
            boolean isHealthy = provider.checkHealth(baseUrl);

            assertThat(isHealthy).isTrue();
        }

        @Test
        @DisplayName("Should check health returning false when endpoints fail")
        void shouldCheckHealthReturningFalseWhenEndpointsFail() {
            // Queue 404 for /health, 500 for /models, etc.
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(500));

            String baseUrl = server.url("/").toString();
            boolean isHealthy = provider.checkHealth(baseUrl);

            assertThat(isHealthy).isFalse();
        }

        @Test
        @DisplayName("Should discover nodes, extract models, and confirm health")
        void shouldDiscoverNodesAndConfirmHealth() {
            String modelsJson = """
                    {
                      "data": [
                        {"id": "qwen2.5:72b"},
                        {"id": "deepseek-r1"}
                      ]
                    }
                    """;

            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setResponseCode(200)
                    .setBody(modelsJson));

            String baseUrl = server.url("/v1").toString();
            List<UpstreamNode> nodes = provider.discoverNodes(baseUrl);

            assertThat(nodes).hasSize(1);
            UpstreamNode node = nodes.get(0);
            assertThat(node.isHealthy()).isTrue();
            assertThat(node.getSupportedModels()).containsExactlyInAnyOrder("qwen2.5:72b", "deepseek-r1");
            assertThat(node.supportsModel("qwen2.5:72b")).isTrue();
            assertThat(node.supportsModel("deepseek-r1")).isTrue();
            assertThat(node.supportsModel("gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("Should handle unhealthy node during discoverNodes")
        void shouldHandleUnhealthyNodeDuringDiscoverNodes() {
            server.enqueue(new MockResponse().setResponseCode(503)); // models fail
            server.enqueue(new MockResponse().setResponseCode(503)); // alt models fail
            server.enqueue(new MockResponse().setResponseCode(503)); // health fail
            server.enqueue(new MockResponse().setResponseCode(503)); // alt health fail
            server.enqueue(new MockResponse().setResponseCode(503)); // probe models fail

            String baseUrl = server.url("/v1").toString();
            List<UpstreamNode> nodes = provider.discoverNodes(baseUrl);

            assertThat(nodes).hasSize(1);
            UpstreamNode node = nodes.get(0);
            assertThat(node.isHealthy()).isFalse();
            assertThat(node.getSupportedModels()).isEmpty();
        }

        @Test
        @DisplayName("Should handle malformed JSON and empty targets gracefully")
        void shouldHandleMalformedJsonAndEmptyTargets() {
            assertThat(provider.extractModelIds(null)).isEmpty();
            assertThat(provider.extractModelIds("")).isEmpty();
            assertThat(provider.extractModelIds("not a json string")).isEmpty();
            assertThat(provider.discoverNodes(null)).isEmpty();
            assertThat(provider.discoverNodes("")).isEmpty();
            assertThat(provider.discoverModels(null, "")).isEmpty();
            assertThat(provider.checkHealth(null)).isFalse();
        }

        @Test
        @DisplayName("Should support custom request sender injection")
        void shouldSupportCustomRequestSenderInjection() {
            ModelCatalogDiscoveryProvider customSenderProvider = new ModelCatalogDiscoveryProvider(
                    request -> null // simulates transport failure returning null
            );

            assertThat(customSenderProvider.getProviderType()).isEqualTo("CATALOG");
            assertThat(customSenderProvider.discoverModels("http://custom-host:8000/v1")).isEmpty();
            assertThat(customSenderProvider.checkHealth("http://custom-host:8000/v1")).isFalse();
        }
    }

    @Nested
    @DisplayName("StaticClusterDiscoveryProvider Tests")
    class StaticClusterDiscoveryProviderTests {

        @Test
        @DisplayName("Should discover nodes from comma-separated URL string")
        void shouldDiscoverNodesFromCommaSeparatedUrls() {
            StaticClusterDiscoveryProvider provider = new StaticClusterDiscoveryProvider();
            assertThat(provider.getProviderType()).isEqualTo("STATIC");

            String target = "http://10.0.1.1:8000/v1,http://10.0.1.2:8000/v1";
            List<UpstreamNode> nodes = provider.discoverNodes(target);

            assertThat(nodes).hasSize(2);

            UpstreamNode node1 = nodes.get(0);
            assertThat(node1.getId()).isEqualTo("static-10.0.1.1:8000");
            assertThat(node1.getBaseUrl()).isEqualTo("http://10.0.1.1:8000/v1");
            assertThat(node1.isHealthy()).isTrue();

            UpstreamNode node2 = nodes.get(1);
            assertThat(node2.getId()).isEqualTo("static-10.0.1.2:8000");
            assertThat(node2.getBaseUrl()).isEqualTo("http://10.0.1.2:8000/v1");
            assertThat(node2.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Should use pre-configured static URLs when target is null or blank")
        void shouldUsePreconfiguredStaticUrls() {
            List<String> staticUrls = List.of(
                    "http://192.168.1.10:8000/v1",
                    "http://192.168.1.11:8000/v1"
            );
            StaticClusterDiscoveryProvider provider = new StaticClusterDiscoveryProvider(staticUrls);

            List<UpstreamNode> nodes = provider.discoverNodes((String) null);
            assertThat(nodes).hasSize(2);
            assertThat(nodes.get(0).getId()).isEqualTo("static-192.168.1.10:8000");
            assertThat(nodes.get(1).getId()).isEqualTo("static-192.168.1.11:8000");

            List<UpstreamNode> nodesFromList = provider.discoverNodes(staticUrls);
            assertThat(nodesFromList).hasSize(2);
        }

        @Test
        @DisplayName("Should auto-prepend http scheme when omitted in URL")
        void shouldAutoPrependHttpScheme() {
            StaticClusterDiscoveryProvider provider = new StaticClusterDiscoveryProvider("10.0.1.5:8080/v1");
            List<UpstreamNode> nodes = provider.discoverNodes("");

            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getId()).isEqualTo("static-10.0.1.5:8080");
            assertThat(nodes.get(0).getBaseUrl()).isEqualTo("http://10.0.1.5:8080/v1");
        }

        @Test
        @DisplayName("Should handle empty, blank, or invalid targets gracefully")
        void shouldHandleEmptyOrInvalidTargets() {
            StaticClusterDiscoveryProvider provider = new StaticClusterDiscoveryProvider();

            assertThat(provider.discoverNodes((String) null)).isEmpty();
            assertThat(provider.discoverNodes("")).isEmpty();
            assertThat(provider.discoverNodes("   ")).isEmpty();
            assertThat(provider.discoverNodes(Collections.emptyList())).isEmpty();
        }
    }

    @Test
    @DisplayName("ClusterDiscoveryProvider default methods return empty list")
    void shouldReturnDefaultValuesFromInterface() {
        ClusterDiscoveryProvider dummyProvider = new ClusterDiscoveryProvider() {
            @Override
            public String getProviderType() {
                return "DUMMY";
            }

            @Override
            public List<UpstreamNode> discoverNodes(String target) {
                return Collections.emptyList();
            }
        };

        assertThat(dummyProvider.getProviderType()).isEqualTo("DUMMY");
        assertThat(dummyProvider.discoverNodes("test")).isEmpty();
        assertThat(dummyProvider.discoverModels("http://localhost:8000/v1")).isEmpty();
        assertThat(dummyProvider.discoverModels("http://localhost:8000/v1", "key")).isEmpty();
    }
}
