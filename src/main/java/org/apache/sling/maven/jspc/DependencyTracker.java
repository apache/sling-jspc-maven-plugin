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

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.sling.scripting.jsp.jasper.compiler.PageInfo;
import org.codehaus.plexus.util.StringUtils;

public class DependencyTracker {

    private final Log logger;
    private final Path projectDirectory;
    private final Path sourceDirectory;
    private final JspCServletContext jspCServletContext;
    private final TrackingClassLoader classLoader;
    private final List<Artifact> compileScopeArtifacts;
    private final Map<String, Set<String>> packageProviders;
    private final Set<String> unusedDependencies;
    private final Map<String, Set<String>> jspDependencies;
    private final Map<String, PageInfo> jspInfo;
    private final Path relativeSourceDirectory;


    public DependencyTracker(Log logger, Path projectDirectory, Path sourceDirectory, JspCServletContext jspCServletContext,
                             TrackingClassLoader classLoader,
                             List<Artifact> compileScopeArtifacts) {
        this.logger = logger;
        this.projectDirectory = projectDirectory;
        this.sourceDirectory = sourceDirectory;
        this.jspCServletContext = jspCServletContext;
        this.classLoader = classLoader;
        this.compileScopeArtifacts = compileScopeArtifacts;
        this.packageProviders = new ConcurrentHashMap<>();
        this.unusedDependencies = Collections.synchronizedSet(new HashSet<>());
        this.jspDependencies = new ConcurrentHashMap<>();
        this.jspInfo = new ConcurrentHashMap<>();
        this.relativeSourceDirectory = projectDirectory.relativize(sourceDirectory);
    }

    public void processCompileDependencies() {
        compileScopeArtifacts.forEach(artifact -> {
            unusedDependencies.add(artifact.getId());
            try (JarFile jar = new JarFile(artifact.getFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    String path = e.getName();
                    if (path.endsWith(".class")) {
                        path = StringUtils.chomp(path, "/");
                        if (path.charAt(0) == '/') {
                            path = path.substring(1);
                        }
                        String packageName = path.replace("/", ".");
                        Set<String> artifacts = packageProviders.computeIfAbsent(packageName, k -> new HashSet<>());
                        artifacts.add(artifact.getId());
                    }
                }
            } catch (IOException e) {
                logger.error("Error while accessing jar file " + artifact.getFile().getAbsolutePath(), e);
            }
        });
        List<String> packages = new ArrayList<>(classLoader.getPackageNames());
        Collections.sort(packages);
        for (String packageName: packages) {
            Set<String> artifacts = packageProviders.get(packageName);
            if (artifacts != null && !artifacts.isEmpty()) {
                artifacts.forEach(unusedDependencies::remove);
            }
        }
        jspInfo.forEach((jsp, pageInfo) -> {
            List dependencies = pageInfo.getDependants();
            if (!dependencies.isEmpty()) {
                Set<String> dependenciesSet =
                        jspDependencies.computeIfAbsent(Paths.get(relativeSourceDirectory.toString(), jsp).toString(), key -> new HashSet<>());
                for (Object d : dependencies) {
                    String dependency = (String) d;
                    try {
                        URL dependencyURL = jspCServletContext.getResource(dependency);
                        if (dependencyURL != null) {
                            Path dependencyPath = Paths.get(dependencyURL.getPath());
                            if (dependencyPath.startsWith(sourceDirectory)) {
                                dependenciesSet.add(projectDirectory.relativize(dependencyPath).toString());
                            } else {
                                dependenciesSet.add(dependencyURL.toExternalForm());
                            }
                        } else {
                            // the dependency comes from a JAR
                            if (dependency.startsWith("/")) {
                                dependency = dependency.substring(1);
                            }
                            JarURLConnection jarURLConnection = (JarURLConnection) classLoader.findResource(dependency).openConnection();
                            for (Artifact a : compileScopeArtifacts) {
                                if (a.getFile().getAbsolutePath().equals(jarURLConnection.getJarFile().getName())) {
                                    unusedDependencies.remove(a.getId());
                                }
                            }
                            dependenciesSet.add(Paths.get(jarURLConnection.getJarFileURL().getPath()).getFileName() + ":/" + dependency);
                        }
                    } catch (IOException e) {
                        dependenciesSet.add(dependency);
                    }
                }
            }
        });
    }

    public void collectJSPInfo(String jspFile, PageInfo pageInfo) {
        jspInfo.put(jspFile, pageInfo);
    }

    public Map<String, Set<String>> getPackageProviders() {
        return Collections.unmodifiableMap(packageProviders);
    }

    public Set<String> getUnusedDependencies() {
        return Collections.unmodifiableSet(unusedDependencies);
    }

    public Map<String, Set<String>> getJspDependencies() {
        return Collections.unmodifiableMap(jspDependencies);
    }
}
