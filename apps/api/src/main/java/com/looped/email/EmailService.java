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
import java.util.Locale;

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

    public void sendCommunityVerificationEmail(String to, long communityId, String communityName, String code, int ttlSeconds) {
        if (!isEnabled()) return;
        String subject = communityName == null || communityName.isBlank()
                ? "Verify your community email"
                : "Verify your " + communityName + " email";
        String link = buildVerifyLink(communityId, code);
        String text = buildTextBody(communityName, code, link, ttlSeconds);
        String html = buildHtmlBody(communityName, code, link, ttlSeconds);
        sendEmail(to, subject, text, html);
    }

    public void sendUserVerificationEmail(String to, String code, int ttlSeconds) {
        if (!isEnabled()) return;
        String subject = "Verify your Looped signup";
        String link = buildVerifyLink(null, code);
        String text = buildTextBody(null, code, link, ttlSeconds);
        String html = buildHtmlBody(null, code, link, ttlSeconds);
        sendEmail(to, subject, text, html);
    }

    private void sendEmail(String to, String subject, String textBody, String htmlBody) {
        sendEmailFrom(props.getFrom(), to, subject, textBody, htmlBody);
    }

    private void sendEmailFrom(String from, String to, String subject, String textBody, String htmlBody) {
        if (from == null || from.isBlank()) return;
        Destination destination = Destination.builder().toAddresses(to).build();
        Message message = Message.builder()
                .subject(Content.builder().data(subject).charset("UTF-8").build())
                .body(Body.builder()
                        .text(Content.builder().data(textBody).charset("UTF-8").build())
                        .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                        .build())
                .build();
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .source(from)
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
            log.info("SES sent messageId={}", res.messageId());
        }
    }

    public void sendCommunityVerifiedEmail(String to, String communityName) {
        if (!isEnabled()) return;
        if (to == null || to.isBlank()) return;
        String name = communityName == null || communityName.isBlank() ? "your community" : communityName.trim();
        String subject = "You're verified for " + name;
        String text = "Hey — you're verified for " + name + ".\n\nYou're all set! Can't wait to see what you share.\n";
        String html = "<html><body style=\"margin:0;padding:0;background-color:#ffffff;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;\">" +
                "<div style=\"max-width:560px;margin:0 auto;padding:32px 16px;\">" +
                "<div style=\"font-size:22px;font-weight:700;margin-bottom:8px;\">You're verified for " + escape(name) + "</div>" +
                "<div style=\"font-size:14px;color:#6b7280;\">You're all set! Can't wait to see what you share.</div>" +
                "</div></body></html>";
        sendEmail(to, subject, text, html);
    }

    public void sendCommunityVerificationRejectedEmail(String to, String communityName, String rejectReason) {
        if (!isEnabled()) return;
        if (to == null || to.isBlank()) return;
        String name = communityName == null || communityName.isBlank() ? "your community" : communityName.trim();
        String subject = "Verification rejected for " + name;
        StringBuilder text = new StringBuilder();
        text.append("Your verification for ").append(name).append(" was rejected.\n");
        if (rejectReason != null && !rejectReason.isBlank()) {
            text.append("\nReason: ").append(rejectReason.trim()).append("\n");
        }
        StringBuilder html = new StringBuilder();
        html.append("<html><body style=\"margin:0;padding:0;background-color:#ffffff;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;\">");
        html.append("<div style=\"max-width:560px;margin:0 auto;padding:32px 16px;\">");
        html.append("<div style=\"font-size:22px;font-weight:700;margin-bottom:8px;\">Verification rejected</div>");
        html.append("<div style=\"font-size:14px;color:#6b7280;margin-bottom:12px;\">Your verification for ").append(escape(name)).append(" was rejected.</div>");
        if (rejectReason != null && !rejectReason.isBlank()) {
            html.append("<div style=\"font-size:13px;color:#374151;\">Reason: ").append(escape(rejectReason.trim())).append("</div>");
        }
        html.append("</div></body></html>");
        sendEmail(to, subject, text.toString(), html.toString());
    }

    public void sendUserVerifiedEmail(String to) {
        if (!isEnabled()) return;
        if (to == null || to.isBlank()) return;
        String subject = "You're verified on Looped";
        String text = "You're verified on Looped.\n\nYou're all set! Can't wait to see what you share.\n";
        String html = "<html><body style=\"margin:0;padding:0;background-color:#ffffff;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;\">" +
                "<div style=\"max-width:560px;margin:0 auto;padding:32px 16px;\">" +
                "<div style=\"font-size:22px;font-weight:700;margin-bottom:8px;\">You're verified</div>" +
                "<div style=\"font-size:14px;color:#6b7280;\">You're all set! Can't wait to see what you share.</div>" +
                "</div></body></html>";
        sendEmail(to, subject, text, html);
    }

    public void sendAdminOpsEmail(String to, String subject, String textBody, String htmlBody) {
        if (!isEnabled()) return;
        if (to == null || to.isBlank()) return;
        String resolvedSubject = (subject == null || subject.isBlank()) ? "Looped admin alert" : subject.trim();
        String resolvedText = textBody == null ? "" : textBody;
        String resolvedHtml;
        if (htmlBody == null || htmlBody.isBlank()) {
            resolvedHtml = buildAdminMessageHtml(resolvedSubject, resolvedText);
        } else if (looksLikeHtmlDocument(htmlBody)) {
            resolvedHtml = htmlBody;
        } else {
            resolvedHtml = wrapAdminShell(resolvedSubject, htmlBody);
        }
        String from = resolveAdminFrom();
        sendEmailFrom(from, to, resolvedSubject, resolvedText, resolvedHtml);
    }

    private String resolveAdminFrom() {
        if (props.getAdminFrom() != null && !props.getAdminFrom().isBlank()) {
            return props.getAdminFrom().trim();
        }
        return props.getFrom();
    }

    private String buildAdminMessageHtml(String subject, String textBody) {
        String body = escape(textBody).replace("\n", "<br/>");
        String inner = "<div style=\"font-size:14px;line-height:1.65;color:#374151;\">" + body + "</div>";
        return wrapAdminShell(subject, inner);
    }

    private String wrapAdminShell(String title, String innerHtml) {
        String safeTitle = escape(title == null ? "Looped update" : title);
        String safeInner = innerHtml == null ? "" : innerHtml;

        StringBuilder out = new StringBuilder();
        out.append("<html><body style=\"margin:0;padding:0;background-color:#ffffff;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"background-color:#ffffff;\">");
        out.append("<tr><td align=\"center\" style=\"padding:32px 16px;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"560\" style=\"width:560px;max-width:560px;background-color:#ffffff;border:1px solid #f3f4f6;border-radius:12px;\">");
        out.append("<tr><td style=\"padding:24px 24px 10px 24px;font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#ea404a;\">Looped</td></tr>");
        out.append("<tr><td style=\"padding:0 24px 16px 24px;font-size:22px;font-weight:700;color:#1f2937;\">").append(safeTitle).append("</td></tr>");
        out.append("<tr><td style=\"padding:0 24px 24px 24px;\">").append(safeInner).append("</td></tr>");
        out.append("</table></td></tr></table>");
        out.append("</body></html>");
        return out.toString();
    }

    private boolean looksLikeHtmlDocument(String html) {
        if (html == null) return false;
        String normalized = html.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("<!doctype html")
                || normalized.startsWith("<html")
                || normalized.contains("<body");
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

    private String buildTextBody(String communityName, String code, String link, int ttlSeconds) {
        StringBuilder out = new StringBuilder();
        if (communityName != null && !communityName.isBlank()) {
            out.append("Verify your ").append(communityName).append(" email\n\n");
        } else {
            out.append("Verify your Looped signup\n\n");
        }
        out.append("Your verification code: ").append(code).append("\n");
        out.append(expirySentence(ttlSeconds)).append("\n");
        if (link != null) {
            out.append("\nOr click this link:\n").append(link).append("\n");
        }
        out.append("\nIf you did not sign up for Looped, you can ignore this email.\n");
        return out.toString();
    }

    private String buildHtmlBody(String communityName, String code, String link, int ttlSeconds) {
        StringBuilder out = new StringBuilder();
        out.append("<html><body style=\"margin:0;padding:0;background-color:#ffffff;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"background-color:#ffffff;\">");
        out.append("<tr><td align=\"center\" style=\"padding:32px 16px;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"560\" style=\"width:560px;max-width:560px;\">");
        if (communityName != null && !communityName.isBlank()) {
            out.append("<tr><td style=\"font-size:22px;font-weight:700;color:#1f2937;padding-bottom:8px;\">Verify your ")
                    .append(escape(communityName)).append(" email</td></tr>");
        } else {
            out.append("<tr><td style=\"font-size:22px;font-weight:700;color:#1f2937;padding-bottom:8px;\">Verify your Looped signup</td></tr>");
        }
        out.append("<tr><td style=\"font-size:14px;color:#6b7280;padding-bottom:20px;\">Use the code below to finish verifying your email.</td></tr>");
        out.append("<tr><td align=\"center\" style=\"padding-bottom:16px;\">");
        out.append(buildCodeBoxes(code));
        out.append("</td></tr>");
        out.append("<tr><td align=\"center\" style=\"font-size:12px;color:#9ca3af;padding-bottom:20px;\">")
                .append(escape(expirySentence(ttlSeconds))).append("</td></tr>");
        if (link != null) {
            out.append("<tr><td style=\"font-size:13px;color:#6b7280;padding-bottom:20px;\">Or verify using this link: ")
                    .append("<a href=\"").append(escape(link)).append("\" style=\"color:#ea404a;text-decoration:none;\">")
                    .append("Verify now</a></td></tr>");
        }
        out.append("<tr><td style=\"font-size:13px;color:#6b7280;\">If you did not sign up for Looped, you can ignore this email.</td></tr>");
        out.append("</table></td></tr></table>");
        out.append("</body></html>");
        return out.toString();
    }

    private String buildCodeBoxes(String code) {
        if (code == null || code.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 auto;\">");
        out.append("<tr>");
        for (int i = 0; i < code.length(); i++) {
            String ch = escape(code.substring(i, i + 1));
            out.append("<td style=\"padding:0 4px;\">")
                    .append("<div style=\"width:44px;height:52px;border:1px solid #ea404a;border-radius:8px;")
                    .append("background-color:#ffffff;color:#1f2937;font-size:20px;font-weight:600;")
                    .append("line-height:52px;text-align:center;\">")
                    .append(ch)
                    .append("</div></td>");
        }
        out.append("</tr></table>");
        return out.toString();
    }

    private String expirySentence(int ttlSeconds) {
        if (ttlSeconds <= 0) return "This code expires soon.";
        int minutes = (int) Math.ceil(ttlSeconds / 60.0);
        return "This code is valid for " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
    }

    private String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
