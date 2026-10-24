package com.enterprise.llm.router.shadow;

/**
 * Rule configuration for shadow traffic mirroring.
 *
 * @param sourceModel original model to be mirrored
 * @param shadowModel candidate shadow model to receive async shadow traffic
 * @param sampleRatio fraction of traffic to mirror (0.0 to 1.0)
 */
public record ShadowTrafficRule(
        String sourceModel,
        String shadowModel,
        double sampleRatio
) {
    public ShadowTrafficRule {
        if (sourceModel == null || shadowModel == null) {
            throw new IllegalArgumentException("sourceModel and shadowModel must not be null");
        }
        if (sampleRatio < 0.0 || sampleRatio > 1.0) {
            throw new IllegalArgumentException("sampleRatio must be between 0.0 and 1.0, was: " + sampleRatio);
        }
    }
}
