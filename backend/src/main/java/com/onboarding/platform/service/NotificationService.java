package com.onboarding.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Tells applicants about review decisions. With app.mail.enabled=false (the default, until
 * SMTP is configured) messages are only logged. A mail failure never fails the decision.
 * Messages deliberately contain no document data -- just the outcome and where to look.
 */
@Slf4j
@Service
public class NotificationService {

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;

    public NotificationService(JavaMailSender mailSender,
                               @Value("${app.mail.enabled:false}") boolean enabled,
                               @Value("${app.mail.from:no-reply@houwiya.local}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    public void verificationDecided(String email, boolean approved, String reason) {
        String subject = approved ? "Your identity is verified" : "Your identity verification was not successful";
        String body = approved
                ? "Good news: your identity verification has been approved.\n\nHouwiya"
                : "We could not verify your identity with the document you submitted."
                        + (reason == null || reason.isBlank() ? "" : "\n\nReason: " + reason)
                        + "\n\nYou can start a new verification from the app.\n\nHouwiya";
        send(email, subject, body);
    }

    private void send(String to, String subject, String body) {
        if (!enabled) {
            log.info("[mail disabled] would send '{}' to {}", subject, to);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("Could not send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }
}
