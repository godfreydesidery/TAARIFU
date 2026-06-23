package com.taarifu.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

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
 * dependency direction, thin controllers, ports free of vendor types). As feature modules are added,
 * cross-module {@code domain}/{@code infrastructure} import rules will be tightened here.</p>
 */
class ModuleBoundaryTest {

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
                        "com.taarifu.admin..")
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
}
