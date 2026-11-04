package io.github.selenium.javaDriverless.profiles;

import java.util.HashSet;
import java.util.Set;

public class SdkProfileListQuery {
    public String folder;
    public String status;
    public Set<String> tags = new HashSet<>();
    public String query;
    public Boolean pinned;
    public Boolean locked;
    public String sortBy = "id";
    public boolean descending = false;
    public int offset = 0;
    public int limit = 0;
}
