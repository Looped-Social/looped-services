package com.looped.links;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "universal-links")
public class UniversalLinksProperties {
    private String appleTeamId = "REPLACE_TEAM_ID";
    private String iosBundleId = "REPLACE_BUNDLE_ID";
    private int cacheMaxAgeSeconds = 300;
    private String aasaVersion = "v1";
    private List<String> iosPaths = new ArrayList<>(List.of(
            "/p/*",
            "/u/*",
            "/app/*",
            "/login*",
            "/"
    ));
    private String androidPackageName = "";
    private String androidSha256CertFingerprint = "";

    public String getAppleTeamId() {
        return appleTeamId;
    }

    public void setAppleTeamId(String appleTeamId) {
        this.appleTeamId = appleTeamId;
    }

    public String getIosBundleId() {
        return iosBundleId;
    }

    public void setIosBundleId(String iosBundleId) {
        this.iosBundleId = iosBundleId;
    }

    public int getCacheMaxAgeSeconds() {
        return cacheMaxAgeSeconds;
    }

    public void setCacheMaxAgeSeconds(int cacheMaxAgeSeconds) {
        this.cacheMaxAgeSeconds = cacheMaxAgeSeconds;
    }

    public String getAasaVersion() {
        return aasaVersion;
    }

    public void setAasaVersion(String aasaVersion) {
        this.aasaVersion = aasaVersion;
    }

    public List<String> getIosPaths() {
        return iosPaths;
    }

    public void setIosPaths(List<String> iosPaths) {
        this.iosPaths = iosPaths;
    }

    public String getAndroidPackageName() {
        return androidPackageName;
    }

    public void setAndroidPackageName(String androidPackageName) {
        this.androidPackageName = androidPackageName;
    }

    public String getAndroidSha256CertFingerprint() {
        return androidSha256CertFingerprint;
    }

    public void setAndroidSha256CertFingerprint(String androidSha256CertFingerprint) {
        this.androidSha256CertFingerprint = androidSha256CertFingerprint;
    }
}
