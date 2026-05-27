package dev.topo.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of post-transform verification.
 */
public class PostTransformResult {
    public int visibilityVerified = 0;
    public int visibilityMismatch = 0;
    public List<String> failures = new ArrayList<>();

    public boolean passed() {
        return visibilityMismatch == 0 && failures.isEmpty();
    }

    public int totalChecks() {
        return visibilityVerified + visibilityMismatch;
    }
}
