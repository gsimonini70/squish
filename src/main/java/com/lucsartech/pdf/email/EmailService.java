package com.lucsartech.pdf.email;

import com.lucsartech.pdf.config.PdfCompressorProperties;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Email service for sending compression reports.
 */
public final class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PdfCompressorProperties.Email config;
    private final Session session;

    public EmailService(PdfCompressorProperties.Email config) {
        this.config = config;
        this.session = createSession();
        log.info("Email service initialized: {} -> {}", config.getFrom(), config.getTo());
    }

    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));

        // Timeout settings
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.getConnectionTimeout()));
        props.put("mail.smtp.timeout", String.valueOf(config.getReadTimeout()));
        props.put("mail.smtp.writetimeout", String.valueOf(config.getReadTimeout()));

        // SSL/TLS configuration
        if (config.isSsl()) {
            // Direct SSL connection (typically port 465)
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.protocols", config.getSslProtocols());
            props.put("mail.smtp.ssl.checkserveridentity", String.valueOf(!config.isTrustAllCerts()));

            if (config.isTrustAllCerts()) {
                props.put("mail.smtp.ssl.trust", "*");
            }
        }

        if (config.isStarttls()) {
            // STARTTLS upgrade (typically port 587)
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.protocols", config.getSslProtocols());

            if (config.isTrustAllCerts()) {
                props.put("mail.smtp.ssl.trust", "*");
            }
        }

        if (config.hasAuth()) {
            props.put("mail.smtp.auth", "true");
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.getSmtpUser(), config.getSmtpPassword());
                }
            });
        }

        return Session.getInstance(props);
    }

    /**
     * Send a cycle report email with PDF attachment.
     *
     * @param cycleNumber  The watchdog cycle number
     * @param recordsProcessed Number of records processed in this cycle
     * @param originalMb   Original size in MB
     * @param compressedMb Compressed size in MB
     * @param savingsPercent Compression savings percentage
     * @param reportPath   Path to the PDF report file
     * @param dryRun       Whether this was a dry-run
     */
    public void sendCycleReport(long cycleNumber, int recordsProcessed, double originalMb,
                                 double compressedMb, double savingsPercent, Path reportPath, boolean dryRun) {
        try {
            String timestamp = LocalDateTime.now().format(DATE_FMT);
            String modeStr = dryRun ? "[DRY-RUN] " : "";
            String subject = String.format("%sPDF Compression Report - Cycle #%d - %d records - %.1f%% saved",
                    modeStr, cycleNumber, recordsProcessed, savingsPercent);

            String body = buildEmailBody(cycleNumber, recordsProcessed, originalMb, compressedMb,
                    savingsPercent, timestamp, dryRun);

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.getFrom()));

            InternetAddress[] recipients = config.getTo().stream()
                    .map(addr -> {
                        try {
                            return new InternetAddress(addr);
                        } catch (AddressException e) {
                            log.warn("Invalid email address: {}", addr);
                            return null;
                        }
                    })
                    .filter(addr -> addr != null)
                    .toArray(InternetAddress[]::new);

            message.setRecipients(Message.RecipientType.TO, recipients);
            message.setSubject(subject);

            // Create multipart message with body and attachment
            Multipart multipart = new MimeMultipart();

            // Text body
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "utf-8", "html");
            multipart.addBodyPart(textPart);

            // PDF attachment
            if (reportPath != null && Files.exists(reportPath)) {
                MimeBodyPart attachmentPart = new MimeBodyPart();
                attachmentPart.attachFile(reportPath.toFile());
                attachmentPart.setFileName(reportPath.getFileName().toString());
                multipart.addBodyPart(attachmentPart);
            }

            message.setContent(multipart);

            Transport.send(message);
            log.info("Cycle #{} report email sent to {} recipients", cycleNumber, recipients.length);

        } catch (MessagingException | IOException e) {
            log.error("Failed to send email for cycle #{}", cycleNumber, e);
        }
    }

    private String buildEmailBody(long cycleNumber, int recordsProcessed, double originalMb,
                                   double compressedMb, double savingsPercent, String timestamp, boolean dryRun) {
        String modeNote = dryRun
                ? "<p style='color: #f59e0b; font-weight: bold;'>This was a DRY-RUN - no actual changes were made to the database.</p>"
                : "";

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                           background: #f5f5f5; padding: 20px; }
                    .container { max-width: 600px; margin: 0 auto; background: white;
                                 border-radius: 12px; padding: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    h1 { color: #1a1a2e; margin-bottom: 5px; }
                    .timestamp { color: #666; font-size: 14px; margin-bottom: 20px; }
                    .stats { background: #f8f9fa; border-radius: 8px; padding: 20px; margin: 20px 0; }
                    .stat-row { display: flex; justify-content: space-between; padding: 8px 0;
                                border-bottom: 1px solid #eee; }
                    .stat-row:last-child { border-bottom: none; }
                    .stat-label { color: #666; }
                    .stat-value { font-weight: 600; color: #1a1a2e; }
                    .savings { color: #22c55e; font-size: 24px; font-weight: 700; }
                    .footer { margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;
                              font-size: 12px; color: #999; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>PDF Compression Report</h1>
                    <div class="timestamp">Cycle #%d - %s</div>
                    %s

                    <div class="stats">
                        <div class="stat-row">
                            <span class="stat-label">Records Processed</span>
                            <span class="stat-value">%,d</span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-label">Original Size</span>
                            <span class="stat-value">%.2f MB</span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-label">Compressed Size</span>
                            <span class="stat-value">%.2f MB</span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-label">Space Saved</span>
                            <span class="stat-value">%.2f MB</span>
                        </div>
                        <div class="stat-row">
                            <span class="stat-label">Compression Rate</span>
                            <span class="savings">%.1f%% saved</span>
                        </div>
                    </div>

                    <p>The detailed PDF report is attached to this email.</p>

                    <div class="footer">
                        Generated by PDF Compressor Modern v2.0<br>
                        Powered by Spring Boot & Virtual Threads
                    </div>
                </div>
            </body>
            </html>
            """.formatted(cycleNumber, timestamp, modeNote, recordsProcessed,
                    originalMb, compressedMb, originalMb - compressedMb, savingsPercent);
    }
}
