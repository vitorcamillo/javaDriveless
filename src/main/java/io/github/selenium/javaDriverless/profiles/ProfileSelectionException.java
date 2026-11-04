package io.github.selenium.javaDriverless.profiles;

public class ProfileSelectionException extends ProfileCatalogException {
    public enum Reason {
        MISSING,
        AMBIGUOUS
    }

    private final Reason reason;

    public ProfileSelectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
