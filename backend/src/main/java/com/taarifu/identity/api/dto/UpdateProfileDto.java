package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Profile-completion request (PATCH semantics) on the path to T2 (AUTH-DESIGN §6).
 *
 * <p>All fields are optional; a {@code null} leaves the stored value unchanged. Reaching T2 needs at
 * least first + last name here, plus ≥1 pinned location (the location pin is a separate endpoint).</p>
 *
 * @param firstName   given/first name.
 * @param lastName    family name.
 * @param dateOfBirth date of birth (person demographics).
 * @param gender      gender code.
 * @param nationality 3-letter nationality code (diaspora support).
 */
public record UpdateProfileDto(
        @Size(max = 120, message = "identity.firstName.tooLong") String firstName,
        @Size(max = 120, message = "identity.lastName.tooLong") String lastName,
        LocalDate dateOfBirth,
        @Size(max = 16, message = "identity.gender.tooLong") String gender,
        @Size(max = 3, message = "identity.nationality.tooLong") String nationality
) {
}
