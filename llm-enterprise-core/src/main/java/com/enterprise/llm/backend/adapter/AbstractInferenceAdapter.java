package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.cluster.UpstreamNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推理引擎适配器公共骨架。
 *
 * <p>把各引擎适配器里逐字重复的两块逻辑收敛到这里：
 * <ul>
 *   <li><b>探活流程</b>：空节点校验、根地址归一化、鉴权头注入、超时、2xx 判定、异常兜底；</li>
 *   <li><b>Prometheus 指标解析</b>：指标文本空值判断、正则取数、数值格式异常兜底。</li>
 * </ul>
 *
 * <p>子类只需声明探针路径与自身指标正则，不再重复上述骨架代码。
 */
abstract class AbstractInferenceAdapter implements InferenceBackendAdapter {

    /** 主探针超时：引擎 /health 类接口通常立即返回，4 秒足够判定不可达。 */
    private static final Duration PRIMARY_TIMEOUT = Duration.ofSeconds(4);

    /** 兜底探针超时：主探针已失败过一次，兜底不再长时间等待。 */
    private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(3);

    /** OpenAI 兼容的模型列表接口，绝大多数引擎都实现了它，作为通用兜底探针。 */
    private static final String DEFAULT_FALLBACK_PATH = "/v1/models";

    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX_EXCLUSIVE = 300;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 主探针路径（相对引擎根地址），例如 {@code /health}、{@code /-/healthz}。 */
    protected abstract String primaryProbePath();

    /**
     * 兜底探针路径，主探针返回非 2xx 时使用。
     *
     * @return 相对路径；返回 {@code null} 表示该引擎不做兜底探测
     */
    protected String fallbackProbePath() {
        return DEFAULT_FALLBACK_PATH;
    }

    @Override
    public final boolean checkHealth(UpstreamNode node, HttpClient httpClient) {
        if (node == null || node.getBaseUrl() == null || node.getBaseUrl().isBlank()) {
            return false;
        }
        HttpClient client = httpClient != null ? httpClient : HttpClient.newHttpClient();
        String root = rootUrl(node.getBaseUrl());

        try {
            if (isSuccess(send(client, root + primaryProbePath(), node.getApiKey(), PRIMARY_TIMEOUT))) {
                return true;
            }
            String fallbackPath = fallbackProbePath();
            if (fallbackPath == null) {
                return false;
            }
            // 兜底探测沿用原行为：不带鉴权头，避免把需要鉴权的节点误判为健康
            return isSuccess(send(client, root + fallbackPath, null, FALLBACK_TIMEOUT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("{} health probe interrupted for node [{}]", getEngineType(), node.getId());
            return false;
        } catch (IOException | RuntimeException e) {
            log.debug("{} health probe failed for node [{}]: {}", getEngineType(), node.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public final void extractMetrics(UpstreamNode node, String prometheusText) {
        if (node == null || prometheusText == null || prometheusText.isBlank()) {
            return;
        }
        doExtractMetrics(node, prometheusText);
    }

    /**
     * 子类实现具体指标解析。
     *
     * @param node 目标节点，基类已保证非空
     * @param prometheusText 指标原文，基类已保证非空白
     */
    protected void doExtractMetrics(UpstreamNode node, String prometheusText) {
        // 该引擎未暴露 Prometheus 指标
    }

    /** 解析等待队列长度写入节点；指标缺失或格式异常时保留节点原值。 */
    protected static void applyWaitingRequests(UpstreamNode node, Pattern pattern, String prometheusText) {
        Double value = firstNumber(pattern, prometheusText);
        if (value != null) {
            node.setWaitingRequests(value.intValue());
        }
    }

    /** 解析 GPU KV-cache 使用率写入节点；指标缺失或格式异常时保留节点原值。 */
    protected static void applyGpuCacheUsage(UpstreamNode node, Pattern pattern, String prometheusText) {
        Double value = firstNumber(pattern, prometheusText);
        if (value != null) {
            node.setGpuCacheUsage(value);
        }
    }

    /**
     * 取正则首个捕获组的数值。
     *
     * @return 解析成功返回数值，指标缺失或非数值（如某些引擎输出的 {@code NaN}）返回 {@code null}
     */
    private static Double firstNumber(Pattern pattern, String prometheusText) {
        Matcher matcher = pattern.matcher(prometheusText);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉末尾的 {@code /} 与 {@code /v1}，得到引擎根地址。 */
    protected static String rootUrl(String baseUrl) {
        String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return clean.endsWith("/v1") ? clean.substring(0, clean.length() - 3) : clean;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= HTTP_OK_MIN && statusCode < HTTP_OK_MAX_EXCLUSIVE;
    }

    private static int send(HttpClient client, String url, String apiKey, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
