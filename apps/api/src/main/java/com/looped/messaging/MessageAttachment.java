package com.looped.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record MessageAttachment(
        @NotBlank
        @JsonProperty("url")
        @JsonAlias({"key"})
        String url,

        @JsonProperty("type")
        String type,

        @JsonProperty("width")
        Integer width,

        @JsonProperty("height")
        Integer height,

        @JsonProperty("size_bytes")
        @JsonAlias({"sizeBytes"})
        Long sizeBytes,

        @JsonProperty("duration_seconds")
        @JsonAlias({"durationSeconds"})
        Integer durationSeconds,

        @JsonProperty("thumbnail_url")
        @JsonAlias({"thumbnailUrl"})
        String thumbnailUrl
) {}

