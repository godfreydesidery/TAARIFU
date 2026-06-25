package com.taarifu.identity.application.service;

import com.taarifu.identity.api.dto.IdentityExportView;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.privacy.api.SubjectExportContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Identity's contribution to a data-subject ACCESS export — the subject's account/profile summary
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: implements the privacy module's {@link SubjectExportContributor} SPI so the privacy
 * export aggregator can include identity's data <b>without</b> reaching into identity's internals (ADR-0013):
 * identity stays the single reader of {@code app_user}/{@code profile}, and privacy depends only on the
 * published SPI interface. Registered automatically as a Spring bean; the privacy
 * {@code SubjectDataExportService} injects every contributor and composes the export by {@link #section()}.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> returns {@link IdentityExportView}, which <b>excludes
 * the national/voter ID number</b> (only the ID type + verification state) — an export must not become a fresh
 * plaintext copy of the most sensitive PII. {@code ProfileLocation}s are summarised as a count, not enumerated.
 * It returns the subject's own phone/email because the subject is exercising <i>their own</i> right of access
 * (unlike the masked admin view).</p>
 */
@Service
public class IdentityExportContributor implements SubjectExportContributor {

    /** The export section key identity fills. */
    private static final String SECTION = "identity";

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;

    /**
     * @param userRepository            account lookup by public id.
     * @param profileRepository         the account's 1:1 profile (names/ID-type/verification/demographics).
     * @param profileLocationRepository the account's pinned locations (counted, never enumerated).
     */
    public IdentityExportContributor(UserRepository userRepository,
                                     ProfileRepository profileRepository,
                                     ProfileLocationRepository profileLocationRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String section() {
        return SECTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the subject's account/profile summary, or {@code null} if there is no such account (so the
     * section is simply absent from the export). The ID number is never read/returned (data minimisation).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Object contribute(UUID subjectPublicId) {
        User user = userRepository.findByPublicId(subjectPublicId).orElse(null);
        if (user == null) {
            return null;
        }
        Profile profile = profileRepository.findByUser(user).orElse(null);
        long locationCount = profile == null ? 0L : profileLocationRepository.countByProfile(profile);

        return new IdentityExportView(
                user.getPublicId(),
                displayName(profile),
                user.getPhone(),
                user.getEmail(),
                profile == null || profile.getIdType() == null ? null : profile.getIdType().name(),
                profile != null && profile.isIdVerified(),
                user.getTrustTier().name(),
                profile == null ? null : profile.getDateOfBirth(),
                profile == null ? null : profile.getGender(),
                profile == null ? null : profile.getNationality(),
                locationCount,
                user.getCreatedAt());
    }

    /** Composes a display name from the profile's name fields (first + last, trimmed), or {@code null}. */
    private static String displayName(Profile profile) {
        if (profile == null) {
            return null;
        }
        String first = profile.getFirstName() == null ? "" : profile.getFirstName();
        String last = profile.getLastName() == null ? "" : profile.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }
}
