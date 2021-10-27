/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.maven.jspc;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;

import org.apache.commons.logging.impl.LogFactoryImpl;
import org.apache.commons.logging.impl.SimpleLog;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.sling.commons.classloader.ClassLoaderWriter;
import org.apache.sling.commons.compiler.JavaCompiler;
import org.apache.sling.commons.compiler.impl.EclipseJavaCompiler;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.scripting.jsp.jasper.Constants;
import org.apache.sling.scripting.jsp.jasper.JasperException;
import org.apache.sling.scripting.jsp.jasper.JspCompilationContext;
import org.apache.sling.scripting.jsp.jasper.Options;
import org.apache.sling.scripting.jsp.jasper.compiler.JspConfig;
import org.apache.sling.scripting.jsp.jasper.compiler.JspRuntimeContext;
import org.apache.sling.scripting.jsp.jasper.compiler.PageInfo;
import org.apache.sling.scripting.jsp.jasper.compiler.TagPluginManager;
import org.apache.sling.scripting.jsp.jasper.compiler.TldLocationsCache;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.StringUtils;
import org.apache.maven.artifact.resolver.ArtifactResolver;

/**
 * The <code>JspcMojo</code> is implements the Sling Maven JspC goal
 * <code>jspc</code> compiling JSP into the target and creating a component
 * descriptor for Declarative Services to use the JSP with the help of the
 * appropriate adapter as component.
 */
@Mojo(name = "jspc", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class JspcMojo extends AbstractMojo implements Options {

    /**
     * The Maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true )
    private MavenProject project;

    /**
     * Location of the JSP source file. 
     */
    @Parameter( property = "jspc.sourceDirectory", defaultValue = "${project.build.scriptSourceDirectory}")
    private File sourceDirectory;

    /**
     * List of alternative resource directories used during compiling.
     */
    @Parameter
    private File[] resourceDirectories = new File[0];

    /**
     * Target directory for the compiled JSP classes.
     */
    @Parameter ( property = "jspc.outputDirectory", defaultValue = "${project.build.outputDirectory}")
    private String outputDirectory;

    @Parameter ( property = "jspc.jasper.classdebuginfo", defaultValue = "true")
    private boolean jasperClassDebugInfo;

    @Parameter ( property = "jspc.jasper.enablePooling", defaultValue = "true")
    private boolean jasperEnablePooling;

    @Parameter ( property = "jspc.jasper.ieClassId", defaultValue = "clsid:8AD9C840-044E-11D1-B3E9-00805F499D93")
    private String jasperIeClassId;

    @Parameter ( property = "jspc.jasper.genStringAsCharArray", defaultValue = "false")
    private boolean jasperGenStringAsCharArray;

    @Parameter ( property = "jspc.jasper.keepgenerated", defaultValue = "true")
    private boolean jasperKeepGenerated;

    @Parameter ( property = "jspc.jasper.mappedfile", defaultValue = "true")
    private boolean jasperMappedFile;

    @Parameter ( property = "jspc.jasper.trimSpaces", defaultValue = "false")
    private boolean jasperTrimSpaces;

    @Parameter ( property = "jspc.jasper.suppressSmap", defaultValue = "false")
    private boolean jasperSuppressSmap;

    @Parameter ( property = "jspc.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Parameter ( property = "jspc.showSuccess", defaultValue = "false")
    private boolean showSuccess;

    /**
     * The Target Virtual Machine Version to generate class files for.
     */
    @Parameter ( property = "jspc.compilerTargetVM", defaultValue = "1.8")
    private String compilerTargetVM;

    /**
     * The Compiler Source Version of the Java source generated from the JSP files before compiling into classes.
     */
    @Parameter ( property = "jspc.compilerSourceVM", defaultValue = "1.8")
    private String compilerSourceVM;

    /**
     * Prints a compilation report by listing all the packages and dependencies that were used during processing the JSPs.
     */
    @Parameter ( property = "jspc.printCompilationReport", defaultValue = "false")
    private boolean printCompilationReport;

    /**
     * Generates a compilation report text file (compilation_report.json) in the {@code ${project.build.outputDirectory}}.
     *
     * <p>
     *     The compilation report has the following format:
     *     <pre>
     *     {
     *         "packageProviders": [
     *             {
     *                 "package": "&lt;a Java package name&gt;",
     *                 "providers": [
     *                     "&lt;Maven Artifact ID&gt;"
     *                 ]
     *             }
     *         ],
     *         "jspDependencies": [
     *             {
     *                 "jsp": "src/main/scripts/&lt;a script&gt;.jsp",
     *                 "dependencies": [
     *                     "&lt;jar file name&gt;:&lt;path to JSP&gt;",
     *                     "src/main/scripts/&lt;path to file in local project&gt;.jsp"
     *                 ]
     *             }
     *         ],
     *         "unusedDependencies": [
     *             "&lt;Maven Artifact ID&gt;"
     *         ]
     *      }
     *     </pre>
     * </p>
     *
     * @since 2.3.0
     */
    @Parameter(property = "jspc.generateCompilationReport", defaultValue = "false")
    private boolean generateCompilationReport;

    private static final String COMPILATION_REPORT = "compilation_report.json";

    /**
     * Comma separated list of extensions of files to be compiled by the plugin.
     * @deprecated Use the {@link #includes} filter instead.
     */
    @Deprecated
    @Parameter ( property = "jspc.jspFileExtensions", defaultValue = "jsp,jspx")
    private String jspFileExtensions;

    /**
     * When defined, this will set the value for the {@link org.apache.sling.scripting.jsp.jasper.Constants#JSP_PACKAGE_NAME_PROPERTY_NAME}
     * system property of the Jasper compiler, which defines the prefix package under which compiled JSPs will be generated.
     */
    @Parameter(property = "jspc.servletPackage")
    private String servletPackage;

    /**
     * Included JSPs, defaults to <code>"**&#47;*.jsp"</code>
     */
    @Parameter
    private String[] includes;

    /**
     * Excluded JSPs, empty by default
     */
    @Parameter
    private String[] excludes;

    @Parameter
    private FeatureDependency feature;

    @Parameter(property = "session", defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession mavenSession;

    @Component
    protected MavenProjectHelper projectHelper;

    @Component
    ArtifactHandlerManager artifactHandlerManager;

    @Component
    ArtifactResolver artifactResolver;

    private String uriSourceRoot;

    private List<String> pages = new ArrayList<String>();

    private JspCServletContext context;

    private JspRuntimeContext rctxt;

    private TrackingClassLoader loader;

    private List<Artifact> jspcCompileArtifacts;

    /**
     * Cache for the TLD locations
     */
    private TldLocationsCache tldLocationsCache;

    private FeatureSupport featureSupport;

    private JspConfig jspConfig;

    private TagPluginManager tagPluginManager;

    private JspCIOProvider ioProvider;

    private DependencyTracker dependencyTracker;

    public static final class FeatureDependency {
        public Artifact featureId;
        public String featureFile;
        public List<Dependency> dependencies;
        public boolean failOnUnresolved;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException {

        try {
            uriSourceRoot = sourceDirectory.getCanonicalPath();
        } catch (Exception e) {
            uriSourceRoot = sourceDirectory.getAbsolutePath();
        }

        // ensure output directory
        File outputDirectoryFile = new File(outputDirectory);
        if (!outputDirectoryFile.isDirectory()) {
            if (outputDirectoryFile.exists()) {
                throw new MojoExecutionException(outputDirectory
                    + " exists but is not a directory");
            }

            if (!outputDirectoryFile.mkdirs()) {
                throw new MojoExecutionException(
                    "Cannot create output directory " + outputDirectory);
            }
        }

        // have the files compiled
        String previousJasperPackageName = null;
        String oldValue = System.getProperty(LogFactoryImpl.LOG_PROPERTY);
        try {
            // ensure the JSP Compiler does not try to use Log4J
            System.setProperty(LogFactoryImpl.LOG_PROPERTY, SimpleLog.class.getName());
            previousJasperPackageName =
                    System.setProperty(Constants.JSP_PACKAGE_NAME_PROPERTY_NAME, (servletPackage != null ? servletPackage : ""));
            executeInternal();
            if (dependencyTracker != null) {
                if (printCompilationReport) {
                    printCompilationReport(dependencyTracker);
                }
                if (generateCompilationReport) {
                    generateCompilationReport(dependencyTracker);
                }
            }
        } catch (JasperException je) {
            getLog().error("Compilation Failure", je);
            throw new MojoExecutionException(je.getMessage(), je);
        } finally {
            if (oldValue == null) {
                System.clearProperty(LogFactoryImpl.LOG_PROPERTY);
            } else {
                System.setProperty(LogFactoryImpl.LOG_PROPERTY, oldValue);
            }
            if (previousJasperPackageName == null) {
                System.clearProperty(Constants.JSP_PACKAGE_NAME_PROPERTY_NAME);
            } else {
                System.setProperty(Constants.JSP_PACKAGE_NAME_PROPERTY_NAME, previousJasperPackageName);
            }
            if (featureSupport != null) {
                try {
                    featureSupport.shutdown(10000);
                } catch (Exception e) {
                    getLog().error(e);
                }

                featureSupport = null;
            }
        }

        project.addCompileSourceRoot(outputDirectory);
    }

    /**
     * Locate all jsp files in the webapp. Used if no explicit jsps are
     * specified.
     */
    private void scanFiles(File base) {

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(base);
        scanner.setIncludes(includes);
        scanner.setExcludes(excludes);
        scanner.scan();

        Collections.addAll(pages, scanner.getIncludedFiles());
    }

    /**
     * Executes the compilation.
     *
     * @throws JasperException If an error occurs
     */
    private void executeInternal() throws JasperException {
        if (getLog().isDebugEnabled()) {
            getLog().debug("execute() starting for " + pages.size() + " pages.");
        }

        try {
            if (featureSupport == null && feature != null) {
                File target = null;
                if (feature.featureId != null) {
                    target = getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, ArtifactId.fromMvnId(feature.featureId.getId())).getFile();
                } else if (feature.featureFile != null && ! feature.featureFile.trim().isEmpty()){
                    target = new File(feature.featureFile);
                }
                if (target != null && target.isFile()) {
                    try (Reader reader = new InputStreamReader(Files.newInputStream(target.toPath()), "UTF-8")) {
                        Feature assembled = FeatureJSONReader.read(reader, target.getAbsolutePath());
                        featureSupport = FeatureSupport.createFeatureSupport(assembled, artifactId -> {
                            try {
                                return getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, artifactId).getFile().toURI().toURL();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, this.getClass().getClassLoader(), feature.failOnUnresolved, properties -> {
                            properties.put(org.osgi.framework.Constants.FRAMEWORK_STORAGE_CLEAN, org.osgi.framework.Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
                            properties.put(org.osgi.framework.Constants.FRAMEWORK_STORAGE, new File(project.getBuild().getDirectory(), "featuresupport").getAbsolutePath());
                            properties.put(org.osgi.framework.Constants.FRAMEWORK_BOOTDELEGATION, "*");
                            return properties;
                        });
                    }
                }
            }
            if (context == null) {
                initServletContext();
            }

            if (printCompilationReport || generateCompilationReport) {
                dependencyTracker = new DependencyTracker(
                        getLog(),
                        project.getBasedir().toPath(),
                        sourceDirectory.toPath(),
                        context,
                        loader,
                        jspcCompileArtifacts);
            }

            if (includes == null) {
                includes = new String[]{ "**/*.jsp" };
            }

            // No explicit pages, we'll process all .jsp in the webapp
            if (pages.size() == 0) {
                scanFiles(sourceDirectory);
            }

            File uriRootF = new File(uriSourceRoot);
            if (!uriRootF.exists() || !uriRootF.isDirectory()) {
                throw new JasperException("The source location '"
                    + uriSourceRoot + "' must be an existing directory");
            }

            pages.stream().parallel().forEach(nextjsp -> {
                File fjsp = new File(nextjsp);
                if (!fjsp.isAbsolute()) {
                    fjsp = new File(uriRootF, nextjsp);
                }
                if (!fjsp.exists()) {
                    if (getLog().isWarnEnabled()) {
                        getLog().warn("JSP file " + fjsp + " does not exist");
                    }
                } else {
                    String s = fjsp.getAbsolutePath();
                    if (s.startsWith(uriSourceRoot)) {
                        nextjsp = s.substring(uriSourceRoot.length());
                    }
                    if (nextjsp.startsWith("." + File.separatorChar)) {
                        nextjsp = nextjsp.substring(2);
                    }

                    try {
                        processFile(nextjsp);
                    } catch (JasperException e) {
                        Throwable rootCause = e;
                        while (rootCause instanceof JasperException
                                && ((JasperException) rootCause).getRootCause() != null) {
                            rootCause = ((JasperException) rootCause).getRootCause();
                        }
                        if (rootCause != e) {
                            rootCause.printStackTrace();
                        }
                        throw new RuntimeException(e);
                    }
                }
            });
            if (dependencyTracker != null) {
                dependencyTracker.processCompileDependencies();
            }
        } catch (/* IO */Exception ioe) {
            throw new JasperException(ioe);
        }
    }

    private void processFile(final String file) throws JasperException {
        try {
            final String jspUri = file.replace('\\', '/');
            final JspCompilationContext clctxt = new JspCompilationContext(jspUri, false, this, context, rctxt, false);

            JasperException error = clctxt.compile(true);
            PageInfo pageInfo = clctxt.getCompiler().getPageInfo();
            if (pageInfo != null && dependencyTracker != null) {
                dependencyTracker.collectJSPInfo(file, pageInfo);
            }
            if (error != null) {
                throw error;
            }
            if (showSuccess) {
                getLog().info("Built File: " + file);
            }

        } catch (final JasperException je) {
            Throwable rootCause = je;
            while (rootCause instanceof JasperException
                && ((JasperException) rootCause).getRootCause() != null) {
                rootCause = ((JasperException) rootCause).getRootCause();
            }
            if (rootCause != je) {
                getLog().error("General problem compiling " + file, rootCause);
            }

            // Bugzilla 35114.
            if (failOnError) {
                throw je;
            }

            // just log otherwise
            getLog().error(je.getMessage());

        } catch (Throwable e) {
            if (failOnError) {
                throw new JasperException(e);
            }
            getLog().error(e.getMessage(), e);
        }
    }

    // ---------- Additional Settings ------------------------------------------

    private void initServletContext() throws IOException, DependencyResolutionRequiredException {
        if (loader == null) {
            initClassLoader();
        }



        tldLocationsCache = featureSupport != null ? featureSupport.getTldLocationsCache() : new JspCTldLocationsCache(true, loader);

        context = new JspCServletContext(getLog(), new URL("file:" + uriSourceRoot.replace('\\', '/') + '/'), tldLocationsCache);
        for (File resourceDir: resourceDirectories) {
            String root = resourceDir.getCanonicalPath().replace('\\', '/');
            URL altUrl = new URL("file:" + root + "/");
            context.addAlternativeBaseURL(altUrl);
        }


        if (tldLocationsCache instanceof JspCTldLocationsCache) {
            ((JspCTldLocationsCache) tldLocationsCache).init(context);
        }

        JavaCompiler compiler = new EclipseJavaCompiler();
        ClassLoaderWriter writer = new JspCClassLoaderWriter(loader, new File(outputDirectory));
        ioProvider = new JspCIOProvider(loader, compiler, writer);
        rctxt = new JspRuntimeContext(context, this, ioProvider);
        jspConfig = new JspConfig(context);
        tagPluginManager = new TagPluginManager(context);
    }


    /**
     * Initializes the classloader as/if needed for the given compilation
     * context.
     *
     * @throws IOException If an error occurs
     */
    private void initClassLoader() throws IOException,
            DependencyResolutionRequiredException {
        List<URL> classPath = new ArrayList<URL>();
        // add output directory to classpath
        final String targetDirectory = project.getBuild().getOutputDirectory();
        classPath.add(new File(targetDirectory).toURI().toURL());


        jspcCompileArtifacts = new ArrayList<>();

        if (featureSupport != null) {
            if (feature.dependencies != null) {
                for (Dependency dependency : feature.dependencies) {
                    Artifact artifact = getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, new ArtifactId(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getType()));
                    classPath.add(artifact.getFile().toURI().toURL());
                    jspcCompileArtifacts.add(artifact);
                }
            }
            featureSupport.getFeature().getBundles().stream()
                    .map(bundle -> bundle.getId())
                    .map(dependency -> getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, new ArtifactId(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getType())))
                    .forEachOrdered(jspcCompileArtifacts::add);

            loader = new TrackingClassLoader(classPath.toArray(new URL[classPath.size()]), featureSupport.getClassLoader());
        } else {
            // add artifacts from project
            Set<Artifact> artifacts = project.getArtifacts();
            for (Artifact a: artifacts) {
                final String scope = a.getScope();
                if ("provided".equals(scope) || "runtime".equals(scope) || "compile".equals(scope)) {
                    // we need to exclude the javax.servlet.jsp API, otherwise the taglib parser causes problems (see note below)
                    if (containsProblematicPackage(a.getFile())) {
                        continue;
                    }
                    classPath.add(a.getFile().toURI().toURL());
                    jspcCompileArtifacts.add(a);
                }
            }

            if (getLog().isDebugEnabled()) {
                getLog().debug("Compiler classpath:");
                for (URL u: classPath) {
                    getLog().debug("  " + u);
                }
            }
            // this is dangerous to use this classloader as parent as the compilation will depend on the classes provided
            // in the plugin dependencies. but if we omit this, we get errors by not being able to load the TagExtraInfo classes.
            // this is because this plugin uses classes from the javax.servlet.jsp that are also loaded via the TLDs.
            loader = new TrackingClassLoader(classPath.toArray(new URL[classPath.size()]), this.getClass().getClassLoader());
        }
    }

    /**
     * Checks if the given jar file contains a problematic java API that should be excluded from the classloader.
     * @param file the file to check
     * @return {@code true} if it contains a problematic package
     * @throws IOException if an error occurrs.
     */
    private boolean containsProblematicPackage(File file) throws IOException {
        JarFile jar = new JarFile(file);
        boolean isJSPApi = jar.getEntry("/javax/servlet/jsp/JspPage.class") != null;
        jar.close();
        return isJSPApi;
    }

    /**
     * Prints the dependency report.
     */
    private void printCompilationReport(DependencyTracker dependencyTracker) {
        StringBuilder report = new StringBuilder("JSP compilation report:\n\n");
        int pad = 10;
        Map<String, Set<String>> packageProviders = dependencyTracker.getPackageProviders();
        List<String> packages = new ArrayList<>(packageProviders.keySet());
        for (String packageName: packages) {
            pad = Math.max(pad, packageName.length());
        }
        pad += 2;
        report.append(StringUtils.rightPad("Package", pad)).append("Dependency");
        report.append("\n---------------------------------------------------------------\n");

        Collections.sort(packages);
        for (String packageName: packages) {
            report.append(StringUtils.rightPad(packageName, pad));
            Set<String> artifacts = packageProviders.get(packageName);
            if (artifacts == null || artifacts.isEmpty()) {
                report.append("n/a");
            } else {
                StringBuilder ids = new StringBuilder();
                for (String id: artifacts) {
                    if (ids.length() > 0) {
                        ids.append(", ");
                    }
                    ids.append(id);
                }
                report.append(ids);
            }
            report.append("\n");
        }

        // print the unused dependencies
        report.append("\n");
        Set<String> unusedDependencies = dependencyTracker.getUnusedDependencies();
        report.append(unusedDependencies.size()).append(" dependencies not used by JSPs:\n");
        if (!unusedDependencies.isEmpty()) {
            report.append("---------------------------------------------------------------\n");
            for (String id: unusedDependencies) {
                report.append(id).append("\n");
            }
        }

        // create the package list that are double defined
        int doubleDefined = 0;
        StringBuilder msg = new StringBuilder();
        for (String packageName: packages) {
            Set<String> a = packageProviders.get(packageName);
            if (a != null && a.size() > 1) {
                doubleDefined++;
                msg.append(StringUtils.rightPad(packageName, pad));
                msg.append(StringUtils.join(a.iterator(), ", ")).append("\n");
            }
        }
        report.append("\n");
        report.append(doubleDefined).append(" packages are defined by multiple dependencies:\n");
        if (doubleDefined > 0) {
            report.append("---------------------------------------------------------------\n");
            report.append(msg);
        }

        Map<String, Set<String>> jspDependencies = dependencyTracker.getJspDependencies();
        if (!jspDependencies.isEmpty()) {
            pad = 10;
            List<String> jspsWithDependencies = new ArrayList<>(jspDependencies.keySet());
            for (String jsp : jspsWithDependencies) {
                pad = Math.max(pad, jsp.length());
            }
            pad += 2;
            report.append("\n");
            report.append(StringUtils.rightPad("JSP", pad)).append("Dependencies");
            report.append("\n---------------------------------------------------------------\n");
            Collections.sort(jspsWithDependencies);
            for (String jsp : jspsWithDependencies) {
                report.append(StringUtils.rightPad(jsp, pad));
                report.append(String.join(", ", jspDependencies.get(jsp)));
            }

        }
        getLog().info(report);
    }

    private void generateCompilationReport(DependencyTracker dependencyTracker) {
        JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
        JsonArrayBuilder providers = Json.createArrayBuilder();
        JsonArrayBuilder jsps = Json.createArrayBuilder();
        dependencyTracker.getPackageProviders().forEach(
                (pkg, providersForPackage) -> {
                    JsonArrayBuilder providersForPackageBuilder = Json.createArrayBuilder(providersForPackage);
                    JsonObject provider = Json.createObjectBuilder()
                            .add("package", pkg)
                            .add("providers", providersForPackageBuilder)
                            .build();
                    providers.add(provider);
                }
        );
        dependencyTracker.getJspDependencies().forEach(
                (jsp, dependencies) -> {
                    JsonArrayBuilder dependenciesBuilder = Json.createArrayBuilder(dependencies);
                    JsonObject jspDependency = Json.createObjectBuilder()
                            .add("jsp", jsp)
                            .add("dependencies", dependenciesBuilder)
                            .build();
                    jsps.add(jspDependency);
                }
        );
        JsonArrayBuilder unusedDependencies = Json.createArrayBuilder(dependencyTracker.getUnusedDependencies());
        Path compilationReportPath = Paths.get(project.getBuild().getOutputDirectory(), COMPILATION_REPORT);
        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
        try (JsonWriter jsonWriter = writerFactory.createWriter(Files.newBufferedWriter(compilationReportPath, StandardCharsets.UTF_8))) {
            jsonWriter.writeObject(
                    jsonObjectBuilder
                            .add("packageProviders", providers.build())
                            .add("jspDependencies", jsps.build())
                            .add("unusedDependencies", unusedDependencies.build())
                            .build()
            );
        } catch (IOException e) {
            getLog().error("Cannot generate the " + COMPILATION_REPORT + " file.", e);
        }
    }

    // ---------- Options interface --------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#genStringAsCharArray()
     */
    public boolean genStringAsCharArray() {
        return jasperGenStringAsCharArray;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getClassDebugInfo()
     */
    public boolean getClassDebugInfo() {
        return jasperClassDebugInfo;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getCompiler()
     */
    public String getCompiler() {
        // use JDTCompiler, which is the default
        return null;
    }

    public String getCompilerClassName() {
        // use JDTCompiler, which is the default
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getCompilerSourceVM()
     */
    public String getCompilerSourceVM() {
        return compilerSourceVM;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getCompilerTargetVM()
     */
    public String getCompilerTargetVM() {
        return compilerTargetVM;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getErrorOnUseBeanInvalidClassAttribute()
     */
    public boolean getErrorOnUseBeanInvalidClassAttribute() {
        // not configurable
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getFork()
     */
    public boolean getFork() {
        // certainly don't fork (not required anyway)
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getIeClassId()
     */
    public String getIeClassId() {
        return jasperIeClassId;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getJavaEncoding()
     */
    public String getJavaEncoding() {
        return "UTF-8";
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getJspConfig()
     */
    public JspConfig getJspConfig() {
        return jspConfig;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getKeepGenerated()
     */
    public boolean getKeepGenerated() {
        return jasperKeepGenerated;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getMappedFile()
     */
    public boolean getMappedFile() {
        return jasperMappedFile;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getScratchDir()
     */
    public String getScratchDir() {
        return outputDirectory;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getSendErrorToClient()
     */
    public boolean getSendErrorToClient() {
        // certainly output any problems
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getTagPluginManager()
     */
    public TagPluginManager getTagPluginManager() {
        return tagPluginManager;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getTldLocationsCache()
     */
    public TldLocationsCache getTldLocationsCache() {
        return tldLocationsCache;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#getTrimSpaces()
     */
    public boolean getTrimSpaces() {
        return jasperTrimSpaces;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#isPoolingEnabled()
     */
    public boolean isPoolingEnabled() {
        return jasperEnablePooling;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#isSmapDumped()
     */
    public boolean isSmapDumped() {
        // always include the SMAP (optionally, limit to if debugging)
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#isSmapSuppressed()
     */
    public boolean isSmapSuppressed() {
        // require SMAP
        return jasperSuppressSmap;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.jasper.Options#isXpoweredBy()
     */
    public boolean isXpoweredBy() {
        // no XpoweredBy setting please
        return false;
    }

    public boolean getDisplaySourceFragment() {
        // Display the source fragment on errors for maven compilation
        return true;
    }

    private static final ConcurrentHashMap<String, Artifact> cache = new ConcurrentHashMap<>();

    public static Artifact getOrResolveArtifact(final MavenProject project,
                                                final MavenSession session,
                                                final ArtifactHandlerManager artifactHandlerManager,
                                                final ArtifactResolver resolver,
                                                final ArtifactId id) {
        @SuppressWarnings("unchecked")
        Artifact result = cache.get(id.toMvnId());
        if ( result == null ) {
            result = findArtifact(id, project.getAttachedArtifacts());
            if ( result == null ) {
                result = findArtifact(id, project.getArtifacts());
                if ( result == null ) {
                    final Artifact prjArtifact = new DefaultArtifact(id.getGroupId(),
                            id.getArtifactId(),
                            VersionRange.createFromVersion(id.getVersion()),
                            Artifact.SCOPE_PROVIDED,
                            id.getType(),
                            id.getClassifier(),
                            artifactHandlerManager.getArtifactHandler(id.getType()));
                    try {
                        resolver.resolve(prjArtifact, project.getRemoteArtifactRepositories(), session.getLocalRepository());
                    } catch (final ArtifactResolutionException | ArtifactNotFoundException e) {
                        throw new RuntimeException("Unable to get artifact for " + id.toMvnId(), e);
                    }
                    result = prjArtifact;
                }
            }
            cache.put(id.toMvnId(), result);
        }

        return result;
    }

    private static Artifact findArtifact(final ArtifactId id, final Collection<Artifact> artifacts) {
        if (artifacts != null) {
            for(final Artifact artifact : artifacts) {
                if ( artifact.getGroupId().equals(id.getGroupId())
                        && artifact.getArtifactId().equals(id.getArtifactId())
                        && artifact.getVersion().equals(id.getVersion())
                        && artifact.getType().equals(id.getType())
                        && ((id.getClassifier() == null && artifact.getClassifier() == null) || (id.getClassifier() != null && id.getClassifier().equals(artifact.getClassifier()))) ) {
                    return artifact.getFile() == null ? null : artifact;
                }
            }
        }
        return null;
    }
}
