package com.enterprise.llm.agent.swarm;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe blackboard context shared across collaborating agents in a swarm.
 */
public class AgentSharedContext {

    private final Map<String, Object> store = new ConcurrentHashMap<>();

    public AgentSharedContext() {
    }

    public AgentSharedContext(Map<String, Object> initialValues) {
        if (initialValues != null) {
            store.putAll(initialValues);
        }
    }

    public Object get(String key) {
        return store.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object val = store.get(key);
        if (val != null && type.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    public void set(String key, Object value) {
        if (key != null) {
            if (value != null) {
                store.put(key, value);
            } else {
                store.remove(key);
            }
        }
    }

    public void putAll(Map<String, Object> entries) {
        if (entries != null) {
            store.putAll(entries);
        }
    }

    public boolean has(String key) {
        return store.containsKey(key);
    }

    public void remove(String key) {
        store.remove(key);
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(store));
    }
}
