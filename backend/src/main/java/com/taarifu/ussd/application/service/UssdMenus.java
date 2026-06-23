package com.taarifu.ussd.application.service;

import com.taarifu.ussd.application.port.UssdReportingPort.UssdCategoryOption;
import com.taarifu.ussd.domain.model.enums.UssdLanguage;

import java.util.List;

/**
 * The USSD menu/prompt text, authored Swahili-first and <b>GSM-7-safe</b> (PRD §14, §15, EI-3/EI-4).
 *
 * <p>Responsibility: hold every screen's text as short, plain-ASCII constants and assemble the dynamic
 * screens (category list, file confirmation, track result). Keeping the strings here — not in the shared
 * {@code i18n/messages_*} bundle — is deliberate: USSD copy is a different medium (≤182 chars/page, no
 * diacritics, numbered options) from the app/SMS templates, and the shared bundle is a centrally-owned file
 * this module must not edit (isolation rule). If/when these belong in the shared bundle, that is a central
 * change — see CENTRAL INTEGRATION NEEDS.</p>
 *
 * <p>WHY GSM-7-safe (ASCII, no Swahili diacritics): a UCS-2 page collapses the USSD character budget and
 * some feature phones render diacritics poorly; plain Swahili ("Karibu", "Chagua") is universally legible on
 * the narrowest handset (PRD §14 inclusion). All strings here are intentionally ASCII.</p>
 */
final class UssdMenus {

    private UssdMenus() {
    }

    /** Language-selection screen (always shown first; both options shown regardless of language). */
    static String languagePrompt() {
        return "Karibu Taarifu / Welcome\n1. Kiswahili\n2. English";
    }

    /** Main menu after language is chosen. */
    static String mainMenu(UssdLanguage lang) {
        if (lang == UssdLanguage.EN) {
            return "Taarifu\n1. Report an issue\n2. Track a report\n3. My-area alerts\n4. Help";
        }
        return "Taarifu\n1. Toa taarifa\n2. Fuatilia taarifa\n3. Tahadhari eneo langu\n4. Msaada";
    }

    /** File-report: category list, numbered 1..N (kept to a few so the page fits). */
    static String categoryMenu(UssdLanguage lang, List<UssdCategoryOption> categories) {
        StringBuilder sb = new StringBuilder(lang == UssdLanguage.EN ? "Choose category:" : "Chagua aina:");
        int i = 1;
        for (UssdCategoryOption c : categories) {
            sb.append('\n').append(i++).append(". ").append(c.name());
        }
        return sb.toString();
    }

    /** File-report: use my registered area, or pick a ward. */
    static String areaChoice(UssdLanguage lang, boolean hasRegisteredArea) {
        if (lang == UssdLanguage.EN) {
            return hasRegisteredArea
                    ? "Area:\n1. Use my area\n2. Enter ward code"
                    : "Area:\n2. Enter ward code";
        }
        return hasRegisteredArea
                ? "Eneo:\n1. Tumia eneo langu\n2. Weka msimbo wa kata"
                : "Eneo:\n2. Weka msimbo wa kata";
    }

    /** File-report: prompt to type the ward code/number. */
    static String areaPickPrompt(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Enter ward code:" : "Weka msimbo wa kata:";
    }

    /** File-report: prompt for the short description. */
    static String descriptionPrompt(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Describe the issue briefly:" : "Eleza tatizo kwa kifupi:";
    }

    /** File-report: confirm before creating the ticket. */
    static String confirmPrompt(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Send report?\n1. Yes\n2. No" : "Tuma taarifa?\n1. Ndio\n2. Hapana";
    }

    /** File-report: final success line carrying the ticket code (also sent by SMS). */
    static String filedOk(UssdLanguage lang, String ticketCode) {
        return lang == UssdLanguage.EN
                ? "Sent. Ticket: " + ticketCode + ". SMS to follow."
                : "Imetumwa. Tikiti: " + ticketCode + ". Utapata SMS.";
    }

    /** File-report: cancelled. */
    static String cancelled(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Cancelled." : "Imeghairiwa.";
    }

    /** Track: prompt for the ticket code. */
    static String trackPrompt(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Enter ticket code:" : "Weka msimbo wa tikiti:";
    }

    /** Track: result line. */
    static String trackResult(UssdLanguage lang, String ticketCode, String status) {
        return lang == UssdLanguage.EN
                ? ticketCode + ": " + status
                : ticketCode + ": " + status;
    }

    /** Track: not found. */
    static String trackNotFound(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Ticket not found." : "Tikiti haikupatikana.";
    }

    /** Alerts: confirm subscribing the registered area. */
    static String alertsConfirm(UssdLanguage lang) {
        return lang == UssdLanguage.EN
                ? "Get SMS alerts for your area?\n1. Yes\n2. No"
                : "Pata tahadhari za SMS eneo lako?\n1. Ndio\n2. Hapana";
    }

    /** Alerts: subscribed. */
    static String alertsOk(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Subscribed to area alerts." : "Umejisajili kwa tahadhari za eneo.";
    }

    /** Alerts: no registered area to subscribe. */
    static String alertsNoArea(UssdLanguage lang) {
        return lang == UssdLanguage.EN
                ? "No registered area. Use the app to set it."
                : "Huna eneo lililosajiliwa. Tumia programu kuweka.";
    }

    /** Help screen (END). */
    static String help(UssdLanguage lang) {
        return lang == UssdLanguage.EN
                ? "Taarifu: report and track civic issues. Call your council for urgent help."
                : "Taarifu: toa na fuatilia taarifa za huduma. Piga halmashauri kwa dharura.";
    }

    /** Generic invalid-input line, re-shown with the same prompt by the machine. */
    static String invalid(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Invalid choice." : "Chaguo si sahihi.";
    }

    /** Session-expired / lost-state line (END). */
    static String expired(UssdLanguage lang) {
        return lang == UssdLanguage.EN ? "Session expired. Dial again." : "Muda umeisha. Piga tena.";
    }
}
