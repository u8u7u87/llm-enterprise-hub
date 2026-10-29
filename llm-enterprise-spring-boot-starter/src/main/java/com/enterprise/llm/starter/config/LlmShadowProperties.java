package com.enterprise.llm.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for shadow (mirrored) traffic, bound from {@code llm.shadow}.
 *
 * <p>Rules are registered into the {@code ShadowTrafficMirror} bean at startup. Disabled by default
 * with a zero sample ratio, so an accidental deployment cannot double the inference bill.
 */
@ConfigurationProperties(prefix = "llm.shadow")
public class LlmShadowProperties {

    private boolean enabled = false;
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = (rules != null) ? rules : new ArrayList<>();
    }

    public static class Rule {
        private String sourceModel;
        private String shadowModel;
        private double sampleRatio = 0.0;

        public String getSourceModel() {
            return sourceModel;
        }

        public void setSourceModel(String sourceModel) {
            this.sourceModel = sourceModel;
        }

        public String getShadowModel() {
            return shadowModel;
        }

        public void setShadowModel(String shadowModel) {
            this.shadowModel = shadowModel;
        }

        public double getSampleRatio() {
            return sampleRatio;
        }

        public void setSampleRatio(double sampleRatio) {
            this.sampleRatio = sampleRatio;
        }
    }
}
