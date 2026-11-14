package com.agentgate.gateway.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.agentgate.gateway", importOptions = {ImportOption.DoNotIncludeTests.class})
public class ModularMonolithArchitectureTest {

    @ArchTest
    public static final ArchRule packages_should_be_free_of_cycles =
            slices().matching("com.agentgate.gateway.(*)..")
                    .should().beFreeOfCycles();
}

