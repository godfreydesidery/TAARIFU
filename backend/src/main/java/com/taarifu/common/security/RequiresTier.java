package com.taarifu.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as gated on a minimum citizen <b>trust tier</b> T0–T3 (PRD §7.3, D13,
 * ARCHITECTURE.md §6.2).
 *
 * <p>Responsibility: declares "this action requires at least tier {@code value}". The trust tier on
 * the JWT is only a hint; an interceptor (added in the auth increment) re-resolves the caller's
 * <b>live</b> tier server-side before allowing the action — the token claim is never trusted for
 * high-stakes gating (PRD §17, §18).</p>
 *
 * <p>WHY a dedicated annotation rather than folding tier into {@code @PreAuthorize} strings: it keeps
 * the tier requirement declarative, greppable, and testable, and it pairs with the integrity fence —
 * binding actions check <b>tier + electoral scope + one-per-person only</b> and must <b>never</b> read
 * a token balance (D18, §23.5). This annotation is the tier half of that fence.</p>
 *
 * <p>This foundation increment ships the annotation and its contract; the enforcing interceptor lands
 * with the auth increment (no protected action exists yet — geography reads are public).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTier {

    /**
     * @return the minimum trust tier (e.g. {@code "T3"} for binding actions: sign petition, rate MP,
     *         vote in a binding poll). Compared server-side against the caller's live tier.
     */
    String value();
}
