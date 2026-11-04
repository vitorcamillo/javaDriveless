package io.github.selenium.javaDriverless.profiles;

import java.util.List;

/** Immutable verdict returned by the PhantomPass policy boundary. */
public final class PhantomPassPolicyResult {
    private final PhantomPassProfile evaluatedProfile;
    private final List<PhantomPassPolicyViolation> violations;

    PhantomPassPolicyResult(
            PhantomPassProfile evaluatedProfile,
            List<PhantomPassPolicyViolation> violations) {
        this.evaluatedProfile = evaluatedProfile;
        this.violations = List.copyOf(violations);
    }

    public boolean accepted() {
        return violations.isEmpty();
    }

    public List<PhantomPassPolicyViolation> violations() {
        return violations;
    }

    public PhantomPassProfile evaluatedProfile() {
        return evaluatedProfile == null ? null : evaluatedProfile.copy();
    }

    PhantomPassProfile requireAcceptedProfile() {
        if (!accepted()) {
            String summary = violations.stream()
                    .map(violation -> violation.code() + "[" + violation.field()
                            + ": expected " + violation.expected() + "]")
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalArgumentException("PhantomPass policy rejected: " + summary);
        }
        return evaluatedProfile.copy();
    }
}
