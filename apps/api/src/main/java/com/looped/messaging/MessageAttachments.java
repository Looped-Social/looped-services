package com.looped.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class MessageAttachments {
    private static final ObjectMapper mapper = new ObjectMapper();

    private MessageAttachments() {}

    public static List<MessageAttachment> parse(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("attachments_must_be_array");

        List<MessageAttachment> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) continue;
            if (item.isTextual()) {
                String key = item.asText(null);
                if (key == null || key.isBlank()) continue;
                out.add(new MessageAttachment(key, "image", null, null, null, null, null));
                continue;
            }
            if (item.isObject()) {
                try {
                    MessageAttachment att = mapper.treeToValue(item, MessageAttachment.class);
                    if (att == null || att.url() == null || att.url().isBlank()) {
                        throw new IllegalArgumentException("attachment_url_required");
                    }
                    out.add(att);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException("invalid_attachment");
                }
                continue;
            }
            throw new IllegalArgumentException("invalid_attachment");
        }
        return List.copyOf(out);
    }

    public static boolean validDmKeys(List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return true;
        for (MessageAttachment a : attachments) {
            if (a == null) continue;
            String url = a.url();
            if (url == null || url.isBlank()) continue;
            if (!url.startsWith("dm/")) return false;
        }
        return true;
    }
}

