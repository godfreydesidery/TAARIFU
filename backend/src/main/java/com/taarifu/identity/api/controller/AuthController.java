package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.identity.api.dto.AuthResultDto;
import com.taarifu.identity.api.dto.LoginResultDto;
import com.taarifu.identity.api.dto.OtpChallengeDto;
import com.taarifu.identity.api.dto.OtpRequestDto;
import com.taarifu.identity.api.dto.PasswordLoginDto;
import com.taarifu.identity.api.dto.RefreshRequestDto;
import com.taarifu.identity.api.dto.TokenPairDto;
import com.taarifu.identity.api.dto.TotpLoginDto;
import com.taarifu.identity.api.dto.VerifyOtpDto;
import com.taarifu.identity.application.service.LoginService;
import com.taarifu.identity.application.service.SignupService;
import com.taarifu.identity.application.service.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Thin REST surface for authentication + tokens (AUTH-DESIGN §14.1, ADR-0011).
 *
 * <p>Responsibility: validates input and delegates to the application services, wrapping every result in
 * the single {@link ApiResponse} envelope. It owns <b>no</b> business logic and <b>no</b> transaction
 * boundary (that lives in the services). The public endpoints (OTP, signup, login, refresh) are
 * permitted by {@code SecurityConfig}; logout requires authentication (deny-by-default).</p>
 *
 * <p>Security notes: OTP request returns {@code 202} non-committally (no enumeration); password/OTP
 * login failures surface a uniform {@code UNAUTHENTICATED}; the raw tokens in responses are never
 * logged (S-4). The {@code /auth/**} paths are added to the public allow-list in {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final ResponseFactory responses;

    /**
     * @param signupService signup flow.
     * @param loginService  login flows.
     * @param tokenService  refresh/logout.
     * @param responses     envelope builder.
     */
    public AuthController(SignupService signupService,
                          LoginService loginService,
                          TokenService tokenService,
                          ResponseFactory responses) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.responses = responses;
    }

    /**
     * Requests a SIGNUP OTP. Always {@code 202} with a challenge id (anti-enumeration).
     *
     * @param request the destination phone.
     * @return {@code 202} + the challenge id.
     */
    @PostMapping("/otp/request")
    public ResponseEntity<ApiResponse<OtpChallengeDto>> requestSignupOtp(
            @Valid @RequestBody OtpRequestDto request) {
        UUID challengeId = signupService.requestSignupOtp(request.phone());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responses.ok(new OtpChallengeDto(challengeId)));
    }

    /**
     * Completes signup by verifying the OTP → creates/activates a T1 account + issues tokens.
     *
     * @param request the challenge id + code.
     * @return {@code 201} + the account id, tier, and token pair.
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AuthResultDto>> completeSignup(
            @Valid @RequestBody VerifyOtpDto request) {
        SignupService.SignupResult result = signupService.completeSignup(request.challengeId(), request.code());
        AuthResultDto body = new AuthResultDto(
                result.userPublicId(), result.tier().name(), toDto(result.tokens()));
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(body));
    }

    /**
     * Requests a LOGIN OTP for passwordless/recovery login. Always {@code 202} (anti-enumeration).
     *
     * @param request the destination phone.
     * @return {@code 202} + the challenge id.
     */
    @PostMapping("/login/otp/request")
    public ResponseEntity<ApiResponse<OtpChallengeDto>> requestLoginOtp(
            @Valid @RequestBody OtpRequestDto request) {
        UUID challengeId = loginService.requestLoginOtp(request.phone());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responses.ok(new OtpChallengeDto(challengeId)));
    }

    /**
     * Password login by phone or email. For a staff/MFA account this returns {@code mfaRequired=true} and
     * an {@code mfaToken} (the TOTP second factor must be completed at {@code /auth/login/totp}, N-4) —
     * <b>no</b> token pair is issued here.
     *
     * @param request account key + password.
     * @return {@code 200} + the login result (token pair, or an MFA challenge for staff).
     */
    @PostMapping("/login/password")
    public ResponseEntity<ApiResponse<LoginResultDto>> loginWithPassword(
            @Valid @RequestBody PasswordLoginDto request) {
        LoginService.LoginOutcome outcome =
                loginService.loginWithPassword(request.accountKey(), request.password());
        return ResponseEntity.ok(responses.ok(toDto(outcome)));
    }

    /**
     * Passwordless OTP login. For a staff/MFA account this returns an MFA challenge (N-4), not a pair.
     *
     * @param request challenge id + code.
     * @return {@code 200} + the login result (token pair, or an MFA challenge for staff).
     */
    @PostMapping("/login/otp")
    public ResponseEntity<ApiResponse<LoginResultDto>> loginWithOtp(
            @Valid @RequestBody VerifyOtpDto request) {
        LoginService.LoginOutcome outcome = loginService.loginWithOtp(request.challengeId(), request.code());
        return ResponseEntity.ok(responses.ok(toDto(outcome)));
    }

    /**
     * Staff second-factor login step (N-4): exchanges the {@code MFA_CHALLENGE} token + TOTP code for the
     * real token pair. Public (it carries the challenge token, not a prior access token).
     *
     * @param request the MFA challenge token + the TOTP code.
     * @return {@code 200} + the issued token pair.
     */
    @PostMapping("/login/totp")
    public ResponseEntity<ApiResponse<TokenPairDto>> loginWithTotp(
            @Valid @RequestBody TotpLoginDto request) {
        TokenService.TokenPair pair = loginService.completeTotpLogin(request.mfaToken(), request.totp());
        return ResponseEntity.ok(responses.ok(toDto(pair)));
    }

    /**
     * Rotates a refresh token (single-use; reuse revokes the family — S-3).
     *
     * @param request the raw refresh token.
     * @return {@code 200} + the new token pair.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenPairDto>> refresh(
            @Valid @RequestBody RefreshRequestDto request) {
        TokenService.TokenPair pair = tokenService.rotate(request.refreshToken());
        return ResponseEntity.ok(responses.ok(toDto(pair)));
    }

    /**
     * Single-session logout: revokes the presented refresh token. Idempotent.
     *
     * @param request the refresh token to revoke.
     * @return {@code 200} success envelope.
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshRequestDto request) {
        tokenService.logout(request.refreshToken());
        return ResponseEntity.ok(responses.ok(null));
    }

    /**
     * All-sessions logout: revokes every live refresh family for the caller.
     *
     * @return {@code 200} success envelope.
     */
    @PostMapping("/logout/all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> logoutAll() {
        tokenService.logoutAll(CurrentUser.requirePublicId());
        return ResponseEntity.ok(responses.ok(null));
    }

    private static TokenPairDto toDto(TokenService.TokenPair pair) {
        return new TokenPairDto(pair.accessToken(), pair.refreshToken());
    }

    /** Maps a first-factor outcome to the wire DTO: a token pair, or an MFA challenge for staff (N-4). */
    private static LoginResultDto toDto(LoginService.LoginOutcome outcome) {
        return new LoginResultDto(
                outcome.mfaRequired(),
                outcome.tokens() == null ? null : toDto(outcome.tokens()),
                outcome.mfaToken());
    }
}
