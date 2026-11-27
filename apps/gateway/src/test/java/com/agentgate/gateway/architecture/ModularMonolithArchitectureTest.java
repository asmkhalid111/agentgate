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
}

