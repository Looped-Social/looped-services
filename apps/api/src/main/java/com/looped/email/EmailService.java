package com.looped.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final SesClient ses;
    private final EmailProperties props;

    public EmailService(SesClient ses, EmailProperties props) {
        this.ses = ses;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.isEnabled() && props.getFrom() != null && !props.getFrom().isBlank();
    }

    public void sendCommunityVerificationEmail(String to, long communityId, String communityName, String code) {
        if (!isEnabled()) return;
        String subject = communityName == null || communityName.isBlank()
                ? "Verify your community email"
                : "Verify your " + communityName + " email";
        String link = buildVerifyLink(communityId, code);
        String text = buildTextBody(communityName, code, link);
        String html = buildHtmlBody(communityName, code, link);
        sendEmail(to, subject, text, html);
    }

    public void sendUserVerificationEmail(String to, String code) {
        if (!isEnabled()) return;
        String subject = "Verify your email";
        String link = buildVerifyLink(null, code);
        String text = buildTextBody(null, code, link);
        String html = buildHtmlBody(null, code, link);
        sendEmail(to, subject, text, html);
    }

    private void sendEmail(String to, String subject, String textBody, String htmlBody) {
        Destination destination = Destination.builder().toAddresses(to).build();
        Message message = Message.builder()
                .subject(Content.builder().data(subject).charset("UTF-8").build())
                .body(Body.builder()
                        .text(Content.builder().data(textBody).charset("UTF-8").build())
                        .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                        .build())
                .build();
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .source(props.getFrom())
                .destination(destination)
                .message(message);
        if (props.getReplyTo() != null && !props.getReplyTo().isBlank()) {
            request.replyToAddresses(props.getReplyTo());
        }
        if (props.getConfigurationSet() != null && !props.getConfigurationSet().isBlank()) {
            request.configurationSetName(props.getConfigurationSet().trim());
        }
        SendEmailResponse res = ses.sendEmail(request.build());
        if (res != null && res.messageId() != null) {
            log.info("SES sent messageId={} to={}", res.messageId(), to);
        }
    }

    private String buildVerifyLink(Long communityId, String code) {
        String base = props.getVerifyBaseUrl();
        if (base == null || base.isBlank()) return null;
        String trimmed = base.trim();
        String sep = trimmed.contains("?") ? "&" : "?";
        String encoded = URLEncoder.encode(code, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(trimmed).append(sep).append("code=").append(encoded);
        if (communityId != null) {
            out.append("&communityId=").append(communityId);
        }
        return out.toString();
    }

    private String buildTextBody(String communityName, String code, String link) {
        StringBuilder out = new StringBuilder();
        if (communityName != null && !communityName.isBlank()) {
            out.append("Verify your ").append(communityName).append(" email\n\n");
        } else {
            out.append("Verify your email\n\n");
        }
        out.append("Your verification code: ").append(code).append("\n");
        out.append("This code expires soon.\n");
        if (link != null) {
            out.append("\nOr click this link:\n").append(link).append("\n");
        }
        out.append("\nIf you did not request this, you can ignore this email.\n");
        return out.toString();
    }

    private String buildHtmlBody(String communityName, String code, String link) {
        StringBuilder out = new StringBuilder();
        out.append("<html><body>");
        if (communityName != null && !communityName.isBlank()) {
            out.append("<p>Verify your ").append(escape(communityName)).append(" email</p>");
        } else {
            out.append("<p>Verify your email</p>");
        }
        out.append("<p><strong>Verification code:</strong> ").append(escape(code)).append("</p>");
        out.append("<p>This code expires soon.</p>");
        if (link != null) {
            out.append("<p><a href=\"").append(escape(link)).append("\">Verify now</a></p>");
        }
        out.append("<p>If you did not request this, you can ignore this email.</p>");
        out.append("</body></html>");
        return out.toString();
    }

    private String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
