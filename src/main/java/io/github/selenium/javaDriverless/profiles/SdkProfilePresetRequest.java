package io.github.selenium.javaDriverless.profiles;

public class SdkProfilePresetRequest {
    public String id;
    public String name;
    public String countryCode = "US";
    /** Canonical preset: windows, linux, docker, phantompass or seleniumlike. */
    public String preset = "";
    /** @deprecated use {@link #preset}; retained for source/JSON migration only. */
    @Deprecated
    public String os = "";
    public String browserChannel = "chrome";
    public boolean headless = false;
    public boolean mobile = false;
    public boolean cleanBaseline = false;
    public boolean diversityEnabled = false;
    /** Null lets the selected preset choose its fixed policy. */
    public String runtimePolicy;
    public Long seed;

    public SdkProfilePresetRequest() {
    }

    public SdkProfilePresetRequest(String id, String name, String countryCode, String os) {
        this.id = id;
        this.name = name;
        this.countryCode = countryCode;
        this.preset = os;
        this.os = os;
    }

    String selectedPreset() {
        if (preset != null && !preset.isBlank()) {
            return preset;
        }
        if (os != null && !os.isBlank()) {
            return os;
        }
        return SdkProfilePreset.defaultRuntime().id();
    }
}
