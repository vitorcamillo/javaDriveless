package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class SdkProfileNormalizationResult {
    public String status = "ready";
    public SdkProfile profile;
    public List<String> warnings = new ArrayList<>();
    public List<String> conflicts = new ArrayList<>();

    public boolean isReady() {
        return "ready".equals(status);
    }

    void addConflict(String conflict) {
        status = "conflict";
        conflicts.add(conflict);
    }
}
