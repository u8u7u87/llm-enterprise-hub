package com.enterprise.llm.backend;

/**
 * Enumeration of supported inference backend engine types.
 */
public enum EngineType {
    DIRECT("direct"),       // Frameworkless / direct connection: Ollama, local llama.cpp, Cloud OpenAI APIs
    VLLM("vllm"),           // vLLM cluster with PagedAttention & Automatic Prefix Caching (APC)
    SGLANG("sglang"),       // SGLang cluster with RadixAttention tree-structured cache
    TRITON("triton"),       // Triton Inference Server with TRT-LLM & KServe v2 health endpoints
    RAY("ray"),             // Ray / Ray Serve distributed serving cluster
    CUSTOM("custom");       // User-defined 3rd-party pluggable extension

    private final String code;

    EngineType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static EngineType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DIRECT;
        }
        String clean = code.trim();
        for (EngineType t : values()) {
            if (t.code.equalsIgnoreCase(clean) || t.name().equalsIgnoreCase(clean)) {
                return t;
            }
        }
        return CUSTOM;
    }
}
