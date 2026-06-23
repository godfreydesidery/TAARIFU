package com.taarifu.admin.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A server-controlled feature flag — an on/off (or staged-rollout) toggle the platform reads to gate a
 * capability without a redeploy (M14, US-14.1, UC-H07; PRD §16 "Feature Flag / AppConfig").
 *
 * <p>Responsibility: one row per flag, keyed by a stable machine {@code key} (e.g.
 * {@code reporting.voice_note_capture}, {@code payments.mpesa_purchase}). {@link #enabled} is the master
 * switch; {@link #rolloutPercentage} supports a staged rollout (0–100) for flags that bucket users by a
 * stable hash. The admin console lists/toggles these; a client or another module reads the flag to decide
 * whether to show/allow a feature.</p>
 *
 * <p>WHY flags live in the admin module (not scattered per-module): the admin console is the single
 * operational surface for "turn X on/off" (UC-H07), and a flag is reference/config data, not a feature
 * module's domain. Modules that need to <i>read</i> a flag do so through a published admin query port (a
 * later increment) or the public config endpoint — they never own the flag table. This keeps one source
 * of truth for the toggle set (DRY).</p>
 *
 * <p>Not PII and intentionally readable by the public config endpoint (the client must know which features
 * are live); it carries no citizen data. Changes are audited (who flipped which flag) by the admin
 * service.</p>
 */
@Entity
@Table(name = "feature_flag", indexes = {
        @Index(name = "ux_feature_flag_key", columnList = "flag_key", unique = true)
})
@SQLRestriction("deleted = false")
public class FeatureFlag extends BaseEntity {

    /** The stable machine key (namespaced by module/concern); unique. Clients/modules branch on this. */
    @Column(name = "flag_key", nullable = false, unique = true, length = 96)
    private String key;

    /** Human-readable description of what the flag gates (admin/UI display). */
    @Column(name = "description", length = 255)
    private String description;

    /** Master on/off switch. Defaults to {@code false} (a new flag is off until explicitly enabled). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    /**
     * Staged-rollout percentage 0–100 for flags that bucket users; {@code 100} = fully on when enabled,
     * {@code 0} = effectively off even if {@code enabled} (used for "armed but not yet rolling"). The
     * DB CHECK enforces the 0–100 range.
     */
    @Column(name = "rollout_percentage", nullable = false)
    private int rolloutPercentage = 100;

    /** JPA requires a no-arg constructor; application code uses {@link #create}. */
    protected FeatureFlag() {
    }

    /**
     * Creates a new flag (off by default, full rollout when later enabled).
     *
     * @param key         the stable machine key.
     * @param description human-readable purpose.
     * @return the populated, transient flag.
     */
    public static FeatureFlag create(String key, String description) {
        FeatureFlag f = new FeatureFlag();
        f.key = key;
        f.description = description;
        f.enabled = false;
        f.rolloutPercentage = 100;
        return f;
    }

    /**
     * Applies an admin update to the toggle state.
     *
     * @param description       new description, or {@code null} to leave unchanged is NOT supported — the
     *                          admin surface sends the full desired state, so {@code null} clears it.
     * @param enabled           new master switch state.
     * @param rolloutPercentage new staged-rollout percentage (0–100; the service validates the range).
     */
    public void update(String description, boolean enabled, int rolloutPercentage) {
        this.description = description;
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
    }

    /** @return the stable machine key. */
    public String getKey() {
        return key;
    }

    /** @return the human description, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** @return whether the flag's master switch is on. */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return the staged-rollout percentage (0–100). */
    public int getRolloutPercentage() {
        return rolloutPercentage;
    }
}
