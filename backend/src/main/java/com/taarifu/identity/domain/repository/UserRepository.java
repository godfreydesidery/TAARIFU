package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link User} accounts (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: account lookups for authentication and the one-account-per-phone rule.
 * {@link #existsByPhone(String)} backs the signup guard (D11/D15); {@link #findByPhone(String)} backs
 * login. Public lookups use {@code publicId} (ADR-0006). Soft-deleted accounts are excluded by the
 * entity's {@code @SQLRestriction}.</p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * @param phone E.164 phone.
     * @return the account with that phone, or empty.
     */
    Optional<User> findByPhone(String phone);

    /**
     * @param publicId the account's public id.
     * @return the account, or empty.
     */
    Optional<User> findByPublicId(UUID publicId);

    /**
     * @param phone E.164 phone.
     * @return {@code true} if an account already uses this phone (one account per phone — D11/D15).
     */
    boolean existsByPhone(String phone);
}
