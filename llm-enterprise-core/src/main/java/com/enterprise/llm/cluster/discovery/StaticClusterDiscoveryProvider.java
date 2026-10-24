package com.enterprise.llm.cluster.discovery;

import com.enterprise.llm.cluster.UpstreamNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static cluster discovery provider that parses explicit static URL configurations.
 */
public class StaticClusterDiscoveryProvider implements ClusterDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(StaticClusterDiscoveryProvider.class);
    public static final String PROVIDER_TYPE = "STATIC";

    private final List<String> defaultUrls;

    public StaticClusterDiscoveryProvider() {
        this(Collections.emptyList());
    }

    public StaticClusterDiscoveryProvider(String commaSeparatedUrls) {
        this(parseCommaSeparated(commaSeparatedUrls));
    }

    public StaticClusterDiscoveryProvider(List<String> urls) {
        this.defaultUrls = urls != null ? List.copyOf(urls) : Collections.emptyList();
    }

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public List<UpstreamNode> discoverNodes(String target) {
        List<String> urlsToParse;
        if (target != null && !target.isBlank()) {
            urlsToParse = parseCommaSeparated(target);
        } else {
            urlsToParse = defaultUrls;
        }

        if (urlsToParse.isEmpty()) {
            return Collections.emptyList();
        }

        List<UpstreamNode> nodes = new ArrayList<>(urlsToParse.size());
        for (String rawUrl : urlsToParse) {
            UpstreamNode node = parseNode(rawUrl);
            if (node != null) {
                nodes.add(node);
            }
        }

        return Collections.unmodifiableList(nodes);
    }

    /**
     * Discovers upstream nodes from an explicit list of URLs.
     *
     * @param urls list of upstream URLs
     * @return list of parsed UpstreamNode instances
     */
    public List<UpstreamNode> discoverNodes(List<String> urls) {
        return discoverNodesFromUrls(urls);
    }

    /**
     * Discovers upstream nodes from an explicit list of URLs.
     *
     * @param urls list of upstream URLs
     * @return list of parsed UpstreamNode instances
     */
    public List<UpstreamNode> discoverNodesFromUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return discoverNodes((String) null);
        }
        List<UpstreamNode> nodes = new ArrayList<>(urls.size());
        for (String rawUrl : urls) {
            UpstreamNode node = parseNode(rawUrl);
            if (node != null) {
                nodes.add(node);
            }
        }
        return Collections.unmodifiableList(nodes);
    }

    private static UpstreamNode parseNode(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String cleaned = rawUrl.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        String urlWithScheme = cleaned;
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            urlWithScheme = "http://" + cleaned;
        }

        try {
            URI uri = URI.create(urlWithScheme);
            String host = uri.getHost();
            int port = uri.getPort();

            String id;
            if (host != null && !host.isBlank()) {
                id = "static-" + host + (port > 0 ? ":" + port : "");
            } else {
                id = "static-" + cleaned.replaceAll("[^a-zA-Z0-9.-]", "_");
            }

            return new UpstreamNode(id, urlWithScheme);
        } catch (Exception e) {
            log.warn("Invalid static upstream URL '{}': {}", rawUrl, e.getMessage());
            return null;
        }
    }

    private static List<String> parseCommaSeparated(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = text.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}
