package io.github.selenium.javaDriverless.profiles;

import java.util.List;

/** Immutable verdict returned by the PhantoraPass policy boundary. */
public final class PhantoraPassPolicyResult {
    private final PhantoraPassProfile evaluatedProfile;
    private final List<PhantoraPassPolicyViolation> violations;

    PhantoraPassPolicyResult(
            PhantoraPassProfile evaluatedProfile,
            List<PhantoraPassPolicyViolation> violations) {
        this.evaluatedProfile = evaluatedProfile;
        this.violations = List.copyOf(violations);
    }

    public boolean accepted() {
        return violations.isEmpty();
    }

    public List<PhantoraPassPolicyViolation> violations() {
        return violations;
    }

    public PhantoraPassProfile evaluatedProfile() {
        return evaluatedProfile == null ? null : evaluatedProfile.copy();
    }

    PhantoraPassProfile requireAcceptedProfile() {
        if (!accepted()) {
            String summary = violations.stream()
                    .map(violation -> violation.code() + "[" + violation.field()
                            + ": expected " + violation.expected() + "]")
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalArgumentException("PhantoraPass policy rejected: " + summary);
        }
        return evaluatedProfile.copy();
    }
}
