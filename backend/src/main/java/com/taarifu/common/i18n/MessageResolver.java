package com.taarifu.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves i18n keys to localised human text, <b>Swahili default, English secondary</b>
 * (ADR-0010, ARCHITECTURE.md §5.1).
 *
 * <p>Responsibility: the single place that turns a stable key (e.g. {@code geography.region.notFound})
 * into the {@code ApiResponse.message} string. Code references keys, never literals (CLAUDE.md §8).
 * The locale comes from the request's {@code Accept-Language} (via Spring's
 * {@link LocaleContextHolder}); when no/unknown locale is supplied the configured default locale —
 * Swahili — is used.</p>
 *
 * <p>WHY a thin wrapper over {@link MessageSource}: it centralises the "missing key is a defect, not
 * a crash" policy and the default-to-key fallback, so a half-translated bundle never 500s a citizen
 * mid-request (PRD §15 resilience). Bundles live in {@code i18n/messages_sw.properties} (default)
 * and {@code i18n/messages_en.properties}.</p>
 */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    /**
     * @param messageSource Spring {@link MessageSource} backed by the {@code i18n/messages*} bundles.
     */
    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves a key in the current request locale.
     *
     * @param key  the i18n key (e.g. {@code geography.region.notFound}).
     * @param args optional positional arguments substituted into the message template.
     * @return the localised message; if the key is missing, the key itself is returned so the
     *         response still renders (the gap is caught in review/QA, never crashes the request).
     */
    public String resolve(String key, Object... args) {
        return resolve(key, LocaleContextHolder.getLocale(), args);
    }

    /**
     * Resolves a key in an explicit locale (used by background workers — SMS/USSD/notifications —
     * that act on a recipient's stored language preference rather than a request locale, PRD §13).
     *
     * @param key    the i18n key.
     * @param locale the target locale.
     * @param args   optional positional arguments.
     * @return the localised message, or the key itself when unresolved.
     */
    public String resolve(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, key, locale);
    }
}
