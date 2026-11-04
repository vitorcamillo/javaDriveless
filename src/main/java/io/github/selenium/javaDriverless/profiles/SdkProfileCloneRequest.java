package io.github.selenium.javaDriverless.profiles;

public class SdkProfileCloneRequest {
    public String sourceSelector;
    public String idPrefix;
    public String namePrefix;
    public int count = 1;
    public boolean dryRun = false;
    public boolean randomize = false;
    public Long seed;

    public SdkProfileCloneRequest() {
    }

    public SdkProfileCloneRequest(String sourceSelector, String idPrefix, String namePrefix, int count) {
        this.sourceSelector = sourceSelector;
        this.idPrefix = idPrefix;
        this.namePrefix = namePrefix;
        this.count = count;
    }
}
