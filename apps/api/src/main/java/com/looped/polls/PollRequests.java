package com.looped.polls;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    public record VoteRequest(
            @Valid @NotNull @Size(min = 1, max = 5) List<Long> selectedOptionIds,
            @JsonAlias("as_anon") Boolean asAnon,
            @JsonAlias("anon_profile_id") Long anonProfileId,
            @JsonAlias("anon_cert") String anonCert,
            @JsonAlias("anon_cert_kid") String anonCertKid,
            @JsonAlias("anon_sig") String anonSig
    ) {
        com.looped.anon.AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            if (asAnon == null || !asAnon) return false;
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank();
        }
    }
}
