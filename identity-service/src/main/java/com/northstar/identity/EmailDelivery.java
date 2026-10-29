package com.northstar.identity;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailDelivery {
    private final String mode;
    private final String from;
    private final Path captureDirectory;
    private final JavaMailSender mailSender;

    EmailDelivery(@Value("${bank.email.mode:capture}") String mode,
                  @Value("${bank.email.from:no-reply@northstar.local}") String from,
                  @Value("${bank.email.capture-dir:/tmp/northstar-email-capture}") String captureDirectory,
                  JavaMailSender mailSender) {
        this.mode = mode.toLowerCase(java.util.Locale.ROOT);
        this.from = from;
        this.captureDirectory = Path.of(captureDirectory);
        this.mailSender = mailSender;
    }

    void sendVerification(String recipient, String code) {
        String body = "Your Northstar verification code is " + code + ". It expires in 10 minutes.";
        try {
            if ("capture".equals(mode)) {
                Files.createDirectories(captureDirectory);
                String safeName = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((recipient + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
                Files.writeString(captureDirectory.resolve(Instant.now().toEpochMilli() + "-" + safeName + ".eml"),
                        "To: " + recipient + "\nSubject: Northstar email verification\n\n" + body + "\n");
                return;
            }
            if (!"smtp".equals(mode)) throw new IllegalStateException("Unsupported email mode");
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject("Northstar email verification");
            helper.setText(body);
            mailSender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Email delivery failed", exception);
        }
    }
}
