/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativebinaries.test.cunit.plugins;

import org.gradle.api.Incubating;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.Plugin;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.language.base.FunctionalSourceSet;
import org.gradle.language.base.ProjectSourceSet;
import org.gradle.language.c.CSourceSet;
import org.gradle.language.c.plugins.CLangPlugin;
import org.gradle.model.Mutate;
import org.gradle.model.Path;
import org.gradle.model.RuleSource;
import org.gradle.nativebinaries.ProjectNativeBinary;
import org.gradle.nativebinaries.ProjectNativeComponent;
import org.gradle.nativebinaries.internal.ProjectNativeBinaryInternal;
import org.gradle.nativebinaries.internal.resolve.NativeDependencyResolver;
import org.gradle.nativebinaries.language.internal.DefaultPreprocessingTool;
import org.gradle.nativebinaries.test.TestSuiteContainer;
import org.gradle.nativebinaries.test.cunit.CUnitTestSuite;
import org.gradle.nativebinaries.test.cunit.CUnitTestSuiteBinary;
import org.gradle.nativebinaries.test.cunit.internal.DefaultCUnitTestSuite;
import org.gradle.nativebinaries.test.cunit.internal.DefaultCUnitTestSuiteBinary;
import org.gradle.nativebinaries.test.cunit.tasks.GenerateCUnitLauncher;
import org.gradle.nativebinaries.test.plugins.NativeBinariesTestPlugin;
import org.gradle.nativebinaries.toolchain.internal.ToolChainInternal;
import org.gradle.runtime.base.BinaryContainer;
import org.gradle.runtime.base.NamedProjectComponentIdentifier;
import org.gradle.runtime.base.internal.BinaryNamingScheme;
import org.gradle.runtime.base.internal.DefaultBinaryNamingSchemeBuilder;
import org.gradle.runtime.base.internal.DefaultNamedProjectComponentIdentifier;

import java.io.File;

/**
 * A plugin that sets up the infrastructure for testing native binaries with CUnit.
 */
@Incubating
public class CUnitPlugin implements Plugin<ProjectInternal> {

    public void apply(final ProjectInternal project) {
        project.getPlugins().apply(NativeBinariesTestPlugin.class);
        project.getPlugins().apply(CLangPlugin.class);
    }

    /**
     * Model rules.
     */
    @SuppressWarnings("UnusedDeclaration")
    @RuleSource
    public static class Rules {

        private static final String CUNIT_LAUNCHER_SOURCE_SET = "cunitLauncher";

        @Mutate
        public void createCUnitTestSuitePerComponent(TestSuiteContainer testSuites, NamedDomainObjectSet<ProjectNativeComponent> components, ServiceRegistry serviceRegistry) {
            Instantiator instantiator = serviceRegistry.get(Instantiator.class);
            for (ProjectNativeComponent component : components) {
                testSuites.add(createCUnitTestSuite(component, instantiator));
            }
        }

        private CUnitTestSuite createCUnitTestSuite(final ProjectNativeComponent testedComponent, Instantiator instantiator) {
            String suiteName = String.format("%sTest", testedComponent.getName());
            String path = testedComponent.getProjectPath();
            NamedProjectComponentIdentifier id = new DefaultNamedProjectComponentIdentifier(path, suiteName);
            return instantiator.newInstance(DefaultCUnitTestSuite.class, id, testedComponent);
        }

        @Mutate
        public void configureCUnitTestSuiteSources(ProjectSourceSet projectSourceSet, TestSuiteContainer testSuites, @Path("buildDir") File buildDir) {

            for (final CUnitTestSuite suite : testSuites.withType(CUnitTestSuite.class)) {
                FunctionalSourceSet suiteSourceSet = projectSourceSet.maybeCreate(suite.getName());

                CSourceSet launcherSources = suiteSourceSet.maybeCreate(CUNIT_LAUNCHER_SOURCE_SET, CSourceSet.class);
                File baseDir = new File(buildDir, String.format("src/%s/cunitLauncher", suite.getName()));
                launcherSources.getSource().srcDir(new File(baseDir, "c"));
                launcherSources.getExportedHeaders().srcDir(new File(baseDir, "headers"));
                suite.source(launcherSources);

                CSourceSet testSources = suiteSourceSet.maybeCreate("c", CSourceSet.class);
                suite.source(testSources);
                testSources.lib(launcherSources);
            }
        }

        @Mutate
        public void createCUnitLauncherTasks(TaskContainer tasks, TestSuiteContainer testSuites, ProjectSourceSet sources) {
            for (final CUnitTestSuite suite : testSuites.withType(CUnitTestSuite.class)) {

                String taskName = suite.getName() + "CUnitLauncher";
                GenerateCUnitLauncher skeletonTask = tasks.create(taskName, GenerateCUnitLauncher.class);

                CSourceSet launcherSources = findLaucherSources(suite);
                skeletonTask.setSourceDir(launcherSources.getSource().getSrcDirs().iterator().next());
                skeletonTask.setHeaderDir(launcherSources.getExportedHeaders().getSrcDirs().iterator().next());
                launcherSources.builtBy(skeletonTask);
            }
        }

        private CSourceSet findLaucherSources(CUnitTestSuite suite) {
            return suite.getSource().withType(CSourceSet.class).matching(new Spec<CSourceSet>() {
                public boolean isSatisfiedBy(CSourceSet element) {
                    return element.getName().equals(CUNIT_LAUNCHER_SOURCE_SET);
                }
            }).iterator().next();
        }

        @Mutate
        public void createCUnitTestBinaries(final BinaryContainer binaries, TestSuiteContainer testSuites, @Path("buildDir") File buildDir, ServiceRegistry serviceRegistry) {
            for (final CUnitTestSuite cUnitTestSuite : testSuites.withType(CUnitTestSuite.class)) {
                for (ProjectNativeBinary testedBinary : cUnitTestSuite.getTestedComponent().getBinaries()) {

                    CUnitTestSuiteBinary testBinary = createTestBinary(serviceRegistry, cUnitTestSuite, testedBinary);

                    configure(testBinary, buildDir);

                    cUnitTestSuite.getBinaries().add(testBinary);
                    binaries.add(testBinary);
                }
            }
        }

        private CUnitTestSuiteBinary createTestBinary(ServiceRegistry serviceRegistry, CUnitTestSuite cUnitTestSuite, ProjectNativeBinary testedBinary) {
            BinaryNamingScheme namingScheme = new DefaultBinaryNamingSchemeBuilder(((ProjectNativeBinaryInternal) testedBinary).getNamingScheme())
                    .withComponentName(cUnitTestSuite.getBaseName())
                    .withTypeString("CUnitExe").build();

            Instantiator instantiator = serviceRegistry.get(Instantiator.class);
            NativeDependencyResolver resolver = serviceRegistry.get(NativeDependencyResolver.class);
            return instantiator.newInstance(DefaultCUnitTestSuiteBinary.class, cUnitTestSuite, testedBinary, namingScheme, resolver);
        }

        private void configure(CUnitTestSuiteBinary testBinary, File buildDir) {
            BinaryNamingScheme namingScheme = ((ProjectNativeBinaryInternal) testBinary).getNamingScheme();
            File binaryOutputDir = new File(new File(buildDir, "binaries"), namingScheme.getOutputDirectoryBase());
            String baseName = testBinary.getComponent().getBaseName();

            ToolChainInternal tc = (ToolChainInternal) testBinary.getToolChain();
            testBinary.setExecutableFile(new File(binaryOutputDir, tc.getExecutableName(baseName)));

            ((ExtensionAware) testBinary).getExtensions().create("cCompiler", DefaultPreprocessingTool.class);
        }
    }
}