/*
 * This file is part of CycloneDX Gradle Plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.cyclonedx.gradle;

import org.apache.commons.io.FileUtils;
import org.apache.maven.project.MavenProject;
import org.cyclonedx.BomGenerator;
import org.cyclonedx.BomParser;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.License;
import org.cyclonedx.util.BomUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedModuleVersion;
import org.gradle.api.tasks.TaskAction;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CycloneDxTask extends DefaultTask {

    /**
     * Various messages sent to console.
     */
    private static final String MESSAGE_RESOLVING_DEPS = "CycloneDX: Resolving Dependencies";
    private static final String MESSAGE_CREATING_BOM = "CycloneDX: Creating BOM";
    private static final String MESSAGE_CALCULATING_HASHES = "CycloneDX: Calculating Hashes";
    private static final String MESSAGE_WRITING_BOM = "CycloneDX: Writing BOM";
    private static final String MESSAGE_VALIDATING_BOM = "CycloneDX: Validating BOM";
    private static final String MESSAGE_VALIDATION_FAILURE = "The BOM does not conform to the CycloneDX BOM standard as defined by the XSD";

    private File buildDir;

    public void setBuildDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @TaskAction
    public void createBom() {

        Set<String> builtDependencies = getProject()
            .getRootProject()
            .getSubprojects()
            .stream()
            .map(p -> p.getGroup() + ":" + p.getName() + ":" + p.getVersion())
            .collect(Collectors.toSet());

        Set<Component> components = new LinkedHashSet<>();
        for (Project p : getProject().getAllprojects()) {
            for (Configuration configuration : p.getConfigurations()) {
                if (!shouldSkipConfiguration(configuration)) {
                    ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
                    if (resolvedConfiguration != null) {
                        for (ResolvedArtifact artifact : resolvedConfiguration.getResolvedArtifacts()) {
                            getLogger().debug("RESOLVED ARTIFACT: " + artifact.getName());
                            getLogger().debug("TYPE: " + artifact.getType());
                            getLogger().debug("CLASSIFIER: " + artifact.getClassifier());
                            getLogger().debug("GROUP: " + artifact.getModuleVersion().getId().getGroup());

                            // Don't include other resources built from this Gradle project.
                            String dependencyName = getDependencyName(artifact);
                            if(builtDependencies.stream().anyMatch(c -> c.equals(dependencyName))) {
                                continue;
                            }

                            // Convert into a Component and augment with pom metadata if available.
                            Component component = convertArtifact(artifact);
                            augmentComponentMetadata(component, dependencyName);
                            components.add(component);
                        }
                    }
                }
            }
        }
        writeBom(components);
    }

    public String getDependencyName(ResolvedArtifact artifact) {
        ModuleVersionIdentifier m = artifact.getModuleVersion().getId();
        return m.getGroup() + ":" + m.getName() + ":" + m.getVersion();
    }

    public void augmentComponentMetadata(Component component, String dependencyName) {
        Dependency pomDep = getProject()
            .getDependencies()
            .create(dependencyName + "@pom");
        Configuration pomCfg = getProject()
            .getConfigurations()
            .detachedConfiguration(pomDep);
        MavenProject project = null;

        try {
            File pomFile = pomCfg.resolve().stream().findFirst().orElse(null);
            project = MavenUtils.readPom(pomFile);
        } catch(IOException err) {
            getLogger().error("Unable to resolve POM for " + component + ": " + err);
        } catch(ResolveException err) {
            getLogger().error("Unable to resolve POM for " + component + ": " + err);
        }

        if(project != null) {
            if(project.getOrganization() != null) {
                component.setPublisher(project.getOrganization().getName());
            }
            component.setDescription(project.getDescription());
            if(project.getLicenses() != null) {
                final List<License> licenses = new ArrayList<>();
                for(org.apache.maven.model.License artifactLicense : project.getLicenses()) {
                    License license = new License();
                    if(artifactLicense.getName() != null) {
                        license.setName(artifactLicense.getName());
                        licenses.add(license);
                    } else if(artifactLicense.getUrl() != null) {
                        license.setName(artifactLicense.getUrl());
                        licenses.add(license);
                    }
                }
                if(licenses.size() > 0) {
                    component.setLicenses(licenses);
                }
            }
        }
    }

    public Component convertArtifact(ResolvedArtifact artifact) {
        final Component component = new Component();
        component.setGroup(artifact.getModuleVersion().getId().getGroup());
        component.setName(artifact.getModuleVersion().getId().getName());
        component.setVersion(artifact.getModuleVersion().getId().getVersion());
        component.setType("library");
        try {
            getLogger().debug(MESSAGE_CALCULATING_HASHES);
            component.setHashes(BomUtils.calculateHashes(artifact.getFile()));
        } catch(IOException e) {
            getLogger().error("Error encountered calculating hashes", e);
        }

        return component;
    }


    private boolean shouldSkipConfiguration(Configuration configuration) {
        final List<String> skipConfigs = Arrays.asList(
                "apiElements",
                "implementation",
                "runtimeElements",
                "runtimeOnly",
                "testImplementation",
                "testRuntimeOnly");
        return skipConfigs.contains(configuration.getName());
    }

    /**
     * Ported from Maven plugin.
     */
    protected void writeBom(Set<Component> components) throws GradleException{
        try {
            getLogger().info(MESSAGE_CREATING_BOM);
            final BomGenerator bomGenerator = new BomGenerator(components);
            bomGenerator.generate();
            final String bomString = bomGenerator.toXmlString();
            final File bomFile = new File(buildDir, "reports/bom.xml");
            getLogger().info(MESSAGE_WRITING_BOM);
            FileUtils.write(bomFile, bomString, Charset.forName("UTF-8"), false);
            getLogger().info(MESSAGE_VALIDATING_BOM);
            final BomParser bomParser = new BomParser();
            if (!bomParser.isValid(bomFile)) {
                throw new GradleException(MESSAGE_VALIDATION_FAILURE);
            }

        } catch (ParserConfigurationException | TransformerException | IOException e) {
            throw new GradleException("An error occurred executing " + this.getClass().getName(), e);
        }
    }
}
