package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Path;
import java.util.List;

public class JavaDriverlessProfiles {
    private final SdkProfileCatalog catalog;

    private JavaDriverlessProfiles(SdkProfileCatalog catalog) {
        this.catalog = catalog;
    }

    public static JavaDriverlessProfiles at(Path root) {
        return new JavaDriverlessProfiles(SdkProfileCatalog.at(root));
    }

    public SdkProfileCatalog catalog() {
        return catalog;
    }

    public SdkProfile create(SdkProfilePresetRequest request) {
        return catalog.save(SdkProfileGenerator.fromPreset(request));
    }

    public SdkProfile save(SdkProfile profile) {
        return catalog.save(profile);
    }

    public SdkProfile select(String selector) {
        return catalog.select(selector);
    }

    public List<SdkProfile> list() {
        return catalog.list();
    }

    public List<SdkProfile> list(SdkProfileListQuery query) {
        return catalog.list(query);
    }

    public ProfileLaunchPlan plan(String selector) {
        return ProfileLaunchPlanner.planCatalogProfile(select(selector), null, catalog);
    }

    public ProfileLaunchPlan plan(String selector, ProxyEvidence evidence) {
        return ProfileLaunchPlanner.planCatalogProfile(select(selector), evidence, catalog);
    }

    public ChromeOptions options(String selector) {
        SdkProfile profile = select(selector);
        return ProfileChromeOptionsFactory.fromCatalogProfile(
                profile, ProfileLaunchPlanner.planCatalogProfile(profile, null, catalog), catalog);
    }

    public ChromeOptions options(String selector, ProxyEvidence evidence) {
        SdkProfile profile = select(selector);
        return ProfileChromeOptionsFactory.fromCatalogProfile(
                profile, ProfileLaunchPlanner.planCatalogProfile(profile, evidence, catalog), catalog);
    }

    public SdkProfileCloneResult cloneProfiles(SdkProfileCloneRequest request) {
        return catalog.cloneProfiles(request);
    }

    public SdkProfileBulkPatchResult bulkPatch(SdkProfileBulkPatchRequest request) {
        return catalog.bulkPatch(request);
    }
}
