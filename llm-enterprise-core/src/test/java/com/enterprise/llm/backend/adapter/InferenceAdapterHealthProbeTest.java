package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.cluster.UpstreamNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定推理适配器探活契约。
 *
 * <p>这些用例在重构前不存在——{@code checkHealth} 的分支逻辑（主探针 / 兜底探针 / 2xx 判定 /
 * 鉴权头 / 空节点）此前没有任何测试覆盖，属于重构风险最高的区域，先补网再动刀。
 */
class InferenceAdapterHealthProbeTest {

    private MockWebServer server;
    private PathDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        dispatcher = new PathDispatcher();
        server.setDispatcher(dispatcher);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void primaryProbeSuccessShouldNotTriggerFallback() {
        dispatcher.respond(200, "/health");

        assertThat(new VllmInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isTrue();
        assertThat(dispatcher.paths()).containsExactly("/health");
    }

    @Test
    void primaryProbeFailureShouldFallBackToModelList() {
        dispatcher.respond(503, "/health");
        dispatcher.respond(200, "/v1/models");

        assertThat(new VllmInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isTrue();
        assertThat(dispatcher.paths()).containsExactly("/health", "/v1/models");
    }

    @Test
    void bothProbesFailingShouldReportUnhealthy() {
        dispatcher.respond(503, "/health");
        dispatcher.respond(500, "/v1/models");

        assertThat(new VllmInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isFalse();
    }

    @Test
    void engineSpecificProbePathsShouldBeHonoured() {
        dispatcher.respond(200, "/-/healthz");
        dispatcher.respond(200, "/v2/health/ready");

        assertThat(new RayServeInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isTrue();
        assertThat(new TritonInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isTrue();
        assertThat(dispatcher.paths()).containsExactly("/-/healthz", "/v2/health/ready");
    }

    @Test
    void baseUrlWithV1SuffixShouldBeNormalisedToRoot() {
        dispatcher.respond(200, "/v1/models");
        UpstreamNode node = new UpstreamNode("node-v1", server.url("/v1").toString());

        assertThat(new DirectInferenceAdapter().checkHealth(node, HttpClient.newHttpClient())).isTrue();
        assertThat(dispatcher.paths()).containsExactly("/v1/models");
    }

    @Test
    void directAdapterShouldNotProbeFallbackPath() {
        dispatcher.respond(503, "/v1/models");

        assertThat(new DirectInferenceAdapter().checkHealth(node(), HttpClient.newHttpClient())).isFalse();
        assertThat(dispatcher.paths()).containsExactly("/v1/models");
    }

    @Test
    void apiKeyShouldBeSentAsBearerOnPrimaryProbe() {
        dispatcher.respond(200, "/health");
        UpstreamNode node = new UpstreamNode("node-auth", server.url("/").toString(), "secret-key", 10);

        assertThat(new VllmInferenceAdapter().checkHealth(node, HttpClient.newHttpClient())).isTrue();
        assertThat(dispatcher.authorizationHeaders()).containsExactly("Bearer secret-key");
    }

    @Test
    void invalidNodeShouldBeRejectedWithoutAnyRequest() {
        VllmInferenceAdapter adapter = new VllmInferenceAdapter();

        assertThat(adapter.checkHealth(null, HttpClient.newHttpClient())).isFalse();
        assertThat(adapter.checkHealth(new UpstreamNode("blank", "   "), HttpClient.newHttpClient())).isFalse();
        assertThat(dispatcher.paths()).isEmpty();
    }

    private UpstreamNode node() {
        return new UpstreamNode("node-1", server.url("/").toString());
    }

    /** 按路径返回预设状态码，并记录实际收到的请求，便于断言探针顺序。 */
    private static final class PathDispatcher extends Dispatcher {

        private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
        private final List<String> receivedPaths = new ArrayList<>();
        private final List<String> receivedAuth = new ArrayList<>();

        void respond(int status, String path) {
            statusByPath.put(path, status);
        }

        List<String> paths() {
            return receivedPaths;
        }

        List<String> authorizationHeaders() {
            return receivedAuth;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            receivedPaths.add(path);
            String auth = request.getHeader("Authorization");
            if (auth != null) {
                receivedAuth.add(auth);
            }
            Integer status = statusByPath.get(path);
            return new MockResponse().setResponseCode(status != null ? status : 404);
        }
    }
}
