package vn.edu.hcmut.tmc.userqr;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.base.DescribedPredicate.not;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards docs/ARCHITECTURE.md dependency direction and the framework-free domain.
 * Standard JDK cryptography is the only javax exception (DECISIONS B23).
 */
@AnalyzeClasses(packages = "vn.edu.hcmut.tmc.userqr", importOptions = ImportOption.DoNotIncludeTests.class)
class UserqrArchitectureTest {

    @ArchTest
    static final ArchRule layersPointInward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Presentation").definedBy("..presentation..")
            .layer("Application").definedBy("..application..")
            .layer("Domain").definedBy("..domain..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Presentation").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Presentation")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Presentation", "Application", "Infrastructure");

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "java.sql..",
                    "com.fasterxml..", "org.postgresql..", "org.sqlite..")
            .allowEmptyShould(true);

    // javax.crypto is JDK cryptography required by ADR-02, not a framework dependency (B23).
    @ArchTest
    static final ArchRule domainRejectsJavaxExceptJdkCrypto = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(resideInAnyPackage("javax..")
                    .and(not(resideInAnyPackage("javax.crypto.."))));

    @ArchTest
    static final ArchRule applicationKnowsNoJdbc = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.postgresql..");
}
