package com.looped.polls;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class PollRequests {
    private PollRequests() {}

    public record PostPollCreate(
            @NotBlank @Size(max = 200) String question,
            @NotNull @Size(min = 2, max = 20) List<@NotBlank @Size(max = 120) String> options,
            @Min(1) @Max(5) Integer maxSelections,
            OffsetDateTime closesAt
    ) {}

    public record VoteRequest(@Valid @NotNull @Size(min = 1, max = 5) List<Long> selectedOptionIds) {}
}

