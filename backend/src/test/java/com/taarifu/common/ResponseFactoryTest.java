package com.taarifu.common;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiError;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.ErrorDetail;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.i18n.MessageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResponseFactory} (FOUNDATION-SCOPE.md §6, CLAUDE.md §10).
 *
 * <p>Responsibility: proves the single envelope is built correctly for success, paged, and error cases
 * — the contract every controller and the global handler relies on. Uses the real i18n bundles so the
 * Swahili-first message resolution is exercised, not mocked away (ADR-0010).</p>
 */
class ResponseFactoryTest {

    private ResponseFactory factory;

    @BeforeEach
    void setUp() {
        // Pin the request locale to Swahili (the platform default, ADR-0010) so message assertions are
        // deterministic regardless of the JVM/host locale.
        LocaleContextHolder.setLocale(new Locale("sw"));
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        factory = new ResponseFactory(new MessageResolver(source));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void ok_buildsSuccessEnvelopeWithLocalisedMessage() {
        ApiResponse<String> response = factory.ok("payload");

        assertThat(response.success()).isTrue();
        // Success envelopes carry the integer HTTP status 200 (was the String machine code "OK").
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.meta()).isNull();
        assertThat(response.timestamp()).isNotNull();
        // Default locale is Swahili → "Imefaulu".
        assertThat(response.message()).isEqualTo("Imefaulu");
    }

    @Test
    void paged_carriesMetaAlongsideData() {
        PageMeta meta = new PageMeta(0, 20, 137, 7);
        ApiResponse<String> response = factory.paged("content", meta);

        assertThat(response.success()).isTrue();
        assertThat(response.meta()).isEqualTo(meta);
        assertThat(response.data()).isEqualTo("content");
    }

    @Test
    void error_buildsFailureEnvelopeWithHttpStatusAndMachineCodeInData() {
        ApiResponse<ApiError> response = factory.error(ErrorCode.NOT_FOUND, (List<ErrorDetail>) null);

        assertThat(response.success()).isFalse();
        // Top level now carries the integer HTTP status derived from ErrorCode.httpStatus().value().
        assertThat(response.statusCode()).isEqualTo(404);
        // The stable machine code is preserved inside data (data.code), not at the top level (ADR-0008).
        assertThat(response.data()).isNotNull();
        assertThat(response.data().code()).isEqualTo("NOT_FOUND");
        // Non-validation error → no field-level errors (data.errors omitted from JSON).
        assertThat(response.data().errors()).isNull();
        // Clients branch on the code; the message is the localised Swahili default.
        assertThat(response.message()).isEqualTo("Haikupatikana");
    }

    @Test
    void error_withFieldErrors_surfacesThemInData() {
        ErrorDetail field = new ErrorDetail("phone", "NotBlank", "Lazima ujaze");
        ApiResponse<ApiError> response = factory.error(ErrorCode.VALIDATION_FAILED, List.of(field));

        assertThat(response.success()).isFalse();
        // VALIDATION_FAILED maps to HTTP 400.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.data().code()).isEqualTo("VALIDATION_FAILED");
        // Field-level validation errors live at data.errors[] (folded in from the old ValidationErrors).
        assertThat(response.data().errors()).containsExactly(field);
    }
}
