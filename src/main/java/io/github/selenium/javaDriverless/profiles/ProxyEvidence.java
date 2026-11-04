package io.github.selenium.javaDriverless.profiles;

public class ProxyEvidence {
    public boolean success = false;
    public boolean skipped = false;
    public String skipReason = "";
    public String checkedAt;
    public String ip;
    public String countryCode;
    public String timezoneId;
    public Double latitude;
    public Double longitude;
    public boolean dnsLeak = false;
    public boolean webRtcLeak = false;
    public boolean ipv6Leak = false;
    public boolean headerLeak = false;
    public boolean localityMismatch = false;
    public String source = "";
    public String checkedBy = "";
    public String provenance = "live_check";
}
