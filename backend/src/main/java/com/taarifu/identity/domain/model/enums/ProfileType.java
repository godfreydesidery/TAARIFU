package com.taarifu.identity.domain.model.enums;

/**
 * Whether a {@code Profile} represents a natural person or an organisation (PRD §9.1).
 *
 * <p>Responsibility: discriminates person profiles (which carry demographics + a government ID) from
 * organisation profiles (which carry org registration fields). The same {@code User} account is used
 * for both with additive roles (§6.4); the profile type fixes which attribute set applies.</p>
 */
public enum ProfileType {

    /** A natural person (citizen, representative, official — all on one account, §6.4). */
    PERSON,

    /** An organisation (CSO, company, institution) acting as a civic actor. */
    ORGANIZATION
}
