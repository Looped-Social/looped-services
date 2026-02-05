package com.looped.notif;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

public class NotifWorker {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> RESERVED_KEYS = Set.of(
            "type",
            "apns_token",
            "title",
            "body",
            "badge"
    );

    public static void main(String[] args) throws Exception {
        String queueUrl = env("SQS_NOTIF_QUEUE_URL");
        if (queueUrl == null || queueUrl.isBlank()) {
            System.out.println("SQS_NOTIF_QUEUE_URL is required");
            return;
        }
        String region = envOrDefault("AWS_REGION", "us-east-1");
        String bundleId = env("APNS_BUNDLE_ID");
        if (bundleId == null || bundleId.isBlank()) {
            System.out.println("APNS_BUNDLE_ID is required");
            return;
        }
        boolean sandbox = Boolean.parseBoolean(envOrDefault("APNS_SANDBOX", "true"));

        ApnsSigningKey signingKey = loadSigningKey();
        if (signingKey == null) {
            System.out.println("APNS signing key not configured");
            return;
        }

        ApnsClient apns = new ApnsClientBuilder()
                .setApnsServer(sandbox
                        ? ApnsClientBuilder.DEVELOPMENT_APNS_HOST
                        : ApnsClientBuilder.PRODUCTION_APNS_HOST)
                .setSigningKey(signingKey)
                .build();

        SqsClient sqs = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        int waitSeconds = Integer.parseInt(envOrDefault("SQS_WAIT_SECONDS", "10"));
        int maxMessages = Integer.parseInt(envOrDefault("SQS_MAX_MESSAGES", "5"));

        System.out.println("notif-worker started");
        while (true) {
            var receiveReq = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .waitTimeSeconds(waitSeconds)
                    .maxNumberOfMessages(maxMessages)
                    .build();
            var messages = sqs.receiveMessage(receiveReq).messages();
            if (messages == null || messages.isEmpty()) {
                continue;
            }
            for (Message message : messages) {
                boolean processed = handleMessage(apns, bundleId, message);
                if (processed) {
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                }
            }
        }
    }

    private static boolean handleMessage(ApnsClient apns, String bundleId, Message message) {
        Map<String, Object> event;
        try {
            event = MAPPER.readValue(message.body(), MAP_TYPE);
        } catch (Exception e) {
            System.out.println("failed to parse message: " + e.getMessage());
            return true;
        }
        SimpleApnsPushNotification notification = buildNotification(bundleId, event, Instant.now());
        if (notification == null) return true;

        try {
            PushNotificationResponse<SimpleApnsPushNotification> response = apns.sendNotification(notification).get();
            if (!response.isAccepted()) {
                String reason = response.getRejectionReason().orElse(null);
                System.out.println("push rejected: " + reason);
            }
            return true;
        } catch (Exception e) {
            System.out.println("push send failed: " + e.getMessage());
            return false;
        }
    }

    static SimpleApnsPushNotification buildNotification(String bundleId, Map<String, Object> event, Instant sentAt) {
        if (bundleId == null || bundleId.isBlank()) return null;
        if (event == null || event.isEmpty()) return null;
        String apnsToken = asString(event.get("apns_token"));
        String title = asString(event.get("title"));
        String body = asString(event.get("body"));
        if (apnsToken == null || apnsToken.isBlank()) return null;
        if (title == null || body == null) return null;

        SimpleApnsPayloadBuilder payloadBuilder = new SimpleApnsPayloadBuilder();
        payloadBuilder.setAlertTitle(title);
        payloadBuilder.setAlertBody(body);
        payloadBuilder.setSound("default");
        String type = asString(event.get("type"));
        payloadBuilder.addCustomProperty("type", type != null ? type : "push");
        Integer badge = asInt(event.get("badge"));
        if (badge != null && badge >= 0) {
            payloadBuilder.setBadgeNumber(badge);
        }
        for (var entry : event.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            if (RESERVED_KEYS.contains(entry.getKey())) continue;
            payloadBuilder.addCustomProperty(entry.getKey(), entry.getValue());
        }
        payloadBuilder.addCustomProperty("sent_at", (sentAt != null ? sentAt : Instant.now()).toString());
        String payload = payloadBuilder.build();

        return new SimpleApnsPushNotification(
                apnsToken,
                bundleId,
                payload
        );
    }

    private static ApnsSigningKey loadSigningKey() {
        String teamId = env("APNS_TEAM_ID");
        String keyId = env("APNS_KEY_ID");
        String base64Key = env("APNS_AUTH_KEY_P8");
        String keyPath = env("APNS_AUTH_KEY_PATH");
        if (teamId == null || keyId == null) {
            return null;
        }
        try {
            if (base64Key != null && !base64Key.isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(base64Key.getBytes(StandardCharsets.UTF_8));
                return ApnsSigningKey.loadFromInputStream(
                        new ByteArrayInputStream(bytes),
                        teamId,
                        keyId
                );
            }
            if (keyPath != null && !keyPath.isBlank()) {
                return ApnsSigningKey.loadFromPkcs8File(new File(keyPath), teamId, keyId);
            }
        } catch (Exception e) {
            System.out.println("failed to load APNS key: " + e.getMessage());
        }
        return null;
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return val == null || val.isBlank() ? fallback : val;
    }

    private static String asString(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l > Integer.MAX_VALUE ? Integer.MAX_VALUE : l.intValue();
        if (v instanceof Number n) return n.intValue();
        return null;
    }
}
