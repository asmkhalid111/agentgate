package com.agentgate.gateway.architecture;

import com.agentgate.gateway.pipeline.ToolAdapter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.agentgate.gateway", importOptions = {ImportOption.DoNotIncludeTests.class})
public class ModularMonolithArchitectureTest {

    @ArchTest
    public static final ArchRule packages_should_be_free_of_cycles =
            slices().matching("com.agentgate.gateway.(*)..")
                    .should().beFreeOfCycles();

    @ArchTest
    public static final ArchRule downstream_adapters_should_only_be_used_by_pipeline =
            noClasses().that().resideOutsideOfPackage("..pipeline..")
                    .should().dependOnClassesThat().areAssignableTo(ToolAdapter.class)
                    .because("All downstream REST and MCP tool execution must pass through ToolExecutionPipeline (C4 Gateway components / Proxy & Pipeline patterns)");

    @ArchTest
    public static final ArchRule controllers_should_not_bypass_to_pipeline =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..pipeline..")
                    .because("Admin and approval controllers must not invoke or bypass the governed execution pipeline");

    @ArchTest
    public static final ArchRule controllers_should_not_embed_jdbc_clients =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..",
                            "javax.sql.."
                    )
                    .because("Controllers must use Repository abstractions rather than embedding database queries (PATTERN_MAP Repository pattern)");

    @ArchTest
    public static final ArchRule repositories_should_only_depend_on_domain_and_dto =
            noClasses().that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..",
                            "..service..",
                            "..pipeline..",
                            "..mcp..",
                            "..guard..",
                            "..ratelimit..",
                            "..resilience.."
                    )
                    .because("Persistence repositories must remain isolated from web, ingress, and orchestration layers");

    @ArchTest
    public static final ArchRule guards_should_not_depend_on_controllers_repositories_or_pipeline =
            noClasses().that().resideInAPackage("..guard..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..",
                            "..repository..",
                            "..pipeline..",
                            "..mcp.."
                    )
                    .because("Guards are pluggable execution-pipeline components and must not depend on web, persistence, or transport layers");
}

