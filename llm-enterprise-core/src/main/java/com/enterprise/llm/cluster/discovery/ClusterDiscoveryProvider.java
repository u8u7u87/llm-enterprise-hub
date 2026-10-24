package com.enterprise.llm.cluster.discovery;

import com.enterprise.llm.cluster.UpstreamNode;

import java.util.Collections;
import java.util.List;

/**
 * Service discovery provider interface for LLM upstream inference clusters.
 */
public interface ClusterDiscoveryProvider {

    /**
     * Returns the type identifier of this discovery provider (e.g., DNS, CATALOG, STATIC).
     *
     * @return provider type string
     */
    String getProviderType();

    /**
     * Discovers active upstream inference nodes for the given target.
     *
     * @param target discovery target (e.g. domain:port, catalog url, or comma-separated static urls)
     * @return list of discovered upstream nodes
     */
    List<UpstreamNode> discoverNodes(String target);

    /**
     * Discovers supported model IDs from a target upstream base URL.
     *
     * @param baseUrl base URL of the upstream service
     * @param apiKey  API key for authorization, if required
     * @return list of discovered model identifiers
     */
    default List<String> discoverModels(String baseUrl, String apiKey) {
        return Collections.emptyList();
    }

    /**
     * Discovers supported model IDs from a target upstream base URL without API key.
     *
     * @param baseUrl base URL of the upstream service
     * @return list of discovered model identifiers
     */
    default List<String> discoverModels(String baseUrl) {
        return discoverModels(baseUrl, "");
    }
}
