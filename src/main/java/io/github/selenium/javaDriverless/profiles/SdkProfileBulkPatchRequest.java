package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class SdkProfileBulkPatchRequest {
    public List<String> selectors = new ArrayList<>();
    public boolean dryRun = false;
    public String folder;
    public String status;
    public List<String> addTags = new ArrayList<>();
    public List<String> removeTags = new ArrayList<>();
    public Boolean pinned;
    public Boolean locked;
    public String notes;
    public String mainWebsite;
}
