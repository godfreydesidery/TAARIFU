package com.taarifu.identity.application.service;

import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Live scope-aware authorization — MF-3 (AUTH-DESIGN §8, ADR-0011 §4, D13/D16).
 *
 * <p>Responsibility: the {@code @taarifuAuthz} bean method security calls to enforce <b>role × scope</b>.
 * Every check reads the caller's <b>active</b> {@link RoleAssignment} scope from the database — never the
 * token — and is deny-by-default. An <b>empty</b> scope set on an active assignment means "unrestricted
 * within that role" (the one explicit permissive case).</p>
 *
 * <p>WHY the bean name {@code taarifuAuthz}: it lets {@code @PreAuthorize("@taarifuAuthz.canActOnArea(
 * #areaPublicId)")} reference it. The contract + {@link #isNotSelf} ship now (the conflict check is
 * needed the moment any "act on someone" endpoint exists); the per-endpoint annotations land with the
 * owning modules (reporting/responders/institutions/engagement).</p>
 *
 * <p>WHY area matching is direct-or-unrestricted in this increment: ancestor resolution (a District
 * grant covering its Wards) requires the geography closure-table public API, which is consumed when the
 * first scoped endpoint lands (AUTH-DESIGN §8.2). Until then this guard matches an area directly or
 * treats an empty set as unrestricted — deny-by-default for anything else — so it is never falsely
 * permissive. The ancestor extension is a documented, additive change confined to {@link #canActOnArea}.</p>
 */
@Component("taarifuAuthz")
public class ScopeGuardImpl implements ScopeGuard {

    private final RoleAssignmentRepository roleAssignmentRepository;

    /**
     * @param roleAssignmentRepository the live scope source (active assignments only — MF-3).
     */
    public ScopeGuardImpl(RoleAssignmentRepository roleAssignmentRepository) {
        this.roleAssignmentRepository = roleAssignmentRepository;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean canActOnArea(UUID areaPublicId) {
        if (areaPublicId == null) {
            return false;
        }
        for (RoleAssignment ra : activeAssignments()) {
            var areas = ra.getAreaIds();
            // Empty set = unrestricted within this role; otherwise must contain the target area directly.
            if (areas.isEmpty() || areas.contains(areaPublicId)) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean canActOnCategory(UUID categoryPublicId) {
        if (categoryPublicId == null) {
            return false;
        }
        for (RoleAssignment ra : activeAssignments()) {
            var categories = ra.getCategoryIds();
            if (categories.isEmpty() || categories.contains(categoryPublicId)) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean canActInConstituency(UUID constituencyPublicId) {
        if (constituencyPublicId == null) {
            return false;
        }
        for (RoleAssignment ra : activeAssignments()) {
            Constituency c = ra.getConstituency();
            if (c != null && constituencyPublicId.equals(c.getPublicId())) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNotSelf(UUID subjectPublicId) {
        // Deny-by-default: with no authenticated caller this cannot be a permitted "act on other".
        return CurrentUser.current()
                .map(cu -> !cu.publicId().equals(subjectPublicId))
                .orElse(false);
    }

    /** @return the caller's active role assignments (the live scope set); empty if unauthenticated. */
    private List<RoleAssignment> activeAssignments() {
        return CurrentUser.current()
                .map(cu -> roleAssignmentRepository
                        .findByUser_PublicIdAndStatus(cu.publicId(), RoleStatus.ACTIVE))
                .orElseGet(List::of);
    }
}
