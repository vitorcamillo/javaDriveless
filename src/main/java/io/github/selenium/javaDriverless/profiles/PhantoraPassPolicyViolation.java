package io.github.selenium.javaDriverless.profiles;

/** A redacted, machine-readable PhantoraPass policy failure. */
public record PhantoraPassPolicyViolation(String code, String field, String expected) {
    public PhantoraPassPolicyViolation {
        code = requireText(code, "code");
        field = requireText(field, "field");
        expected = requireText(expected, "expected");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " não pode ser vazio");
        }
        return value.trim();
    }
}
