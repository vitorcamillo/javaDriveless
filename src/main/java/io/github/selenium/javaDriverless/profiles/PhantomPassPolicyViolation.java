package io.github.selenium.javaDriverless.profiles;

/** A redacted, machine-readable PhantomPass policy failure. */
public record PhantomPassPolicyViolation(String code, String field, String expected) {
    public PhantomPassPolicyViolation {
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
