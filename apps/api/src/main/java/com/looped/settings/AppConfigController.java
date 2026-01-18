package com.looped.settings;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/app-config")
public class AppConfigController {
    private final AppConfigService appConfig;

    public AppConfigController(AppConfigService appConfig) {
        this.appConfig = appConfig;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(appConfig.publicConfig());
    }
}
