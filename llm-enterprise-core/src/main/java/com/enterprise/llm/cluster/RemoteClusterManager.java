package com.enterprise.llm.cluster;

import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.backend.InferenceBackendRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.cluster.discovery.ClusterDiscoveryProvider;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.model.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enterprise Remote Inference Cluster Manager.
 * Coordinates auto-discovery providers (DNS, Model Catalog, Static),
 * maintains dynamic node pools, and performs inference-aware load balancing.
 */
public class RemoteClusterManager {

    private static final Logger log = LoggerFactory.getLogger(RemoteClusterManager.class);

    private final List<ClusterDiscoveryProvider> discoveryProviders;
    private final Set<String> discoveryTargets;
    private final Map<String, UpstreamNode> activeNodes;
    private final Map<String, LlmClient> clientCache;
    private final Function<UpstreamNode, LlmClient> clientFactory;
    private volatile ClusterLoadBalancer loadBalancer;
    private volatile InferenceBackendRegistry backendRegistry;
    private ScheduledExecutorService scheduledExecutor;

    public RemoteClusterManager(
            List<ClusterDiscoveryProvider> discoveryProviders,
            ClusterLoadBalancer loadBalancer,
            Function<UpstreamNode, LlmClient> clientFactory,
            InferenceBackendRegistry backendRegistry
    ) {
        this.discoveryProviders = discoveryProviders != null ? new CopyOnWriteArrayList<>(discoveryProviders) : new CopyOnWriteArrayList<>();
        this.discoveryTargets = ConcurrentHashMap.newKeySet();
        this.activeNodes = new ConcurrentHashMap<>();
        this.clientCache = new ConcurrentHashMap<>();
        this.backendRegistry = backendRegistry != null ? backendRegistry : new InferenceBackendRegistry();
        this.loadBalancer = loadBalancer != null ? loadBalancer : this.backendRegistry.getDefaultAdapter().createDefaultLoadBalancer();
        this.clientFactory = clientFactory != null ? clientFactory : node -> new OpenAiCompatibleLlmClient(node.getBaseUrl(), node.getApiKeyPool());
    }

    public RemoteClusterManager(
            List<ClusterDiscoveryProvider> discoveryProviders,
            ClusterLoadBalancer loadBalancer,
            Function<UpstreamNode, LlmClient> clientFactory
    ) {
        this(discoveryProviders, loadBalancer, clientFactory, null);
    }

    public RemoteClusterManager(List<ClusterDiscoveryProvider> discoveryProviders, ClusterLoadBalancer loadBalancer) {
        this(discoveryProviders, loadBalancer, null, null);
    }

    public RemoteClusterManager() {
        this(new ArrayList<>(), null, null, null);
    }

    public void registerDiscoveryProvider(ClusterDiscoveryProvider provider) {
        if (provider != null) {
            this.discoveryProviders.add(provider);
        }
    }

    public void addDiscoveryTarget(String target) {
        if (target != null && !target.isBlank()) {
            this.discoveryTargets.add(target.trim());
        }
    }

    public void setLoadBalancer(ClusterLoadBalancer loadBalancer) {
        if (loadBalancer != null) {
            this.loadBalancer = loadBalancer;
        }
    }

    public ClusterLoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    public InferenceBackendRegistry getBackendRegistry() {
        return backendRegistry;
    }

    public void setBackendRegistry(InferenceBackendRegistry backendRegistry) {
        if (backendRegistry != null) {
            this.backendRegistry = backendRegistry;
        }
    }

    /**
     * Probes all active nodes using their corresponding InferenceBackendAdapter.
     * Updates healthy state and extracts queue/cache metrics.
     */
    public void probeHealthAndMetrics() {
        if (backendRegistry == null || activeNodes.isEmpty()) {
            return;
        }
        for (UpstreamNode node : activeNodes.values()) {
            try {
                InferenceBackendAdapter adapter = backendRegistry.getAdapter(node.getEngineType());
                if (adapter != null) {
                    boolean healthy = adapter.checkHealth(node, null);
                    node.setHealthy(healthy);
                }
            } catch (Exception e) {
                log.debug("Failed probing health for node [{}]: {}", node.getId(), e.getMessage());
            }
        }
    }

    /**
     * Actively triggers topology auto-discovery across all configured providers and targets.
     * Discovers new nodes, synchronizes supported models, and cleans up removed nodes.
     */
    public synchronized void refreshTopology() {
        if (discoveryProviders.isEmpty() || discoveryTargets.isEmpty()) {
            return;
        }

        Set<String> discoveredNodeIds = new HashSet<>();

        for (String target : discoveryTargets) {
            for (ClusterDiscoveryProvider provider : discoveryProviders) {
                try {
                    List<UpstreamNode> nodes = provider.discoverNodes(target);
                    if (nodes != null && !nodes.isEmpty()) {
                        for (UpstreamNode node : nodes) {
                            discoveredNodeIds.add(node.getId());
                            // Preserve activeRequests and circuitBreaker if node already exists
                            UpstreamNode existing = activeNodes.putIfAbsent(node.getId(), node);
                            UpstreamNode effective = existing != null ? existing : node;

                            // Model introspection if models not yet populated
                            if (effective.getSupportedModels().isEmpty()) {
                                try {
                                    List<String> models = provider.discoverModels(effective.getBaseUrl(), effective.getApiKey());
                                    if (models != null && !models.isEmpty()) {
                                        effective.setSupportedModels(new HashSet<>(models));
                                        log.info("Node [{}] introspected models: {}", effective.getId(), models);
                                    }
                                } catch (Exception me) {
                                    log.debug("Model catalog introspection failed for node [{}]: {}", effective.getId(), me.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Discovery provider [{}] failed for target [{}]: {}", provider.getProviderType(), target, e.getMessage());
                }
            }
        }

        // Remove stale nodes that were not discovered in this cycle
        if (!discoveredNodeIds.isEmpty()) {
            Iterator<Map.Entry<String, UpstreamNode>> iterator = activeNodes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, UpstreamNode> entry = iterator.next();
                if (!discoveredNodeIds.contains(entry.getKey())) {
                    log.info("Node [{}] scaled down / unreachable, removing from active pool", entry.getKey());
                    iterator.remove();
                    clientCache.remove(entry.getKey());
                }
            }
        }

        log.debug("RemoteClusterManager topology refreshed: {} active nodes", activeNodes.size());
    }

    /**
     * Starts periodic background discovery.
     */
    public synchronized void startScheduledDiscovery(long intervalSeconds) {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            return;
        }
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-discovery-worker");
            t.setDaemon(true);
            return t;
        });
        scheduledExecutor.scheduleAtFixedRate(
                this::refreshTopology,
                0,
                Math.max(1, intervalSeconds),
                TimeUnit.SECONDS
        );
        log.info("Started scheduled cluster discovery every {} seconds", intervalSeconds);
    }

    public synchronized void stop() {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
            scheduledExecutor = null;
        }
    }

    /**
     * Selects an inference node for the given chat completion request using the configured load balancer.
     * Returns null if no nodes match the model or if all matching nodes are currently unavailable.
     */
    public UpstreamNode selectNode(ChatCompletionRequest request) {
        if (activeNodes.isEmpty()) {
            return null;
        }

        String targetModel = request != null ? request.model() : null;

        List<UpstreamNode> candidates = activeNodes.values().stream()
                .filter(node -> node.supportsModel(targetModel))
                .filter(UpstreamNode::isAvailable)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return null;
        }

        return loadBalancer.choose(candidates, request);
    }

    /**
     * Obtains or creates a cached LlmClient for communicating with the given UpstreamNode.
     */
    public LlmClient getClientForNode(UpstreamNode node) {
        if (node == null) {
            return null;
        }
        return clientCache.computeIfAbsent(node.getId(), id -> clientFactory.apply(node));
    }

    public void registerNode(UpstreamNode node) {
        if (node != null) {
            this.activeNodes.put(node.getId(), node);
        }
    }

    public void removeNode(String nodeId) {
        if (nodeId != null) {
            this.activeNodes.remove(nodeId);
            this.clientCache.remove(nodeId);
        }
    }

    public boolean hasActiveNodes() {
        return activeNodes.values().stream().anyMatch(UpstreamNode::isAvailable);
    }

    public List<UpstreamNode> getActiveNodes() {
        return Collections.unmodifiableList(new ArrayList<>(activeNodes.values()));
    }

    public int getActiveNodeCount() {
        return activeNodes.size();
    }
}
