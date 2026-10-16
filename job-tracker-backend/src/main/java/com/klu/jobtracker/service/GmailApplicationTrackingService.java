
package com.klu.jobtracker.service;

import com.klu.jobtracker.dto.ApplicationRequest;
import com.klu.jobtracker.entity.ApplicationStatus;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GmailApplicationTrackingService {

    private static final Logger log =
            LoggerFactory.getLogger(GmailApplicationTrackingService.class);

    private final ApplicationService applicationService;

    @Value("${app.mail-tracking.enabled:false}")
    private boolean enabled;

    @Value("${app.mail-tracking.imap-host:imap.gmail.com}")
    private String host;

    @Value("${app.mail-tracking.imap-port:993}")
    private int port;

    @Value("${app.mail-tracking.username:}")
    private String username;

    @Value("${app.mail-tracking.password:}")
    private String password;

    @Value("${app.mail-tracking.owner-email:}")
    private String ownerEmail;

    public GmailApplicationTrackingService(
            ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Scheduled(
        fixedDelayString = "${app.mail-tracking.poll-interval-ms:60000}",
        initialDelayString = "15000"
    )
    public void scanInbox() {

        if (!enabled) {
            log.debug("Gmail tracking is disabled.");
            return;
        }

        if (blank(username) || blank(password) || blank(ownerEmail)) {
            log.warn("Gmail tracking configuration is incomplete. "
                    + "Check username, password and owner email.");
            return;
        }

        Store store = null;
        Folder inbox = null;

        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.connectiontimeout", "15000");
            props.put("mail.imaps.timeout", "15000");
            props.put("mail.imaps.writetimeout", "15000");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(host, port, username, password);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Date since = Date.from(
                LocalDate.now()
                    .minusDays(30)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            );

            Message[] messages = inbox.search(
                new ReceivedDateTerm(ComparisonTerm.GE, since)
            );

            int scanned = 0;
            int matched = 0;

            log.info("Gmail scan started. Recent inbox messages: {}",
                    messages.length);

            for (Message message : messages) {
                scanned++;

                try {
                    String subject = safe(message.getSubject());
                    String sender = senderName(message);
                    String senderAddress = senderAddress(message);
                    String body = readBody(message);

                    ApplicationRequest request = parseApplication(
                        subject, sender, senderAddress, body
                    );

                    if (request == null) {
                        continue;
                    }

                    /*
                     * IMPORTANT:
                     * createAutoDetected must check the database for an
                     * existing application before inserting a new record.
                     * This service scans the same 30-day window repeatedly.
                     */
                    applicationService.createAutoDetected(
                        ownerEmail, request
                    );

                    matched++;

                    log.info(
                        "Application confirmation matched: company={}, "
                        + "role={}, sender={}",
                        request.getCompanyName(),
                        request.getJobTitle(),
                        senderAddress
                    );

                } catch (Exception ex) {
                    log.error(
                        "Failed to process an email. Subject={}. Reason={}",
                        safe(message.getSubject()),
                        ex.getMessage(),
                        ex
                    );
                }
            }

            log.info(
                "Gmail scan completed. Scanned={}, confirmation matches={}",
                scanned, matched
            );

        } catch (Exception ex) {
            log.error(
                "Gmail inbox scan failed. Check IMAP settings, Gmail App "
                + "Password and network access. Reason={}",
                ex.getMessage(), ex
            );

        } finally {
            try {
                if (inbox != null && inbox.isOpen()) {
                    inbox.close(false);
                }
            } catch (Exception ignored) {
            }

            try {
                if (store != null && store.isConnected()) {
                    store.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private ApplicationRequest parseApplication(
            String subject,
            String senderName,
            String senderAddress,
            String body) {

        String combined = (subject + "\n" + body)
                .toLowerCase();

        ApplicationStatus detectedStatus = detectStatus(combined);

        boolean confirmation = containsAny(
            combined,
            "we received your application",
            "we received your job application",
            "we have received your application",
            "we have received your job application",
            "thank you for your application",
            "thank you for applying",
            "thanks for applying",
            "application received",
            "your application has been received",
            "application has been submitted",
            "successfully applied",
            "we have received your submission",
            "thank you for your interest in"
        );

        // Also process meaningful status-update emails, not only acknowledgments.
        if (!confirmation && detectedStatus == null) {
            log.debug("Email is not a recognized application/status email: {}",
                    subject);
            return null;
        }

        String company = findCompany(
            senderName, senderAddress, body, subject
        );

        String roleAndLocation = extractRole(subject, body);

        if (blank(company) || blank(roleAndLocation)) {
            log.warn(
                "Confirmation email matched, but company or job title "
                + "could not be extracted. Subject={}, company={}, role={}",
                subject, company, roleAndLocation
            );
            return null;
        }

        String title = roleAndLocation.trim();
        String location = null;

        /*
         * Remove a trailing job reference number, such as 260140.
         * Do not incorrectly interpret it as a location.
         */
        title = title.replaceFirst(
            "\\s*[-–—]\\s*#?\\d{4,}\\s*$", ""
        ).trim();

        /*
         * Extract a location only when the suffix is a recognized
         * location. A dash by itself does not mean location.
         */
        Matcher locationMatcher = Pattern.compile(
            "(?i)^(.+?)\\s+[-–—]\\s+"
            + "(Noida|Hyderabad|Bengaluru|Bangalore|"
            + "Chennai|Pune|Mumbai|New Delhi|Delhi|"
            + "Gurugram|Gurgaon|Kolkata|Remote)$"
        ).matcher(title);

        if (locationMatcher.matches()) {
            title = locationMatcher.group(1).trim();
            location = locationMatcher.group(2).trim();
        }

        title = title.replaceAll(
            "(?i)\\s+(opportunity|position|role)$", ""
        ).trim();

        if (title.length() < 2) {
            return null;
        }

        ApplicationRequest request = new ApplicationRequest();
        request.setCompanyName(company);
        request.setJobTitle(title);
        request.setLocation(location);
        request.setApplicationDate(LocalDate.now());
        request.setStatus(detectedStatus != null
                ? detectedStatus : ApplicationStatus.APPLIED);
        request.setNotes(
            "Automatically detected from Gmail. Sender: "
            + (blank(senderAddress) ? senderName : senderAddress)
            + ". Please verify the extracted details."
        );

        return request;
    }

    private ApplicationStatus detectStatus(String text) {
        // Check negative/terminal outcomes before generic interview or application wording.
        if (containsAny(text,
                "we regret to inform you", "regret to inform",
                "your application was not selected", "not moving forward",
                "will not be moving forward", "unsuccessful in your application",
                "we will not be proceeding", "your application has been rejected",
                "not selected for this position")) {
            return ApplicationStatus.REJECTED;
        }

        if (containsAny(text,
                "pleased to offer you", "we are delighted to offer",
                "job offer", "offer of employment", "employment offer",
                "congratulations on your offer")) {
            return ApplicationStatus.OFFER;
        }

        if (containsAny(text,
                "interview has been scheduled", "interview is scheduled",
                "interview invitation", "invite you to interview",
                "invited to interview", "interview is confirmed",
                "your interview is confirmed", "schedule an interview")) {
            return ApplicationStatus.INTERVIEW;
        }

        if (containsAny(text,
                "shortlisted", "short-listed", "selected for the next round",
                "proceed to the next round", "move forward to the next stage")) {
            return ApplicationStatus.SHORTLISTED;
        }

        return null;
    }

    private String extractRole(String subject, String body) {

        /*
         * Supports both:
         * "application to the Talent Acquisition Intern - Noida opportunity"
         * "received your job application for Associate SW Development Engineer - 260140"
         */
        Pattern[] patterns = {
            Pattern.compile(
                "(?i)position\\s+of\\s+(.{3,150}?)(?=[.!?\\n]|\\s+(?:and\\s+we|we\\s+are|we\\s+will)\\b|$)"
            ),
            Pattern.compile(
                "(?i)job\\s+title\\s*[:\\-]\\s*([^\\n.!?]{3,120})"
            ),
            Pattern.compile(
                "(?i)application\\s+to\\s+the\\s+"
                + "(.{3,150}?)\\s+opportunity"
            ),
            Pattern.compile(
                "(?i)received\\s+your\\s+(?:job\\s+)?application\\s+for\\s+"
                + "(.{3,150}?)(?=[.!?\\n]|,?\\s+(?:and\\s+we|we\\s+are|we\\s+will|we\\s+would|please|to\\s+learn)\\b|$)"
            ),
            Pattern.compile(
                "(?i)application\\s+for\\s+"
                + "(.{3,150}?)(?=[.!?\\n]|,?\\s+(?:and\\s+we|we\\s+are|we\\s+will|we\\s+would|please|to\\s+learn)\\b|$)"
            ),
            Pattern.compile(
                "(?i)position\\s*:\\s*([^\\n.!?]{3,120})"
            ),
            Pattern.compile(
                "(?i)role\\s*:\\s*([^\\n.!?]{3,120})"
            )
        };

        String[] sources = {subject, body, subject + "\n" + body};

        for (Pattern pattern : patterns) {
            for (String source : sources) {
                Matcher matcher = pattern.matcher(source);

                if (matcher.find()) {
                    String role = matcher.group(1)
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .replaceAll("[,;!]+$", "")
                        .trim();

                    if (role.length() > 150) {
                        role = role.substring(0, 150).trim();
                    }

                    if (role.length() >= 3) {
                        return role;
                    }
                }
            }
        }

        return null;
    }

    private String findCompany(
            String senderName,
            String senderAddress,
            String body,
            String subject) {

        /*
         * A useful sender display name is usually the best source.
         * Do not use generic names such as "Recruiting Team".
         */
        if (!blank(senderName)
                && !senderName.equalsIgnoreCase("no-reply")
                && !senderName.toLowerCase().contains("talent acquisition")
                && !senderName.toLowerCase().contains("recruiting team")
                && !senderName.toLowerCase().contains("oracle")) {

            return cleanCompany(senderName);
        }

        /*
         * Find company names in email body/signatures, for example
         * "Harmonic Inc." in the supplied Harmonic email.
         */
        Matcher legalName = Pattern.compile(
            "(?im)^\\s*([A-Z][A-Za-z0-9&.,' -]{1,55}?"
            + "\\s+(?:Inc\\.?|Ltd\\.?|Limited|Corporation|Corp\\.?|"
            + "Technologies|Technology|Solutions|Group|Labs))"
            + "\\s*$"
        ).matcher(body);

        if (legalName.find()) {
            return cleanCompany(legalName.group(1));
        }

        /*
         * Example: "Thank you for your interest in Paytm!"
         */
        Matcher interest = Pattern.compile(
            "(?i)(?:interest in|application to)\\s+"
            + "([A-Z][A-Za-z0-9& .'-]{1,50})[!,.\\n]"
        ).matcher(body);

        if (interest.find()) {
            return cleanCompany(interest.group(1));
        }

        /*
         * Fall back to the sender's email domain.
         * Ignore generic email providers and recruitment platforms.
         */
        String address = safe(senderAddress).toLowerCase();
        int at = address.lastIndexOf('@');

        if (at >= 0 && at < address.length() - 1) {
            String domain = address.substring(at + 1);
            String firstLabel = domain.split("\\.")[0];

            if (!containsAny(domain,
                    "gmail.com", "outlook.com", "yahoo.com",
                    "lever.co", "hire.lever.co")) {

                String company = firstLabel
                    .replaceAll("(?i)inc$", " Inc")
                    .replace('-', ' ')
                    .trim();

                return cleanCompany(toTitleCase(company));
            }
        }

        return null;
    }

    private String toTitleCase(String value) {
        StringBuilder result = new StringBuilder();

        for (String word : value.split("\\s+")) {
            if (word.isBlank()) continue;

            if (result.length() > 0) {
                result.append(' ');
            }

            result.append(
                word.substring(0, 1).toUpperCase()
                + word.substring(1).toLowerCase()
            );
        }

        return result.toString();
    }

    private String senderName(Message message) throws Exception {
        Address[] addresses = message.getFrom();

        if (addresses == null || addresses.length == 0) {
            return "";
        }

        if (addresses[0] instanceof InternetAddress internet) {
            String personal = internet.getPersonal();

            if (!blank(personal)) {
                return personal.trim();
            }
        }

        return "";
    }

    private String senderAddress(Message message) throws Exception {
        Address[] addresses = message.getFrom();

        if (addresses == null || addresses.length == 0) {
            return "";
        }

        if (addresses[0] instanceof InternetAddress internet) {
            return safe(internet.getAddress());
        }

        return addresses[0].toString();
    }

    private String readBody(Part part) throws Exception {

        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }

        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            String html = content == null ? "" : content.toString();

            return html
                .replaceAll(
                    "(?is)<(script|style)[^>]*>.*?</\\1>", " "
                )
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll(
                    "(?i)</(p|div|tr|h[1-6])>", "\n"
                )
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ");
        }

        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();

            if (!(content instanceof Multipart multipart)) {
                return "";
            }

            StringBuilder result = new StringBuilder();

            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);

                if (Part.ATTACHMENT.equalsIgnoreCase(
                        bodyPart.getDisposition())) {
                    continue;
                }

                result.append(readBody(bodyPart)).append('\n');
            }

            return result.toString();
        }

        return "";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String cleanCompany(String value) {
        String result = safe(value)
            .replaceAll("[!,.\\s]+$", "")
            .trim();

        if (result.isEmpty()) {
            return null;
        }

        return result.length() > 80
            ? result.substring(0, 80).trim()
            : result;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
