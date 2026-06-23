package com.taarifu.admin.domain.model;

import com.taarifu.admin.domain.model.enums.ClientPlatform;
import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * Server-driven application configuration for a client platform — the <b>min-version / force-update /
 * splash</b> surface (M14, US-14.1, UC-H07; PRD EI-16 "AppConfig: min version, force-update, splash").
 *
 * <p>Responsibility: one row per {@link ClientPlatform} holding the gate the mobile/web client reads at
 * startup. {@code minSupportedVersion} is the floor the client must be at or above; if the installed
 * build is below it and {@link #forceUpdate} is {@code true}, the client hard-blocks until the user
 * updates (EI-16: the client enforces min-version <b>locally</b>, so a server/store outage never bricks a
 * running client). {@code latestVersion} drives a soft "update available" nudge; {@code splashMessage} /
 * {@code splashUrl} carry an optional maintenance/announcement banner.</p>
 *
 * <p>WHY one row per platform (a partial-unique, not a hard singleton): Play and App Store version lines
 * advance independently and a force-update may be needed on one platform but not the other (PRD EI-16).
 * The {@code (platform)} unique index (where not deleted) keeps it at most one live row per platform; the
 * admin service upserts it.</p>
 *
 * <p>WHY versions are stored as both a human {@code String} and a comparable {@code long code}: store
 * version strings are not lexically comparable ({@code "1.10.0"} &lt; {@code "1.9.0"} as text), so the
 * comparable gate is the integer {@code minSupportedVersionCode}/{@code latestVersionCode} (the platform
 * build number); the display string is for humans. The client compares its own build code against the
 * code — never the string (EI-16).</p>
 *
 * <p>Not security-sensitive and not PII: this is public-by-design operational config (the client fetches
 * it unauthenticated at boot); it carries no citizen data.</p>
 */
@Entity
@Table(name = "app_config", indexes = {
        @Index(name = "ix_app_config_platform", columnList = "platform")
})
@SQLRestriction("deleted = false")
public class AppConfig extends BaseEntity {

    /** The client platform this config targets (one live row per platform). */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private ClientPlatform platform;

    /** Human-readable minimum supported version (e.g. {@code "2.3.0"}); display only. */
    @Column(name = "min_supported_version", nullable = false, length = 32)
    private String minSupportedVersion;

    /**
     * The comparable minimum supported build code (the platform build number). The client gates on this,
     * never on the display string (EI-16). Must be {@code >= 0}.
     */
    @Column(name = "min_supported_version_code", nullable = false)
    private long minSupportedVersionCode;

    /** Human-readable latest available version (e.g. {@code "2.5.1"}); drives the soft "update" nudge. */
    @Column(name = "latest_version", length = 32)
    private String latestVersion;

    /** The comparable latest available build code; {@code null} if no soft nudge configured. */
    @Column(name = "latest_version_code")
    private Long latestVersionCode;

    /**
     * Whether a client below {@link #minSupportedVersionCode} must hard-block (force update) rather than
     * receive only a soft nudge. Defaults to {@code false} (advisory).
     */
    @Column(name = "force_update", nullable = false)
    private boolean forceUpdate = false;

    /** Optional splash/maintenance banner text shown by the client (SW/EN handled client-side); nullable. */
    @Column(name = "splash_message", length = 512)
    private String splashMessage;

    /** Optional deep link / URL the splash banner points to (e.g. store page, status page); nullable. */
    @Column(name = "splash_url", length = 512)
    private String splashUrl;

    /** JPA requires a no-arg constructor; application code uses {@link #create}. */
    protected AppConfig() {
    }

    /**
     * Creates a config row for a platform with its version gate.
     *
     * @param platform                the targeted client platform.
     * @param minSupportedVersion     human min version string.
     * @param minSupportedVersionCode comparable min build code ({@code >= 0}).
     * @param forceUpdate             whether below-min clients must hard-block.
     * @return the populated, transient config.
     */
    public static AppConfig create(ClientPlatform platform, String minSupportedVersion,
                                   long minSupportedVersionCode, boolean forceUpdate) {
        AppConfig c = new AppConfig();
        c.platform = platform;
        c.minSupportedVersion = minSupportedVersion;
        c.minSupportedVersionCode = minSupportedVersionCode;
        c.forceUpdate = forceUpdate;
        return c;
    }

    /**
     * Applies an admin update to the version gate and splash. Each field is replaced wholesale (the admin
     * surface sends the full desired state); the {@link ClientPlatform} is immutable on an existing row.
     *
     * @param minSupportedVersion     new human min version string.
     * @param minSupportedVersionCode new comparable min build code.
     * @param latestVersion           new latest version string, or {@code null}.
     * @param latestVersionCode       new latest build code, or {@code null}.
     * @param forceUpdate             new force-update flag.
     * @param splashMessage           new splash text, or {@code null} to clear.
     * @param splashUrl               new splash URL, or {@code null} to clear.
     */
    public void update(String minSupportedVersion, long minSupportedVersionCode, String latestVersion,
                       Long latestVersionCode, boolean forceUpdate, String splashMessage, String splashUrl) {
        this.minSupportedVersion = minSupportedVersion;
        this.minSupportedVersionCode = minSupportedVersionCode;
        this.latestVersion = latestVersion;
        this.latestVersionCode = latestVersionCode;
        this.forceUpdate = forceUpdate;
        this.splashMessage = splashMessage;
        this.splashUrl = splashUrl;
    }

    /** @return the targeted client platform. */
    public ClientPlatform getPlatform() {
        return platform;
    }

    /** @return the human minimum supported version string. */
    public String getMinSupportedVersion() {
        return minSupportedVersion;
    }

    /** @return the comparable minimum supported build code. */
    public long getMinSupportedVersionCode() {
        return minSupportedVersionCode;
    }

    /** @return the human latest version string, or {@code null}. */
    public String getLatestVersion() {
        return latestVersion;
    }

    /** @return the comparable latest build code, or {@code null}. */
    public Long getLatestVersionCode() {
        return latestVersionCode;
    }

    /** @return whether below-min clients must hard-block (force update). */
    public boolean isForceUpdate() {
        return forceUpdate;
    }

    /** @return the optional splash/maintenance banner text, or {@code null}. */
    public String getSplashMessage() {
        return splashMessage;
    }

    /** @return the optional splash URL, or {@code null}. */
    public String getSplashUrl() {
        return splashUrl;
    }
}
