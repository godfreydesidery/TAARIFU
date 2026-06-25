package com.taarifu.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests enforcing the module-boundary rules from ARCHITECTURE.md §3.4 (ADR-0003, ADR-0009).
 *
 * <p>Responsibility: makes the architecture's boundaries <b>mechanically enforced</b>, not just
 * documented — boundaries that rot silently are the legacy failure this design exists to prevent. The
 * rules assert that the shared kernel stays independent, that controllers carry no transaction
 * boundary, and that vendor SDKs do not leak into domain ports.</p>
 *
 * <p>WHY these specific rules: they are the load-bearing invariants of the modular monolith (clean
 * dependency direction, thin controllers, ports free of vendor types). As feature modules landed, the
 * cross-module {@code domain}/{@code infrastructure} import rule promised here was tightened by
 * {@link #noModuleDependsOnAnotherModulesDomainOrInfrastructure()} (ADR-0013).</p>
 */
class ModuleBoundaryTest {

    /**
     * Matches a top-level Taarifu module segment, e.g. {@code com.taarifu.reporting...} → {@code reporting}.
     * Used by the cross-module-internals rule to compare the importing and target module segments so a
     * class may freely reach into its <i>own</i> {@code domain}/{@code infrastructure}.
     */
    private static final Pattern MODULE_SEGMENT =
            Pattern.compile("^com\\.taarifu\\.([^.]+)\\.");

    /** Imports production classes only (test classes excluded) once for all rules. */
    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.taarifu");

    @Test
    void commonKernelDependsOnNoFeatureModule() {
        // The shared kernel must depend on nothing else in com.taarifu (ARCHITECTURE §3.2 rule 1).
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.taarifu.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.taarifu.geography..",
                        "com.taarifu.identity..",
                        "com.taarifu.reporting..",
                        "com.taarifu.institutions..",
                        "com.taarifu.communications..",
                        "com.taarifu.responders..",
                        "com.taarifu.tokens..",
                        "com.taarifu.moderation..",
                        "com.taarifu.engagement..",
                        "com.taarifu.accountability..",
                        "com.taarifu.admin..",
                        // Phase-2 wave modules + the others that landed since this explicit list was last
                        // touched — the shared kernel must depend on NONE of them. (The predicate rule
                        // noModuleDependsOnAnotherModulesDomainOrInfrastructure() already covers all modules
                        // generically; this explicit list is the belt-and-braces guard against a future
                        // common → feature regression and must enumerate every feature module to bite.)
                        "com.taarifu.analytics..",
                        "com.taarifu.media..",
                        "com.taarifu.payments..",
                        "com.taarifu.privacy..",
                        "com.taarifu.search..",
                        "com.taarifu.ussd..")
                .because("the shared kernel must not depend on any feature module (ARCHITECTURE §3.2)");
        rule.check(productionClasses);
    }

    @Test
    void controllersHaveNoTransactionalBoundary() {
        // Controllers stay thin; the transaction boundary lives in the application layer (ADR-0003).
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api.controller..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .because("controllers must not own transactions (ARCHITECTURE §3.3)");
        rule.check(productionClasses);
    }

    @Test
    void domainPortsHaveNoVendorImports() {
        // Ports are plain interfaces; vendor SDKs live only in infrastructure.adapter (ADR-0004).
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain.port..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("software.amazon..", "com.nimbusds..", "org.springframework.web..")
                .because("domain ports must stay free of vendor/framework SDKs (ARCHITECTURE §3.4)");
        rule.check(productionClasses);
    }

    @Test
    void entitiesStayWithinDomainModel() {
        // JPA entities must not leak into api/application layers as types (DTOs at the boundary).
        ArchRule rule = classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("..domain.model..")
                .because("entities live in domain.model and never leak past a module (CLAUDE.md §8)");
        rule.check(productionClasses);
    }

    /**
     * No type in a module's <b>published port surface</b> ({@code ..api..}, which includes the event
     * records under {@code ..api.event..}) may be a JPA {@code @Entity}. This mechanically guards the
     * "no entity / no PII in published ports & events" invariant — the cross-module contract is
     * ids/codes/enums + DTOs only, never a persistence aggregate (ADR-0013 §3 follow-up; ADR-0014 §1, §4
     * — outbox payloads carry no PII; security review P3-1).
     *
     * <p>WHY this is the cheap mechanical insurance the review asked for: the no-PII / no-entity rule on
     * published ports and event payloads was enforced only by contract + reviewer discipline. An
     * {@code @Entity} sitting in {@code ..api..} is the load-bearing failure mode — it would drag a
     * persistence aggregate (and any PII fields, lazy associations, or {@code domain.model} types) into a
     * sibling module's compile surface and into serialised event payloads. This rule makes that
     * impossible to commit unnoticed. It complements {@link #entitiesStayWithinDomainModel()} (entities
     * live in {@code domain.model}) by asserting the same boundary from the {@code api} side — together
     * they pin entities strictly to {@code domain.model}.</p>
     *
     * <p>{@code allowEmptyShould(true)} because a module may have an {@code api} package with no entities
     * at all (the expected, healthy state) — an empty match set is a pass, not a configuration error.</p>
     */
    @Test
    void noEntityInPublishedApiOrEvents() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().beAnnotatedWith("jakarta.persistence.Entity")
                .because("published ports and api.event payloads carry ids/codes/enums + DTOs only — never a "
                        + "JPA entity (no entity / no PII leak in the cross-module contract; "
                        + "ADR-0013 §3, ADR-0014 §1/§4, review P3-1)");
        rule.allowEmptyShould(true).check(productionClasses);
    }

    /**
     * No module may import another module's internal layers ({@code domain} / {@code infrastructure}).
     * Cross-module integration is permitted ONLY through the callee's published {@code ..api..} package
     * (ADR-0013): synchronous reads via a published {@code *QueryApi}/{@code *Api}/{@code *LifecycleApi}
     * port, async via events. This is the rule ADR-0003's {@code ModuleBoundaryTest} Javadoc promised to
     * add as feature modules landed.
     *
     * <p>This is the explicit (predicate-based) form named in ADR-0013 §3 as the canonical version to
     * commit, "adjusting the allow-list" so the architecture's already-sanctioned cross-module references
     * stay GREEN. The full allow-list (same module, the shared kernel {@code common}, foundation
     * {@code geography.domain.model} FK targets, and any module's {@code domain.port} interfaces) is
     * documented on {@link #notDependOnAnotherModulesInternals()}. Critically it does NOT carve out
     * cross-module {@code ..api..} on the target side: a cross-module {@code api → api} edge never matches
     * because the target then resides in {@code ..api..}, not {@code ..domain..}/{@code ..infrastructure..}
     * — so {@code api → api} is permitted by construction while internals stay encapsulated. The existing
     * four rules are unchanged; the suite stays GREEN.</p>
     *
     * <p>The companion {@link #boundaryRuleActuallyBites()} canary proves this rule is not a false-GREEN:
     * a synthetic cross-module {@code application → foreign domain.model} dependency IS flagged.</p>
     */
    @Test
    void noModuleDependsOnAnotherModulesDomainOrInfrastructure() {
        // ADR-0013 §3 directs the EXPLICIT (predicate) form, adjusting the allow-list for the architecture's
        // already-sanctioned cross-module references so the suite stays GREEN while the NEW reach-arounds
        // (a module's api/application reaching into a sibling's tables) are mechanically blocked. We use a
        // positive classes().should(condition): a class passes iff it has NO dependency on a FOREIGN
        // module's encapsulated internals. ('noClasses().should(ArchCondition)' would invert the events and
        // silently pass — so the positive form is the correct primitive for a hand-rolled condition.)
        ArchRule rule = classes()
                .that().resideInAPackage("com.taarifu..")
                .and().resideOutsideOfPackage("com.taarifu.common..")
                .should(notDependOnAnotherModulesInternals())
                .because("cross-module integration is via the published api package only (ADR-0013); "
                        + "domain/infrastructure stay encapsulated (ARCHITECTURE §3.2)");
        rule.allowEmptyShould(true).check(productionClasses);
    }

    /**
     * The cross-module-internals condition: a class <b>violates</b> it if it depends on a <b>different</b>
     * module's encapsulated internals. Implemented as a hand-rolled {@link ArchCondition} (not a
     * {@code dependOnClassesThat(predicate)}) because the test must see <b>both</b> ends of each
     * {@link Dependency} to compare module segments.
     *
     * <p><b>The allow-list (what is NOT a violation — ADR-0013 §3 + ARCHITECTURE §3.2/§4.3):</b></p>
     * <ul>
     *   <li><b>cross-module {@code ..api..}</b> — the whole point of ADR-0013: a target in another
     *       module's {@code api} package is the sanctioned synchronous contract, so it never matches (the
     *       condition only inspects {@code domain}/{@code infrastructure} targets);</li>
     *   <li><b>same module</b> — a class freely uses its OWN {@code domain}/{@code infrastructure};</li>
     *   <li><b>the shared kernel {@code common}</b> — the dependency-free base every module may use
     *       (§3.2 rule 1);</li>
     *   <li><b>{@code geography.domain.model}</b> — foundation geography entities ({@code Location},
     *       {@code Constituency}) that {@code identity}/{@code institutions}/{@code reporting} legitimately
     *       <i>FK-reference</i> (ARCHITECTURE §3.2 "foundation modules may be referenced", §4.3
     *       "deliberate persistence-level reference to the geography model"); the closed boundary still
     *       holds at the application layer (callers use geography's public service, not its repositories);</li>
     *   <li><b>any module's {@code ..domain.port..}</b> — ports are plain interfaces designed for
     *       cross-module/adapter injection (the {@code SmsGateway}/{@code Geocoder} pattern, §7); the
     *       <i>implementation</i> stays in the owner's {@code infrastructure.adapter}.</li>
     * </ul>
     *
     * @return the condition; an event is added for each disallowed reach-into-foreign-internals.
     */
    private static ArchCondition<JavaClass> notDependOnAnotherModulesInternals() {
        return new ArchCondition<>("not depend on another module's domain/infrastructure (ADR-0013)") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    if (isForeignModuleInternal(originModule, dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * @param originModule the importing class's top-level module segment.
     * @param target       the depended-upon class.
     * @return {@code true} if {@code target} is an encapsulated internal-layer class of a <i>different</i>
     *         Taarifu module that the architecture does NOT sanction for cross-module reference (see the
     *         allow-list on {@link #notDependOnAnotherModulesInternals()}).
     */
    private static boolean isForeignModuleInternal(String originModule, JavaClass target) {
        String pkg = target.getPackageName();
        boolean internal = pkg.contains(".domain.") || pkg.endsWith(".domain")
                || pkg.contains(".infrastructure.") || pkg.endsWith(".infrastructure");
        if (!internal) {
            return false; // ..api.. and ..application.. targets are not the encapsulated internals.
        }
        String targetModule = moduleOf(target);
        // common kernel + unresolved segment: always allowed (§3.2 rule 1).
        if (targetModule.isEmpty() || "common".equals(targetModule)) {
            return false;
        }
        // Same module reaching into its OWN internals is legitimate.
        if (targetModule.equals(originModule)) {
            return false;
        }
        // Sanctioned cross-module references that predate / are blessed by the architecture:
        //  (a) geography foundation ENTITIES are FK-referenced (§3.2, §4.3);
        if ("geography".equals(targetModule) && pkg.endsWith(".domain.model")) {
            return false;
        }
        //  (b) any module's domain.port interfaces are designed for cross-module/adapter injection (§7).
        if (pkg.endsWith(".domain.port") || pkg.contains(".domain.port.")) {
            return false;
        }
        //  (c) a foreign module's domain.model.enums — pure VALUE-TYPE enum constants (no behaviour, no
        //      tables, no associations, no PII) that a module's PUBLISHED api port legitimately exposes as
        //      its cross-module vocabulary. The load-bearing precedent: tokens.api.TokenLedgerApi's
        //      meter/reward/topUp methods take tokens.domain.model.enums.WalletOwnerType /
        //      RewardBehaviour, so any caller of that published contract (payments → topUp) must name those
        //      enum constants. Entities/aggregates in domain.model stay encapsulated — this carve-out is
        //      scoped strictly to the leaf `domain.model.enums` package, never the wider domain.model
        //      (ADR-0013 §3: api → api is the contract; the enums it exposes ARE part of that contract).
        if (pkg.endsWith(".domain.model.enums") || pkg.contains(".domain.model.enums.")) {
            return false;
        }
        return true;
    }

    /** Extracts the top-level module segment (e.g. {@code reporting}) from a class's package, or {@code ""}. */
    private static String moduleOf(JavaClass clazz) {
        Matcher m = MODULE_SEGMENT.matcher(clazz.getPackageName() + ".");
        return m.find() ? m.group(1) : "";
    }

    /**
     * Canary for {@link #noModuleDependsOnAnotherModulesDomainOrInfrastructure()} — proves the rule is not
     * a silent false-GREEN by checking the load-bearing {@link #isForeignModuleInternal(String, JavaClass)}
     * decisions against real imported classes:
     * <ul>
     *   <li>a sibling's NON-geography {@code domain.model} from another module IS a violation
     *       (e.g. {@code reporting} → {@code identity.domain.model.Profile});</li>
     *   <li>geography's {@code domain.model} (a blessed FK target) is NOT a violation;</li>
     *   <li>another module's {@code domain.port} (cross-module injectable) is NOT a violation;</li>
     *   <li>a sibling's {@code api} package (the ADR-0013 contract) is NOT a violation;</li>
     *   <li>same-module internals and the {@code common} kernel are NOT violations.</li>
     * </ul>
     */
    @Test
    void boundaryRuleActuallyBites() {
        // A cross-module reach into a sibling's (non-geography) domain.model MUST be flagged.
        JavaClass identityProfile = productionClasses.get("com.taarifu.identity.domain.model.Profile");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("reporting", identityProfile))
                .as("reporting -> identity.domain.model.Profile is a forbidden reach-in")
                .isTrue();

        // Geography foundation entities are the blessed FK exception (ARCHITECTURE §4.3) — NOT a violation.
        JavaClass constituency = productionClasses.get("com.taarifu.geography.domain.model.Constituency");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("institutions", constituency))
                .as("institutions -> geography.domain.model.Constituency is a sanctioned FK reference")
                .isFalse();

        // Cross-module domain.port injection (the SmsGateway/Geocoder pattern) is allowed.
        JavaClass smsGateway = productionClasses.get("com.taarifu.communications.domain.port.SmsGateway");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("identity", smsGateway))
                .as("a module's domain.port is designed for cross-module injection")
                .isFalse();

        // The published api contract (ADR-0013) is never an internal reach-in.
        JavaClass tokenLedgerApi = productionClasses.get("com.taarifu.tokens.api.TokenLedgerApi");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("reporting", tokenLedgerApi))
                .as("cross-module ..api.. is the permitted contract, not an internal")
                .isFalse();

        // A foreign module's domain.model.enums is a published-contract value type (carve-out (c)):
        // tokens.api.TokenLedgerApi exposes WalletOwnerType in meter/reward/topUp, so payments naming it is
        // the contract, NOT an internal reach-in. (Entities in domain.model — asserted above — still bite.)
        JavaClass walletOwnerType =
                productionClasses.get("com.taarifu.tokens.domain.model.enums.WalletOwnerType");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("payments", walletOwnerType))
                .as("a foreign module's domain.model.enums is a published-api contract value type, not an internal")
                .isFalse();

        // Same-module internal access and the common kernel are both fine.
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("identity", identityProfile))
                .as("a module using its OWN domain.model is legitimate")
                .isFalse();
        JavaClass baseEntity = productionClasses.get("com.taarifu.common.domain.model.BaseEntity");
        org.assertj.core.api.Assertions
                .assertThat(isForeignModuleInternal("reporting", baseEntity))
                .as("the shared kernel common is always allowed")
                .isFalse();
    }
}
