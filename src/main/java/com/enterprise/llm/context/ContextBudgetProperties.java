package com.enterprise.llm.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm.context")
public class ContextBudgetProperties {

    private boolean enabled = true;
    private int maxContextTokens = 8192;
    private double compressionTriggerRatio = 0.75;
    private int reservedResponseTokens = 1024;
    private int keepLastMessages = 4;
    private boolean toolPruningEnabled = true;
    private int toolPruningThresholdChars = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public double getCompressionTriggerRatio() {
        return compressionTriggerRatio;
    }

    public void setCompressionTriggerRatio(double compressionTriggerRatio) {
        this.compressionTriggerRatio = compressionTriggerRatio;
    }

    public int getReservedResponseTokens() {
        return reservedResponseTokens;
    }

    public void setReservedResponseTokens(int reservedResponseTokens) {
        this.reservedResponseTokens = reservedResponseTokens;
    }

    public int getKeepLastMessages() {
        return keepLastMessages;
    }

    public void setKeepLastMessages(int keepLastMessages) {
        this.keepLastMessages = keepLastMessages;
    }

    public boolean isToolPruningEnabled() {
        return toolPruningEnabled;
    }

    public void setToolPruningEnabled(boolean toolPruningEnabled) {
        this.toolPruningEnabled = toolPruningEnabled;
    }

    public int getToolPruningThresholdChars() {
        return toolPruningThresholdChars;
    }

    public void setToolPruningThresholdChars(int toolPruningThresholdChars) {
        this.toolPruningThresholdChars = toolPruningThresholdChars;
    }
}
