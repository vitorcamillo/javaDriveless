package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.List;

public class SdkProfileBulkPatchResult {
    public boolean dryRun;
    public List<SdkProfileBulkPatchItem> items = new ArrayList<>();

    public static class SdkProfileBulkPatchItem {
        public String selector;
        public String id;
        public String status;

        public SdkProfileBulkPatchItem() {
        }

        public SdkProfileBulkPatchItem(String selector, String id, String status) {
            this.selector = selector;
            this.id = id;
            this.status = status;
        }
    }
}
