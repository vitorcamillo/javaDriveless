package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class SdkProfileArchiveReport {
    public boolean valid = true;
    public List<String> profileIds = new ArrayList<>();
    public List<String> errors = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    public void addError(String error) {
        valid = false;
        errors.add(error);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }
}
