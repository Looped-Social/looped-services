package com.looped.anon;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "anon.issuer")
public class AnonIssuerProperties {
    private String kid;
    private String privateKeyPem;
    private String publicKeyPem;
    private Duration maxAge = Duration.ofDays(365);
    private String kek;
    private Integer keyBits = 2048;

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }

    public String getKek() {
        return kek;
    }

    public void setKek(String kek) {
        this.kek = kek;
    }

    public Integer getKeyBits() {
        return keyBits;
    }

    public void setKeyBits(Integer keyBits) {
        this.keyBits = keyBits;
    }
}
