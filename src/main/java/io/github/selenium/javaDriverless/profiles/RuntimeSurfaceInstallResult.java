package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class RuntimeSurfaceInstallResult {
    public List<String> installed = new ArrayList<>();
    public List<String> missing = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    public boolean isComplete() {
        return missing.isEmpty();
    }
}
