package vn.edu.hcmut.tmc.reporting;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards docs/ARCHITECTURE.md "Dependency direction" for reporting-service. These rules are what makes the DIP claim
 * checkable: breaking the direction fails {@code mvn test}.
 */
@AnalyzeClasses(packages = "vn.edu.hcmut.tmc.reporting", importOptions = ImportOption.DoNotIncludeTests.class)
class ReportingArchitectureTest {

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
                    "org.springframework..", "jakarta..", "javax..", "java.sql..",
                    "com.fasterxml..", "org.postgresql..", "org.sqlite..");

    /** The export and report use cases know their ports only: no SQL, HTTP client or file system types. */
    @ArchTest
    static final ArchRule applicationKnowsNoJdbcHttpOrFiles = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "org.springframework.jdbc..", "org.postgresql..",
                    "org.springframework.web.client..", "java.net.http..", "java.nio.file..", "java.io..");

    /** Ports are owned by the domain and implemented only at the edge. */
    @ArchTest
    static final ArchRule portsAreImplementedOnlyInInfrastructure = classes()
            .that().implement(resideInAPackage("..domain.port.."))
            .should().resideInAPackage("..infrastructure..");
}
