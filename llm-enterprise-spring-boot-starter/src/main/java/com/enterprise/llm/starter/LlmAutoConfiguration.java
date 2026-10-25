package com.enterprise.llm.starter;

import java.util.List;
import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.agent.annotation.LlmToolBeanPostProcessor;
import com.enterprise.llm.agent.task.AgentTaskManager;
import com.enterprise.llm.agent.task.AgentTaskStorageProvider;
import com.enterprise.llm.agent.task.InMemoryAgentTaskStorageProvider;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.cache.CacheProperties;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.config.LlmConfiguration;
import com.enterprise.llm.context.ContextBudgetProperties;
import com.enterprise.llm.context.ContextDistiller;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.context.HeuristicContextDistiller;
import com.enterprise.llm.guard.OutputGuardrailService;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.guard.dlp.DlpPolicyProperties;
import com.enterprise.llm.guard.dlp.DlpRuleProvider;
import com.enterprise.llm.guard.dlp.PropertiesDlpRuleProvider;
import com.enterprise.llm.guard.prompt.PromptRuleProvider;
import com.enterprise.llm.guard.prompt.PromptSecurityProperties;
import com.enterprise.llm.guard.prompt.PropertiesPromptRuleProvider;
import com.enterprise.llm.guard.tool.PropertiesToolPolicyProvider;
import com.enterprise.llm.guard.tool.ToolPolicyProperties;
import com.enterprise.llm.guard.tool.ToolPolicyProvider;
import com.enterprise.llm.quota.AsyncQuotaWriteBehindBatcher;
import com.enterprise.llm.quota.InMemoryQuotaStorageEngine;
import com.enterprise.llm.quota.QuotaStorageEngine;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.ratelimit.DistributedRateLimiter;
import com.enterprise.llm.resilience.AdaptiveJitteredRetryPolicy;
import com.enterprise.llm.router.canary.CanaryRoutingService;
import com.enterprise.llm.router.shadow.ShadowTrafficMirror;
import com.enterprise.llm.template.PromptTemplateRegistry;
import com.enterprise.llm.rag.PromptAugmentor;
import com.enterprise.llm.rag.RagPipelineService;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.store.MilvusVectorStore;
import com.enterprise.llm.rag.store.VectorStore;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.tenant.InMemoryTenantStorageProvider;
import com.enterprise.llm.tenant.TenantService;
import com.enterprise.llm.tenant.TenantStorageProvider;
import com.enterprise.llm.token.TokenEstimator;
import com.enterprise.llm.trace.LangfuseTraceExporter;
import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.backend.InferenceBackendProperties;
import com.enterprise.llm.backend.InferenceBackendRegistry;
import com.enterprise.llm.backend.adapter.*;
import com.enterprise.llm.runner.EmbeddedLocalEngineSupervisor;
import com.enterprise.llm.runner.EmbeddedRunnerProperties;
import com.enterprise.llm.trace.TraceCollector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(LlmConfiguration.class)
@EnableConfigurationProperties({
        ContextBudgetProperties.class,
        CacheProperties.class,
        PromptSecurityProperties.class,
        DlpPolicyProperties.class,
        ToolPolicyProperties.class,
        InferenceBackendProperties.class,
        EmbeddedRunnerProperties.class
})
public class LlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    public TokenEstimator tokenEstimator() {
        return new TokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean(ContextDistiller.class)
    public ContextDistiller contextDistiller() {
        return new HeuristicContextDistiller();
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.memory.WorkingMemoryManager.class)
    public com.enterprise.llm.memory.WorkingMemoryManager workingMemoryManager(ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        return new com.enterprise.llm.memory.WorkingMemoryManager(embeddingClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(ContextManager.class)
    public ContextManager contextManager(
            ContextBudgetProperties properties,
            TokenEstimator tokenEstimator,
            ContextDistiller contextDistiller,
            ObjectProvider<com.enterprise.llm.memory.WorkingMemoryManager> workingMemoryManagerProvider
    ) {
        return new ContextManager(properties, tokenEstimator, contextDistiller, workingMemoryManagerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(DirectInferenceAdapter.class)
    public DirectInferenceAdapter directInferenceAdapter() {
        return new DirectInferenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(VllmInferenceAdapter.class)
    public VllmInferenceAdapter vllmInferenceAdapter() {
        return new VllmInferenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(SglangInferenceAdapter.class)
    public SglangInferenceAdapter sglangInferenceAdapter() {
        return new SglangInferenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(TritonInferenceAdapter.class)
    public TritonInferenceAdapter tritonInferenceAdapter() {
        return new TritonInferenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(RayServeInferenceAdapter.class)
    public RayServeInferenceAdapter rayServeInferenceAdapter() {
        return new RayServeInferenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(InferenceBackendRegistry.class)
    public InferenceBackendRegistry inferenceBackendRegistry(
            InferenceBackendProperties properties,
            List<InferenceBackendAdapter> adapters
    ) {
        return new InferenceBackendRegistry(adapters, properties.getEngine());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean(EmbeddedLocalEngineSupervisor.class)
    public EmbeddedLocalEngineSupervisor embeddedLocalEngineSupervisor(EmbeddedRunnerProperties properties) {
        return new EmbeddedLocalEngineSupervisor(properties);
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.cluster.RemoteClusterManager.class)
    public com.enterprise.llm.cluster.RemoteClusterManager remoteClusterManager(
            @Value("${llm.cluster.enabled:false}") boolean clusterEnabled,
            @Value("${llm.cluster.dns-discovery:}") String dnsDiscovery,
            @Value("${llm.cluster.static-nodes:}") String staticNodes,
            @Value("${llm.cluster.discovery-interval-seconds:15}") long discoveryInterval,
            ObjectProvider<com.enterprise.llm.cluster.lb.ClusterLoadBalancer> lbProvider,
            InferenceBackendRegistry backendRegistry
    ) {
        if (!clusterEnabled && (dnsDiscovery == null || dnsDiscovery.isBlank()) && (staticNodes == null || staticNodes.isBlank())) {
            return null;
        }

        com.enterprise.llm.cluster.lb.ClusterLoadBalancer lb = lbProvider.getIfAvailable(() ->
                backendRegistry != null ? backendRegistry.getDefaultAdapter().createDefaultLoadBalancer() : new com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer()
        );
        com.enterprise.llm.cluster.RemoteClusterManager manager = new com.enterprise.llm.cluster.RemoteClusterManager(
                new java.util.ArrayList<>(), lb, null, backendRegistry
        );

        if (dnsDiscovery != null && !dnsDiscovery.isBlank()) {
            manager.registerDiscoveryProvider(new com.enterprise.llm.cluster.discovery.DnsClusterDiscoveryProvider());
            manager.addDiscoveryTarget(dnsDiscovery);
        }
        if (staticNodes != null && !staticNodes.isBlank()) {
            manager.registerDiscoveryProvider(new com.enterprise.llm.cluster.discovery.StaticClusterDiscoveryProvider());
            manager.addDiscoveryTarget(staticNodes);
        }

        // Always register ModelCatalogDiscoveryProvider for automatic introspection
        manager.registerDiscoveryProvider(new com.enterprise.llm.cluster.discovery.ModelCatalogDiscoveryProvider());

        if (discoveryInterval > 0) {
            manager.startScheduledDiscovery(discoveryInterval);
        } else {
            manager.refreshTopology();
        }

        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(FailoverRoutingService.class)
    public FailoverRoutingService failoverRoutingService(
            @Qualifier("primaryLlmClient") LlmClient primaryClient,
            @Qualifier("fallbackLlmClient") LlmClient fallbackClient,
            @Value("${llm.fallback.model:qwen2.5:0.5b}") String fallbackModel,
            ObjectProvider<ContextManager> contextManagerProvider,
            ObjectProvider<com.enterprise.llm.resilience.CircuitBreaker> circuitBreakerProvider,
            ObjectProvider<com.enterprise.llm.cluster.RemoteClusterManager> clusterManagerProvider,
            ObjectProvider<com.enterprise.llm.router.ModelAliasRegistry> modelAliasRegistryProvider
    ) {
        return new FailoverRoutingService(
                primaryClient,
                fallbackClient,
                fallbackModel,
                contextManagerProvider.getIfAvailable(),
                circuitBreakerProvider.getIfAvailable(),
                clusterManagerProvider.getIfAvailable(),
                modelAliasRegistryProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(LlmToolBeanPostProcessor.class)
    public static LlmToolBeanPostProcessor llmToolBeanPostProcessor(ObjectProvider<ToolRegistry> toolRegistryProvider) {
        return new LlmToolBeanPostProcessor(toolRegistryProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TenantStorageProvider.class)
    public TenantStorageProvider tenantStorageProvider() {
        return new InMemoryTenantStorageProvider();
    }

    @Bean
    @ConditionalOnMissingBean(TenantService.class)
    public TenantService tenantService(TenantStorageProvider tenantStorageProvider) {
        return new TenantService(tenantStorageProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TenantRateLimiter.class)
    public TenantRateLimiter tenantRateLimiter() {
        return new TenantRateLimiter();
    }

    @Bean
    @ConditionalOnMissingBean(DistributedRateLimiter.class)
    public DistributedRateLimiter distributedRateLimiter(TenantRateLimiter tenantRateLimiter) {
        return new DistributedRateLimiter(tenantRateLimiter);
    }

    @Bean
    @ConditionalOnMissingBean(QuotaStorageEngine.class)
    public QuotaStorageEngine quotaStorageEngine() {
        return new InMemoryQuotaStorageEngine();
    }

    @Bean
    @ConditionalOnProperty(prefix = "llm.quota.write-behind", name = "enabled", havingValue = "true")
    public AsyncQuotaWriteBehindBatcher asyncQuotaWriteBehindBatcher(TenantStorageProvider tenantStorageProvider) {
        return new AsyncQuotaWriteBehindBatcher(tenantStorageProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TenantQuotaService.class)
    public TenantQuotaService tenantQuotaService(TenantStorageProvider tenantStorageProvider,
                                                 QuotaStorageEngine quotaStorageEngine,
                                                 ObjectProvider<AsyncQuotaWriteBehindBatcher> asyncQuotaWriteBehindBatcher) {
        return new TenantQuotaService(tenantStorageProvider, quotaStorageEngine, asyncQuotaWriteBehindBatcher.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(CanaryRoutingService.class)
    public CanaryRoutingService canaryRoutingService() {
        return new CanaryRoutingService();
    }

    @Bean
    @ConditionalOnMissingBean(ShadowTrafficMirror.class)
    public ShadowTrafficMirror shadowTrafficMirror() {
        return new ShadowTrafficMirror();
    }

    @Bean
    @ConditionalOnMissingBean(PromptTemplateRegistry.class)
    public PromptTemplateRegistry promptTemplateRegistry() {
        return new PromptTemplateRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(AdaptiveJitteredRetryPolicy.class)
    public AdaptiveJitteredRetryPolicy adaptiveJitteredRetryPolicy() {
        return new AdaptiveJitteredRetryPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(PromptRuleProvider.class)
    public PromptRuleProvider promptRuleProvider(PromptSecurityProperties properties) {
        return new PropertiesPromptRuleProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(PromptGuardrailService.class)
    public PromptGuardrailService promptGuardrailService(PromptRuleProvider ruleProvider) {
        return new PromptGuardrailService(ruleProvider);
    }

    @Bean
    @ConditionalOnMissingBean(DlpRuleProvider.class)
    public DlpRuleProvider dlpRuleProvider(DlpPolicyProperties properties) {
        return new PropertiesDlpRuleProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(OutputGuardrailService.class)
    public OutputGuardrailService outputGuardrailService(DlpRuleProvider ruleProvider) {
        return new OutputGuardrailService(ruleProvider);
    }

    @Bean
    @ConditionalOnMissingBean(ToolPolicyProvider.class)
    public ToolPolicyProvider toolPolicyProvider(ToolPolicyProperties properties) {
        return new PropertiesToolPolicyProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolActionGatekeeper.class)
    public ToolActionGatekeeper toolActionGatekeeper(ToolPolicyProvider policyProvider) {
        return new ToolActionGatekeeper(policyProvider);
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.metrics.LlmMetricsCollector.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public com.enterprise.llm.metrics.LlmMetricsCollector micrometerLlmMetricsCollector(
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider
    ) {
        io.micrometer.core.instrument.MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            return new com.enterprise.llm.starter.metrics.MicrometerLlmMetricsCollector(registry);
        }
        return com.enterprise.llm.metrics.NoOpMetricsCollector.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.metrics.LlmMetricsCollector.class)
    public com.enterprise.llm.metrics.LlmMetricsCollector noOpLlmMetricsCollector() {
        return com.enterprise.llm.metrics.NoOpMetricsCollector.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(AgentToolOrchestrator.class)
    public AgentToolOrchestrator agentToolOrchestrator(
            ToolRegistry toolRegistry,
            ObjectProvider<ToolActionGatekeeper> gatekeeperProvider,
            ObjectProvider<com.enterprise.llm.metrics.LlmMetricsCollector> metricsCollectorProvider
    ) {
        AgentToolOrchestrator orchestrator = new AgentToolOrchestrator(toolRegistry, gatekeeperProvider.getIfAvailable());
        if (metricsCollectorProvider.getIfAvailable() != null) {
            orchestrator.setMetricsCollector(metricsCollectorProvider.getIfAvailable());
        }
        return orchestrator;
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.agent.StreamingAgentOrchestrator.class)
    public com.enterprise.llm.agent.StreamingAgentOrchestrator streamingAgentOrchestrator(
            AgentToolOrchestrator agentToolOrchestrator
    ) {
        return new com.enterprise.llm.agent.StreamingAgentOrchestrator(agentToolOrchestrator);
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator.class)
    public com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator agentSwarmOrchestrator() {
        return new com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator();
    }

    @Bean
    @ConditionalOnMissingBean(AgentTaskStorageProvider.class)
    public AgentTaskStorageProvider agentTaskStorageProvider() {
        return new InMemoryAgentTaskStorageProvider();
    }

    @Bean
    @ConditionalOnMissingBean(AgentTaskManager.class)
    public AgentTaskManager agentTaskManager(
            AgentTaskStorageProvider taskStorageProvider,
            AgentToolOrchestrator orchestrator,
            @Qualifier("primaryLlmClient") ObjectProvider<LlmClient> primaryClientProvider
    ) {
        return new AgentTaskManager(taskStorageProvider, orchestrator, primaryClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(CacheEngine.class)
    public CacheEngine cacheEngine(
            CacheProperties properties,
            ObjectProvider<com.enterprise.llm.rag.embedding.EmbeddingClient> embeddingClientProvider
    ) {
        return new CacheEngine(properties, embeddingClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(
            @Value("${milvus.endpoint:disabled}") String endpoint,
            @Value("${milvus.token:}") String token,
            @Value("${milvus.collection:knowledge_base}") String collectionName
    ) {
        return new MilvusVectorStore(endpoint, token, collectionName);
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator.class)
    public com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator ragFaithfulnessEvaluator(
            @Value("${rag.guardrail.pass-threshold:0.65}") double passThreshold,
            @Value("${rag.guardrail.warn-threshold:0.40}") double warnThreshold
    ) {
        return new com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator(passThreshold, warnThreshold);
    }

    @Bean
    @ConditionalOnMissingBean(RagPipelineService.class)
    public RagPipelineService ragPipelineService(
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<com.enterprise.llm.rag.query.QueryRewriter> queryRewriterProvider,
            ObjectProvider<com.enterprise.llm.rag.rerank.Reranker> rerankerProvider,
            ObjectProvider<com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator> faithfulnessEvaluatorProvider,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.min-score:0.5}") double minScore
    ) {
        return new RagPipelineService(
                embeddingClientProvider.getIfAvailable(),
                vectorStoreProvider.getIfAvailable(),
                queryRewriterProvider.getIfAvailable(),
                rerankerProvider.getIfAvailable(),
                faithfulnessEvaluatorProvider.getIfAvailable(),
                ragEnabled,
                topK,
                minScore
        );
    }

    @Bean
    @ConditionalOnMissingBean(TraceCollector.class)
    public TraceCollector traceCollector() {
        return new TraceCollector();
    }

    @Bean
    @ConditionalOnMissingBean(LangfuseTraceExporter.class)
    public LangfuseTraceExporter langfuseTraceExporter() {
        return new LangfuseTraceExporter();
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.session.SessionStorageProvider.class)
    public com.enterprise.llm.session.SessionStorageProvider sessionStorageProvider() {
        return new com.enterprise.llm.session.InMemorySessionStorageProvider();
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.session.SessionManager.class)
    public com.enterprise.llm.session.SessionManager sessionManager(com.enterprise.llm.session.SessionStorageProvider sessionStorageProvider) {
        return new com.enterprise.llm.session.SessionManager(sessionStorageProvider);
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.router.ModelAliasRegistry.class)
    public com.enterprise.llm.router.ModelAliasRegistry modelAliasRegistry() {
        return new com.enterprise.llm.router.ModelAliasRegistry();
    }


    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.cost.ModelPricingRegistry.class)
    public com.enterprise.llm.cost.ModelPricingRegistry modelPricingRegistry() {
        return new com.enterprise.llm.cost.ModelPricingRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.cost.CostCalculator.class)
    public com.enterprise.llm.cost.CostCalculator costCalculator(ObjectProvider<com.enterprise.llm.cost.ModelPricingRegistry> pricingRegistryProvider) {
        return new com.enterprise.llm.cost.CostCalculator(pricingRegistryProvider.getIfAvailable(com.enterprise.llm.cost.ModelPricingRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.cost.TenantBudgetManager.class)
    public com.enterprise.llm.cost.TenantBudgetManager tenantBudgetManager() {
        return new com.enterprise.llm.cost.TenantBudgetManager();
    }

    @Bean
    @ConditionalOnMissingBean(com.enterprise.llm.guard.StreamingOutputGuardrail.class)
    public com.enterprise.llm.guard.StreamingOutputGuardrail streamingOutputGuardrail(
            @Value("${llm.guardrail.streaming.mode:BLOCK}") String modeStr,
            @Value("${llm.guardrail.streaming.overlap-size:64}") int overlapSize
    ) {
        com.enterprise.llm.guard.StreamingOutputGuardrail.Mode mode = "MASK".equalsIgnoreCase(modeStr)
                ? com.enterprise.llm.guard.StreamingOutputGuardrail.Mode.MASK
                : com.enterprise.llm.guard.StreamingOutputGuardrail.Mode.BLOCK;
        return new com.enterprise.llm.guard.StreamingOutputGuardrail(mode, overlapSize);
    }
}

