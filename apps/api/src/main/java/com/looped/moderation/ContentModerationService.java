package com.looped.moderation;

import org.springframework.stereotype.Service;

@Service
public class ContentModerationService {
    private final ModerationProperties props;
    private final BlocklistService blocklist;
    private final OpenAiModerationClient openai;

    public ContentModerationService(ModerationProperties props, BlocklistService blocklist, OpenAiModerationClient openai) {
        this.props = props;
        this.blocklist = blocklist;
        this.openai = openai;
    }

    public Decision evaluateText(String text) {
        if (!props.isEnabled()) return Decision.allow();
        if (text == null || text.isBlank()) return Decision.allow();

        var match = blocklist.match(text);
        if (match != null) {
            return Decision.quarantine("blocklist", "policy:blocklist", match);
        }

        var openaiRes = openai.moderateText(text);
        if (openai.shouldQuarantine(openaiRes)) {
            String reason = openaiRes.trueCategories() == null || openaiRes.trueCategories().isEmpty()
                    ? "policy:openai:flagged"
                    : "policy:openai:flagged:" + String.join(",", openaiRes.trueCategories());
            return Decision.quarantine("openai", reason);
        }

        return Decision.allow();
    }

    public Decision evaluateTextForAnon(String text) {
        Decision d = evaluateText(text);
        if (d.action == Action.QUARANTINE) {
            return Decision.rejectAnon("policy:anon_under_review");
        }
        return d;
    }

    public enum Action { ALLOW, QUARANTINE, REJECT_ANON }

    public record Decision(Action action, String source, String reason, BlocklistService.Match blocklistMatch) {
        static Decision allow() { return new Decision(Action.ALLOW, null, null, null); }
        static Decision quarantine(String source, String reason) { return new Decision(Action.QUARANTINE, source, reason, null); }
        static Decision quarantine(String source, String reason, BlocklistService.Match blocklistMatch) {
            return new Decision(Action.QUARANTINE, source, reason, blocklistMatch);
        }
        static Decision rejectAnon(String reason) { return new Decision(Action.REJECT_ANON, "policy", reason, null); }
    }
}
