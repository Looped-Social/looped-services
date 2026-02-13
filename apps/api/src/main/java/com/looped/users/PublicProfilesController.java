package com.looped.users;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/public/profiles")
public class PublicProfilesController {
    private final PublicProfilesService service;

    public PublicProfilesController(PublicProfilesService service) {
        this.service = service;
    }

    @GetMapping("/{username}")
    public ResponseEntity<?> get(@PathVariable("username") String username) {
        var res = service.getByUsername(username);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "profile_not_found",
                    "message", "Profile not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "profile_unavailable",
                    "message", "Profile is unavailable"
            ));
            case OK -> ResponseEntity.ok(res.profile());
        };
    }
}
