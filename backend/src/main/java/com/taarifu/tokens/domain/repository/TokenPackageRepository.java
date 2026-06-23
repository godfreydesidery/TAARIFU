package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.TokenPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link TokenPackage} — Phase 2 seam (ARCHITECTURE.md §3.3, PRD §23.6).
 *
 * <p>Responsibility: catalogue listing for the (Phase 2) purchase flow and admin CRUD. No purchase logic
 * ships in MVP; this exists to keep the seam complete.</p>
 */
public interface TokenPackageRepository extends JpaRepository<TokenPackage, Long> {

    /** @return active (purchasable) packages. */
    List<TokenPackage> findByActiveTrue();

    /**
     * @param publicId a package's public id.
     * @return the package, or empty.
     */
    Optional<TokenPackage> findByPublicId(UUID publicId);
}
