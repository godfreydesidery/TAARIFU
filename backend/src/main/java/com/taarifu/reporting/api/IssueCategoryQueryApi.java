package com.taarifu.reporting.api;

import java.util.UUID;

/**
 * The reporting module's <b>public, in-process query port</b> for validating an issue category
 * (ADR-0013 §1, §4a). The responders module calls this (synchronous {@code responders → reporting}) to
 * validate the {@code categoryId}s on a {@code Responder}/{@code RoutingRule}, without importing reporting's
 * internals (ARCHITECTURE §3.2).
 *
 * <p>Responsibility: answer "is this a valid (active) issue category?" so routing/capability configuration
 * can never reference a non-existent or retired category. The caller treats the result as opaque truth.</p>
 */
public interface IssueCategoryQueryApi {

    /**
     * Asserts the category exists (and is not soft-deleted), throwing if not.
     *
     * @param categoryPublicId the category's public id.
     * @throws com.taarifu.common.error.ApiException with {@code NOT_FOUND} if no such category exists.
     */
    void requireCategory(UUID categoryPublicId);

    /**
     * @param categoryPublicId the category's public id.
     * @return {@code true} if a non-deleted category with that id exists.
     */
    boolean exists(UUID categoryPublicId);
}
