package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.identity.api.UserAdminApi;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.GrantRoleCommand;
import com.taarifu.identity.api.dto.UserAdminDetail;
import com.taarifu.identity.api.dto.UserAdminFilter;
import com.taarifu.identity.api.dto.UserAdminPage;
import com.taarifu.identity.api.dto.UserAdminSummary;
import com.taarifu.identity.api.dto.UserRoleGrant;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The identity-owned implementation of the back-office user-management ports — both the read
 * {@link UserAdminQueryApi} and the command {@link UserAdminApi} (M14, US-14.1, UC-H06; ADR-0013 §1/§4d;
 * PRD §7.2 RBAC, D15 additive roles, D16 no-self-action).
 *
 * <p>Responsibility: it is the <b>single writer</b> of the {@code app_user}/{@code role_assignment}
 * aggregate for admin operations and the read source for the console's Users area. The admin module owns the
 * operator workflow (deny-by-default {@code ADMIN}/{@code ROOT} method security and the no-self-action fence)
 * and calls these ports; identity validates the target/role, applies the integrity rules below, and writes
 * the canonical audit row — so the admin module mutates account/role state <b>without importing identity's
 * {@code domain}/{@code repository}</b> (the boundary holds, ARCHITECTURE §3.2).</p>
 *
 * <p><b>Integrity rules enforced here (CLAUDE.md §10 — test the invariant):</b></p>
 * <ul>
 *   <li><b>Additive, idempotent grant (D15):</b> a grant never removes existing roles; granting a role the
 *       account already holds active is a no-op success that returns the existing assignment id — no
 *       duplicate row, no double-credit of authority.</li>
 *   <li><b>Revoke is an end-date, never a delete (§6.4/§18):</b> the grant is set {@code FORMER} and
 *       {@code effectiveTo}=now so it stops authorising immediately, but the row stays for audit/history.</li>
 *   <li><b>Base role is protected:</b> the account's last active {@code CITIZEN} grant may not be revoked —
 *       every account keeps its base civic role (otherwise a citizen could be silently stripped of the
 *       ability to act). Returns {@code BAD_REQUEST}.</li>
 *   <li><b>Ownership guard:</b> a revoke must target an assignment that belongs to the named account, else
 *       {@code NOT_FOUND} (an admin cannot revoke account B's grant by passing account A's path id).</li>
 * </ul>
 *
 * <p><b>🔒 Privacy (PRD §18, PDPA):</b> every read projection masks the phone and <b>never</b> returns the
 * {@code idNo}; the detail returns a bare {@code locationCount} (the private {@code ProfileLocation}s are
 * never listed). Audit rows carry public-ids + machine reason codes only (the acting admin from the security
 * context, never a body field) — never PII (L-1).</p>
 *
 * <p>WHY the acting-admin id is a method parameter (not re-read here): the admin module already resolved it
 * from {@code CurrentUser.requirePublicId()} and applied the {@code isNotSelf} fence before calling; passing
 * it keeps this port a pure state-change + audit writer with no hidden dependency on the web security
 * context (it is callable from a worker/test the same way). The port still trusts only the value the gated
 * caller supplies — there is no body-supplied actor anywhere on this path.</p>
 *
 * <p><b>CENTRAL INTEGRATION NEED (for the solution-architect):</b> the optional constituency scope on a
 * grant is resolved here by a JPQL query over the geography {@link Constituency} <i>entity</i> (the
 * allow-listed cross-module FK model reference, ARCHITECTURE §4.3) because geography publishes no
 * synchronous {@code *QueryApi} for a constituency-by-public-id lookup yet. The canonical ADR-0013 §1 shape
 * is a {@code geography.api.ConstituencyQueryApi.requireByPublicId(UUID)} that identity would inject; adding
 * it is deferred (out of this increment's identity+admin scope). When it lands, swap {@link #em} for that
 * port with no contract change.</p>
 */
// Explicit bean name: the admin module also has a `UserAdminService` (the operator workflow); Spring's
// component scan rejects two beans with the same default simple name ('userAdminService'). Naming this one
// distinctly avoids the ConflictingBeanDefinitionException while keeping each module's intent-revealing
// class name. Callers inject by the published interface (UserAdminQueryApi/UserAdminApi), not by bean name.
@Service("identityUserAdminService")
@Transactional
public class UserAdminService implements UserAdminQueryApi, UserAdminApi {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * The shared persistence context, used only to resolve the optional constituency scope by its public id
     * via a JPQL query over the {@link Constituency} <b>entity</b> (a {@code geography.domain.model} type
     * identity already FK-references on {@code RoleAssignment} — ARCHITECTURE §4.3, the blessed cross-module
     * FK exception). WHY the {@code EntityManager} rather than geography's {@code ConstituencyRepository}: a
     * sibling module's {@code domain.repository} is encapsulated and must not be reached into across the
     * boundary (ADR-0013, enforced by {@code ModuleBoundaryTest}); the allow-listed way to reference the
     * geography <i>entity</i> is by the model type, which this query does. Resolving by a published
     * geography {@code *QueryApi} would be the eventual shape once geography publishes one — recorded as a
     * CENTRAL NEED (see the class note) — but adding that port is out of this increment's identity+admin
     * scope.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * @param userRepository           account search + lookup (the admin list query lives here).
     * @param profileRepository        resolves an account's profile for the display name + location count.
     * @param profileLocationRepository counts an account's pinned locations (count only — never lists them).
     * @param roleRepository           resolves a {@link RoleName} to its catalogue row on grant.
     * @param roleAssignmentRepository  reads/creates/end-dates grants (the additive-role aggregate).
     * @param audit                     append-only audit writer (grants/revokes/suspensions; refs only).
     * @param clock                     time source for effective-window stamping (testable — never inline now()).
     */
    public UserAdminService(UserRepository userRepository,
                            ProfileRepository profileRepository,
                            ProfileLocationRepository profileLocationRepository,
                            RoleRepository roleRepository,
                            RoleAssignmentRepository roleAssignmentRepository,
                            AuditEventService audit,
                            ClockPort clock) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.audit = audit;
        this.clock = clock;
    }

    // ----------------------------------- Reads (UserAdminQueryApi) -----------------------------------

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public UserAdminPage<UserAdminSummary> listUsers(UserAdminFilter filter, int page, int size) {
        UserAdminFilter f = filter == null
                ? new UserAdminFilter(null, null, null, null, null)
                : filter;
        // Parse the optional enum filters. A blank value = "no constraint"; a NON-blank but unparseable value
        // (a stale/unknown enum name) must yield an EMPTY page — NOT "unfiltered" — so a stale admin filter
        // narrows to nothing rather than silently showing everyone. We detect that case explicitly: if a
        // filter string was supplied yet did not parse, short-circuit to empty (never a 500 — deny-by-default).
        TrustTier tier = parseEnum(TrustTier.class, f.tier());
        UserStatus status = parseEnum(UserStatus.class, f.status());
        RoleName role = parseEnum(RoleName.class, f.role());
        if (suppliedButUnparsed(f.tier(), tier)
                || suppliedButUnparsed(f.status(), status)
                || suppliedButUnparsed(f.role(), role)) {
            return new UserAdminPage<>(List.of(), Math.max(page, 0), Math.max(size, 1), 0L);
        }

        // Newest-first; sort property is fixed server-side so the client cannot inject an arbitrary one.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<User> result = userRepository.adminSearch(
                blankToNull(f.name()), blankToNull(f.phoneSuffix()), tier, status, role,
                RoleStatus.ACTIVE, pageable);

        List<UserAdminSummary> content = new ArrayList<>(result.getContent().size());
        for (User u : result.getContent()) {
            content.add(toSummary(u));
        }
        return new UserAdminPage<>(content, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public UserAdminDetail getUser(UUID userPublicId) {
        User user = requireUser(userPublicId);
        Profile profile = profileRepository.findByUser(user).orElse(null);

        List<UserRoleGrant> grants = new ArrayList<>();
        for (RoleAssignment ra : roleAssignmentRepository.findByUser(user)) {
            grants.add(toGrant(ra));
        }
        long locationCount = profile == null ? 0L : profileLocationRepository.countByProfile(profile);

        return new UserAdminDetail(
                user.getPublicId(),
                displayName(profile),
                maskPhone(user.getPhone()),
                user.getEmail(),
                user.getTrustTier().name(),
                user.getStatus().name(),
                profile != null && profile.isIdVerified(),
                grants,
                locationCount,
                user.getCreatedAt(),
                user.getLastLoginAt());
    }

    // ---------------------------------- Commands (UserAdminApi) ----------------------------------

    /** {@inheritDoc} */
    @Override
    public UUID grantRole(UUID actingAdminPublicId, UUID userPublicId, GrantRoleCommand command) {
        User user = requireUser(userPublicId);
        RoleName roleName = parseRoleNameOrBadRequest(command.roleName());

        // Additive + idempotent (D15): if the account already holds this role ACTIVE, do not create a second
        // grant — return the existing one. The grant never strips other roles.
        RoleAssignment existing = roleAssignmentRepository
                .findFirstByUserAndRole_NameAndStatus(user, roleName, RoleStatus.ACTIVE)
                .orElse(null);
        if (existing != null) {
            // No-op success: still audited so the operator action is recorded, with an IDEMPOTENT marker.
            recordRoleEvent(AuditEventType.ROLE_GRANTED, AuditOutcome.SUCCESS,
                    actingAdminPublicId, user.getPublicId(), roleName.name() + ":IDEMPOTENT");
            return existing.getPublicId();
        }

        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST));

        RoleAssignment grant = RoleAssignment.grant(user, role, RoleStatus.ACTIVE);
        grant.setAreaIds(command.areaIds());
        grant.setCategoryIds(command.categoryIds());
        grant.setConstituency(resolveConstituency(command.constituencyId()));
        grant.setEffectiveWindow(command.effectiveFrom(), command.effectiveTo());
        RoleAssignment saved = roleAssignmentRepository.save(grant);

        recordRoleEvent(AuditEventType.ROLE_GRANTED, AuditOutcome.SUCCESS,
                actingAdminPublicId, user.getPublicId(), roleName.name());
        return saved.getPublicId();
    }

    /** {@inheritDoc} */
    @Override
    public void revokeRole(UUID actingAdminPublicId, UUID userPublicId, UUID assignmentPublicId) {
        User user = requireUser(userPublicId);
        RoleAssignment grant = roleAssignmentRepository.findByPublicId(assignmentPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        // Ownership guard: the assignment must belong to the named account (an admin cannot revoke another
        // account's grant by passing this account's path id).
        if (!grant.getUser().getPublicId().equals(user.getPublicId())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        // Protect the base role: never revoke the account's last active CITIZEN grant (every account keeps
        // its base civic role — §6.4). BAD_REQUEST, not a silent no-op, so the operator sees why.
        if (grant.getRole().getName() == RoleName.CITIZEN
                && roleAssignmentRepository.countByUserAndRole_NameAndStatus(
                        user, RoleName.CITIZEN, RoleStatus.ACTIVE) <= 1) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }

        // Idempotent: revoking an already-FORMER grant is a no-op success (it is already end-dated).
        if (grant.getStatus() != RoleStatus.FORMER) {
            grant.revoke(clock.now());
            roleAssignmentRepository.save(grant);
        }

        recordRoleEvent(AuditEventType.ROLE_REVOKED, AuditOutcome.SUCCESS,
                actingAdminPublicId, user.getPublicId(), grant.getRole().getName().name());
    }

    /** {@inheritDoc} */
    @Override
    public void suspendUser(UUID actingAdminPublicId, UUID userPublicId, String reasonCode) {
        User user = requireUser(userPublicId);
        user.suspend();
        userRepository.save(user);
        recordRoleEvent(AuditEventType.USER_SUSPENDED, AuditOutcome.SUCCESS,
                actingAdminPublicId, user.getPublicId(), blankToNull(reasonCode));
    }

    /** {@inheritDoc} */
    @Override
    public void reinstateUser(UUID actingAdminPublicId, UUID userPublicId) {
        User user = requireUser(userPublicId);
        user.activate();
        userRepository.save(user);
        recordRoleEvent(AuditEventType.USER_REINSTATED, AuditOutcome.SUCCESS,
                actingAdminPublicId, user.getPublicId(), null);
    }

    // --------------------------------------- internals ---------------------------------------

    /**
     * Resolves an account by public id or throws {@code NOT_FOUND}. Soft-deleted accounts are excluded by
     * the entity's {@code @SQLRestriction}, so a tombstoned account is correctly invisible to admin.
     */
    private User requireUser(UUID userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Maps an account to its privacy-minimised list summary (masked phone, active role names only). */
    private UserAdminSummary toSummary(User user) {
        Profile profile = profileRepository.findByUser(user).orElse(null);
        List<String> roles = new ArrayList<>();
        for (RoleAssignment ra : roleAssignmentRepository
                .findByUser_PublicIdAndStatus(user.getPublicId(), RoleStatus.ACTIVE)) {
            roles.add(ra.getRole().getName().name());
        }
        return new UserAdminSummary(
                user.getPublicId(),
                displayName(profile),
                maskPhone(user.getPhone()),
                user.getTrustTier().name(),
                user.getStatus().name(),
                roles,
                user.getCreatedAt());
    }

    /** Maps a role grant to its boundary projection (assignment id + role + scope + effective window). */
    private UserRoleGrant toGrant(RoleAssignment ra) {
        Constituency c = ra.getConstituency();
        return new UserRoleGrant(
                ra.getPublicId(),
                ra.getRole().getName().name(),
                ra.getStatus().name(),
                List.copyOf(ra.getAreaIds()),
                List.copyOf(ra.getCategoryIds()),
                c == null ? null : c.getPublicId(),
                ra.getEffectiveFrom(),
                ra.getEffectiveTo());
    }

    /** @return the profile's display name (first + last, trimmed), or {@code null} if unset/no profile. */
    private String displayName(Profile profile) {
        if (profile == null) {
            return null;
        }
        String first = profile.getFirstName();
        String last = profile.getLastName();
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Masks a phone for display: keeps the country/operator prefix and the last 4 digits, replacing the
     * middle with {@code ****} (e.g. {@code +255712345678} → {@code +2557****5678}). Never returns the raw
     * number anywhere a client can see it (PRD §18, PDPA). A short/null phone degrades to a fully masked
     * value rather than leaking digits.
     *
     * @param phone the stored E.164 phone, or {@code null}.
     * @return the masked phone (never the raw number).
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return "****";
        }
        String prefix = phone.substring(0, 5);   // e.g. "+2557"
        String suffix = phone.substring(phone.length() - 4);
        return prefix + "****" + suffix;
    }

    /**
     * Resolves the optional constituency scope by public id, or {@code null} when none is requested; an
     * unknown id is {@code NOT_FOUND}. Queries the {@link Constituency} entity via JPQL (the allow-listed
     * cross-module model reference — never geography's repository, see {@link #em}). The entity's
     * {@code @SQLRestriction} excludes soft-deleted constituencies, so a tombstoned id is correctly unknown.
     */
    private Constituency resolveConstituency(UUID constituencyPublicId) {
        if (constituencyPublicId == null) {
            return null;
        }
        return em.createQuery(
                        "SELECT c FROM Constituency c WHERE c.publicId = :pid", Constituency.class)
                .setParameter("pid", constituencyPublicId)
                .getResultList().stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Parses a {@link RoleName}, mapping an unknown/blank value to {@code BAD_REQUEST} (un-grantable role). */
    private RoleName parseRoleNameOrBadRequest(String name) {
        RoleName role = parseEnum(RoleName.class, name);
        if (role == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        return role;
    }

    /**
     * @return {@code true} if the caller supplied a non-blank filter value that did NOT parse to a known
     *         enum (a stale/unknown name) — the signal to return an empty page rather than treat it as "no
     *         filter". A blank input (parsed {@code null} from blank) is "no constraint", not unparsed.
     */
    private static boolean suppliedButUnparsed(String raw, Enum<?> parsed) {
        return StringUtils.hasText(raw) && parsed == null;
    }

    /** Null-safe, blank-tolerant enum parse: an unknown/blank name yields {@code null} (no match / no filter). */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** @return the trimmed value, or {@code null} if blank (so a blank filter/reason is "no constraint"). */
    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    /**
     * Records an append-only audit row for a user-admin action (grant/revoke/suspend/reinstate). The acting
     * admin is the actor and the target account is the subject; the reason code is a machine token (the
     * {@code RoleName} or suspension reason) — never PII (L-1, PRD §18).
     */
    private void recordRoleEvent(AuditEventType type, AuditOutcome outcome,
                                 UUID actingAdmin, UUID subject, String reasonCode) {
        AuditEvent.Builder builder = AuditEvent.Builder.of(type, outcome)
                .actor(actingAdmin)
                .subject(subject);
        if (reasonCode != null) {
            builder.reason(reasonCode);
        }
        audit.record(builder.build());
    }
}
