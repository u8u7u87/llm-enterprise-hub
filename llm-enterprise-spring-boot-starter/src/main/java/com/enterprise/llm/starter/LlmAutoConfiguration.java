package com.enterprise.llm.starter;

import java.util.ArrayList;
import java.util.List;
import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.StreamingAgentOrchestrator;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.agent.annotation.LlmToolBeanPostProcessor;
import com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator;
import com.enterprise.llm.agent.task.AgentTaskManager;
import com.enterprise.llm.agent.task.AgentTaskStorageProvider;
import com.enterprise.llm.agent.task.InMemoryAgentTaskStorageProvider;
import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.backend.InferenceBackendProperties;
import com.enterprise.llm.backend.InferenceBackendRegistry;
import com.enterprise.llm.backend.adapter.DirectInferenceAdapter;
import com.enterprise.llm.backend.adapter.RayServeInferenceAdapter;
import com.enterprise.llm.backend.adapter.SglangInferenceAdapter;
import com.enterprise.llm.backend.adapter.TritonInferenceAdapter;
import com.enterprise.llm.backend.adapter.VllmInferenceAdapter;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.cache.CacheProperties;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.cluster.RemoteClusterManager;
import com.enterprise.llm.cluster.discovery.DnsClusterDiscoveryProvider;
import com.enterprise.llm.cluster.discovery.ModelCatalogDiscoveryProvider;
import com.enterprise.llm.cluster.discovery.StaticClusterDiscoveryProvider;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.config.LlmConfiguration;
import com.enterprise.llm.context.ContextBudgetProperties;
import com.enterprise.llm.context.ContextDistiller;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.context.HeuristicContextDistiller;
import com.enterprise.llm.cost.CostCalculator;
import com.enterprise.llm.cost.ModelPricingRegistry;
import com.enterprise.llm.cost.TenantBudgetManager;
import com.enterprise.llm.guard.OutputGuardrailService;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.guard.StreamingOutputGuardrail;
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
import com.enterprise.llm.memory.WorkingMemoryManager;
import com.enterprise.llm.metrics.LlmMetricsCollector;
import com.enterprise.llm.metrics.NoOpMetricsCollector;
import com.enterprise.llm.quota.AsyncQuotaWriteBehindBatcher;
import com.enterprise.llm.quota.InMemoryQuotaStorageEngine;
import com.enterprise.llm.quota.QuotaStorageEngine;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.rag.RagPipelineService;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator;
import com.enterprise.llm.rag.query.QueryRewriter;
import com.enterprise.llm.rag.rerank.Reranker;
import com.enterprise.llm.rag.store.MilvusVectorStore;
import com.enterprise.llm.rag.store.VectorStore;
import com.enterprise.llm.ratelimit.DistributedRateLimiter;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.resilience.AdaptiveJitteredRetryPolicy;
import com.enterprise.llm.resilience.CircuitBreaker;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.router.ModelAliasRegistry;
import com.enterprise.llm.router.canary.CanaryRoutingService;
import com.enterprise.llm.router.canary.CanaryRule;
import com.enterprise.llm.router.shadow.ShadowTrafficMirror;
import com.enterprise.llm.router.shadow.ShadowTrafficRule;
import com.enterprise.llm.runner.EmbeddedLocalEngineSupervisor;
import com.enterprise.llm.runner.EmbeddedRunnerProperties;
import com.enterprise.llm.session.InMemorySessionStorageProvider;
import com.enterprise.llm.session.SessionManager;
import com.enterprise.llm.session.SessionStorageProvider;
import com.enterprise.llm.starter.config.LlmCanaryProperties;
import com.enterprise.llm.starter.config.LlmPrivacyProperties;
import com.enterprise.llm.starter.config.LlmShadowProperties;
import com.enterprise.llm.starter.metrics.MicrometerLlmMetricsCollector;
import com.enterprise.llm.template.PromptTemplateRegistry;
import com.enterprise.llm.tenant.InMemoryTenantStorageProvider;
import com.enterprise.llm.tenant.TenantService;
import com.enterprise.llm.tenant.TenantStorageProvider;
import com.enterprise.llm.token.TokenEstimator;
import com.enterprise.llm.trace.LangfuseTraceExporter;
import com.enterprise.llm.trace.TraceCollector;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
        EmbeddedRunnerProperties.class,
        LlmCanaryProperties.class,
        LlmShadowProperties.class,
        LlmPrivacyProperties.class
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
    @ConditionalOnMissingBean(WorkingMemoryManager.class)
    public WorkingMemoryManager workingMemoryManager(ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        return new WorkingMemoryManager(embeddingClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(ContextManager.class)
    public ContextManager contextManager(
            ContextBudgetProperties properties,
            TokenEstimator tokenEstimator,
            ContextDistiller contextDistiller,
            ObjectProvider<WorkingMemoryManager> workingMemoryManagerProvider
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
    @ConditionalOnMissingBean(RemoteClusterManager.class)
    public RemoteClusterManager remoteClusterManager(
            @Value("${llm.cluster.enabled:false}") boolean clusterEnabled,
            @Value("${llm.cluster.dns-discovery:}") String dnsDiscovery,
            @Value("${llm.cluster.static-nodes:}") String staticNodes,
            @Value("${llm.cluster.discovery-interval-seconds:15}") long discoveryInterval,
            ObjectProvider<ClusterLoadBalancer> lbProvider,
            InferenceBackendRegistry backendRegistry
    ) {
        if (!clusterEnabled && (dnsDiscovery == null || dnsDiscovery.isBlank()) && (staticNodes == null || staticNodes.isBlank())) {
            return null;
        }

        ClusterLoadBalancer lb = lbProvider.getIfAvailable(() ->
                backendRegistry != null ? backendRegistry.getDefaultAdapter().createDefaultLoadBalancer() : new LeastRequestsLoadBalancer()
        );
        RemoteClusterManager manager = new RemoteClusterManager(
                new ArrayList<>(), lb, null, backendRegistry
        );

        if (dnsDiscovery != null && !dnsDiscovery.isBlank()) {
            manager.registerDiscoveryProvider(new DnsClusterDiscoveryProvider());
            manager.addDiscoveryTarget(dnsDiscovery);
        }
        if (staticNodes != null && !staticNodes.isBlank()) {
            manager.registerDiscoveryProvider(new StaticClusterDiscoveryProvider());
            manager.addDiscoveryTarget(staticNodes);
        }

        // Always register ModelCatalogDiscoveryProvider for automatic introspection
        manager.registerDiscoveryProvider(new ModelCatalogDiscoveryProvider());

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
            ObjectProvider<CircuitBreaker> circuitBreakerProvider,
            ObjectProvider<RemoteClusterManager> clusterManagerProvider,
            ObjectProvider<ModelAliasRegistry> modelAliasRegistryProvider
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

    /**
     * Applies {@code llm.canary} / {@code llm.shadow} rules to whichever routing beans ended up in
     * the context. Both classes are annotated with {@code @Service} / {@code @Component}, so in an
     * application that component-scans {@code com.enterprise.llm} the beans above back off; a
     * post-instantiation hook is therefore the only place that is guaranteed to see the real bean.
     */
    @Bean
    public SmartInitializingSingleton llmTrafficRuleApplier(
            ObjectProvider<CanaryRoutingService> canaryProvider,
            ObjectProvider<ShadowTrafficMirror> shadowProvider,
            LlmCanaryProperties canaryProperties,
            LlmShadowProperties shadowProperties) {
        return () -> {
            canaryProvider.ifAvailable(service -> applyCanaryRules(service, canaryProperties));
            shadowProvider.ifAvailable(mirror -> applyShadowRules(mirror, shadowProperties));
        };
    }

    private static void applyCanaryRules(CanaryRoutingService service,
                                         LlmCanaryProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        for (LlmCanaryProperties.Rule rule : properties.getRules()) {
            if (rule.getBaseModel() == null || rule.getCanaryModel() == null) {
                continue;
            }
            service.setRule(new CanaryRule(
                    rule.getBaseModel(),
                    rule.getCanaryModel(),
                    rule.getWeightPercent(),
                    rule.isStickyByTenant(),
                    rule.isStickyBySession()));
        }
        for (LlmCanaryProperties.TenantOverride override : properties.getOverrides()) {
            service.setTenantOverride(override.getTenantId(), override.getBaseModel(), override.getTargetModel());
        }
    }

    private static void applyShadowRules(ShadowTrafficMirror mirror,
                                         LlmShadowProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        for (LlmShadowProperties.Rule rule : properties.getRules()) {
            if (rule.getSourceModel() == null || rule.getShadowModel() == null) {
                continue;
            }
            mirror.setRule(new ShadowTrafficRule(
                    rule.getSourceModel(),
                    rule.getShadowModel(),
                    rule.getSampleRatio()));
        }
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
    @ConditionalOnMissingBean(LlmMetricsCollector.class)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public LlmMetricsCollector micrometerLlmMetricsCollector(
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            return new MicrometerLlmMetricsCollector(registry);
        }
        return NoOpMetricsCollector.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(LlmMetricsCollector.class)
    public LlmMetricsCollector noOpLlmMetricsCollector() {
        return NoOpMetricsCollector.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(AgentToolOrchestrator.class)
    public AgentToolOrchestrator agentToolOrchestrator(
            ToolRegistry toolRegistry,
            ObjectProvider<ToolActionGatekeeper> gatekeeperProvider,
            ObjectProvider<LlmMetricsCollector> metricsCollectorProvider
    ) {
        AgentToolOrchestrator orchestrator = new AgentToolOrchestrator(toolRegistry, gatekeeperProvider.getIfAvailable());
        if (metricsCollectorProvider.getIfAvailable() != null) {
            orchestrator.setMetricsCollector(metricsCollectorProvider.getIfAvailable());
        }
        return orchestrator;
    }

    @Bean
    @ConditionalOnMissingBean(StreamingAgentOrchestrator.class)
    public StreamingAgentOrchestrator streamingAgentOrchestrator(
            AgentToolOrchestrator agentToolOrchestrator
    ) {
        return new StreamingAgentOrchestrator(agentToolOrchestrator);
    }

    @Bean
    @ConditionalOnMissingBean(AgentSwarmOrchestrator.class)
    public AgentSwarmOrchestrator agentSwarmOrchestrator() {
        return new AgentSwarmOrchestrator();
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
            ObjectProvider<EmbeddingClient> embeddingClientProvider
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
    @ConditionalOnMissingBean(RagFaithfulnessEvaluator.class)
    public RagFaithfulnessEvaluator ragFaithfulnessEvaluator(
            @Value("${rag.guardrail.pass-threshold:0.65}") double passThreshold,
            @Value("${rag.guardrail.warn-threshold:0.40}") double warnThreshold
    ) {
        return new RagFaithfulnessEvaluator(passThreshold, warnThreshold);
    }

    @Bean
    @ConditionalOnMissingBean(RagPipelineService.class)
    public RagPipelineService ragPipelineService(
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<QueryRewriter> queryRewriterProvider,
            ObjectProvider<Reranker> rerankerProvider,
            ObjectProvider<RagFaithfulnessEvaluator> faithfulnessEvaluatorProvider,
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
    @ConditionalOnMissingBean(SessionStorageProvider.class)
    public SessionStorageProvider sessionStorageProvider() {
        return new InMemorySessionStorageProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SessionManager.class)
    public SessionManager sessionManager(SessionStorageProvider sessionStorageProvider) {
        return new SessionManager(sessionStorageProvider);
    }

    @Bean
    @ConditionalOnMissingBean(ModelAliasRegistry.class)
    public ModelAliasRegistry modelAliasRegistry() {
        return new ModelAliasRegistry();
    }


    @Bean
    @ConditionalOnMissingBean(ModelPricingRegistry.class)
    public ModelPricingRegistry modelPricingRegistry() {
        return new ModelPricingRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(CostCalculator.class)
    public CostCalculator costCalculator(ObjectProvider<ModelPricingRegistry> pricingRegistryProvider) {
        return new CostCalculator(pricingRegistryProvider.getIfAvailable(ModelPricingRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean(TenantBudgetManager.class)
    public TenantBudgetManager tenantBudgetManager() {
        return new TenantBudgetManager();
    }

    @Bean
    @ConditionalOnMissingBean(StreamingOutputGuardrail.class)
    public StreamingOutputGuardrail streamingOutputGuardrail(
            @Value("${llm.guardrail.streaming.mode:BLOCK}") String modeStr,
            @Value("${llm.guardrail.streaming.overlap-size:64}") int overlapSize
    ) {
        StreamingOutputGuardrail.Mode mode = "MASK".equalsIgnoreCase(modeStr)
                ? StreamingOutputGuardrail.Mode.MASK
                : StreamingOutputGuardrail.Mode.BLOCK;
        return new StreamingOutputGuardrail(mode, overlapSize);
    }
}

