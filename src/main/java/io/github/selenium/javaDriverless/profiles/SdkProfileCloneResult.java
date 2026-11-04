package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class SdkProfileCloneResult {
    public boolean dryRun;
    public List<SdkProfileCloneItem> items = new ArrayList<>();

    public static class SdkProfileCloneItem {
        public String id;
        public String name;
        public String status;
        public Long seed;

        public SdkProfileCloneItem() {
        }

        public SdkProfileCloneItem(String id, String name, String status, Long seed) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.seed = seed;
        }
    }
}
