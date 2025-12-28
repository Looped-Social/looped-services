package com.looped.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PushQueuePublisher {
    private final SqsClient sqs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String queueUrl;

    public PushQueuePublisher(SqsClient sqs,
                              @Value("${sqs.notifQueueUrl:}") String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
    }

    public boolean enabled() {
        return !queueUrl.isBlank();
    }

    public void enqueueAnnouncement(long userId,
                                    String apnsToken,
                                    String title,
                                    String body,
                                    String deeplink,
                                    String collapseId,
                                    String traceId) {
        if (!enabled()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "push.announcement");
        payload.put("user_id", userId);
        payload.put("apns_token", apnsToken);
        payload.put("title", title);
        payload.put("body", body);
        if (deeplink != null && !deeplink.isBlank()) {
            payload.put("deeplink", deeplink);
        }
        if (collapseId != null && !collapseId.isBlank()) {
            payload.put("collapse_id", collapseId);
        }
        if (traceId != null && !traceId.isBlank()) {
            payload.put("trace_id", traceId);
        }
        String json = toJson(payload);
        if (json == null) return;
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(json)
                .build());
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
