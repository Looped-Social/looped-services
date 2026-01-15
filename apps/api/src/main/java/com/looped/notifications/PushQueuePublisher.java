package com.looped.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final java.util.Set<String> RESERVED_KEYS = java.util.Set.of(
            "type",
            "notification_id",
            "user_id",
            "apns_token",
            "title",
            "body",
            "deeplink",
            "collapse_id",
            "trace_id",
            "badge"
    );

    public PushQueuePublisher(ObjectProvider<SqsClient> sqsProvider,
                              @Value("${sqs.notifQueueUrl:}") String queueUrl) {
        this.sqs = sqsProvider.getIfAvailable();
        this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
    }

    public boolean enabled() {
        return sqs != null && !queueUrl.isBlank();
    }

    public void enqueueNotification(long userId,
                                    String apnsToken,
                                    String type,
                                    long notificationId,
                                    String title,
                                    String body,
                                    String deeplink,
                                    String collapseId,
                                    String traceId) {
        enqueueNotification(userId, apnsToken, type, notificationId, title, body, deeplink, collapseId, traceId, null, null);
    }

    public void enqueueNotification(long userId,
                                    String apnsToken,
                                    String type,
                                    long notificationId,
                                    String title,
                                    String body,
                                    String deeplink,
                                    String collapseId,
                                    String traceId,
                                    Integer badge,
                                    Map<String, Object> userInfo) {
        if (!enabled()) return;
        if (apnsToken == null || apnsToken.isBlank()) return;
        if (title == null || body == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (notificationId > 0) {
            payload.put("notification_id", notificationId);
        }
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
        if (badge != null && badge >= 0) {
            payload.put("badge", badge);
        }
        if (userInfo != null && !userInfo.isEmpty()) {
            for (var entry : userInfo.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                if (RESERVED_KEYS.contains(entry.getKey())) continue;
                if (payload.containsKey(entry.getKey())) continue;
                payload.put(entry.getKey(), entry.getValue());
            }
        }
        String json = toJson(payload);
        if (json == null) return;
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(json)
                .build());
    }

    public void enqueueAnnouncement(long userId,
                                    String apnsToken,
                                    String title,
                                    String body,
                                    String deeplink,
                                    String collapseId,
                                    String traceId) {
        enqueueNotification(userId, apnsToken, "announcement", 0L, title, body, deeplink, collapseId, traceId);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
