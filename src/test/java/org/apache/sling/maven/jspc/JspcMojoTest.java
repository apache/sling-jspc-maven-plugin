/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.maven.jspc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.UserLocalArtifactRepository;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


public class JspcMojoTest {

    private static final String INCLUDES_PROJECT = "src/test/resources/jspc-maven-plugin-it-includes";
    private static final String REPOSITORY = "src/test/resources/jspc-maven-plugin-it-includes/repo";

    @Rule
    public MojoRule mojoRule = new MojoRule();

    private MavenProject mavenProject;
    private JspcMojo jspcMojo;
    private File baseDir;

    @Before
    public void before() throws Exception {
        baseDir = new File(INCLUDES_PROJECT);
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setBaseDirectory(baseDir);
        ProjectBuildingRequest configuration = request.getProjectBuildingRequest();
        configuration.setResolveDependencies(true);
        configuration.setLocalRepository(new DefaultArtifactRepository("project", "file://" + new File(REPOSITORY).getAbsolutePath(),
                new DefaultRepositoryLayout()));
        mavenProject = mojoRule.lookup(ProjectBuilder.class).build(new File(baseDir, "pom.xml"), configuration).getProject();
        Assert.assertNotNull(mavenProject);
        MavenSession session = mojoRule.newMavenSession(mavenProject);
        MojoExecution execution = mojoRule.newMojoExecution("jspc");
        jspcMojo = (JspcMojo) mojoRule.lookupConfiguredMojo(session, execution);
    }

    @After
    public void after() {
        FileUtils.deleteQuietly(new File(baseDir, "target"));
    }

    @Test
    public void testIncludesFromClassPath() throws Exception {
        jspcMojo.execute();
        File generatedMain = new File(mavenProject.getBuild().getOutputDirectory() + File.separator + "main__002e__jsp.java");
        assertTrue("Expected to find a generated main__002e__jsp.java file.", generatedMain.exists());
        FileReader fileReader = new FileReader(generatedMain);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        Set<String> expectedContent = new HashSet<>(Arrays.asList("included-fs.jsp", "included-cp.jsp", "/libs/l1/l2/included-cp.jsp"));
        String line = bufferedReader.readLine();
        while (line != null) {
            expectedContent.removeIf(line::contains);
            line = bufferedReader.readLine();
        }
        assertTrue("Some files were not correctly included: " + expectedContent.toString(), expectedContent.isEmpty());
    }

    @Test
    public void testCompilationReport() throws Exception {
        jspcMojo.execute();
        Path compilationReportPath = Paths.get(mavenProject.getBuild().getOutputDirectory(), "compilation_report.json");
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(compilationReportPath))) {
            JsonObject compilationReport = reader.readObject();

            JsonArray unusedDependencies = compilationReport.getJsonArray("unusedDependencies");
            assertNotNull(unusedDependencies);
            assertTrue(unusedDependencies.isEmpty());

            JsonArray jspDependencies = compilationReport.getJsonArray("jspDependencies");
            assertNotNull(jspDependencies);
            assertEquals(1, jspDependencies.size());
            JsonObject mainJsp = jspDependencies.getJsonObject(0);
            assertEquals("src/main/scripts/main.jsp", mainJsp.getString("jsp"));
            JsonArray mainJspDependencies = mainJsp.getJsonArray("dependencies");
            assertNotNull(mainJspDependencies);
            assertEquals(3, mainJspDependencies.size());

            assertEquals(new HashSet<>(Arrays.asList("jspc-maven-plugin-it-includes-deps-0.0.1.jar:/libs/l1/l2/included-cp.jsp", "jspc" +
                    "-maven-plugin-it-includes-deps-0.0.1.jar:/included-cp.jsp", "src/main/scripts/included-fs.jsp")),
                    new HashSet<>(mainJspDependencies.getValuesAs(JsonString.class).stream().map(JsonString::getString).collect(
                            Collectors.toList())));

            JsonArray packageProviders = compilationReport.getJsonArray("packageProviders");
            assertNotNull(packageProviders);
            assertEquals(1, packageProviders.size());

            JsonObject provider = packageProviders.getJsonObject(0);
            assertEquals("org.apache.sling.maven.jspc.it", provider.getString("package"));
            JsonArray providers = provider.getJsonArray("providers");
            assertNotNull(providers);
            assertEquals(1, providers.size());
            assertEquals("org.apache.sling:jspc-maven-plugin-it-deps:jar:0.0.1", providers.getString(0));
        }
    }

}
