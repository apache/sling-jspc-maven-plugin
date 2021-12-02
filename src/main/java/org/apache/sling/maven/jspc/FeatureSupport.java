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

import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.maven.jspc.classloader.DynamicClassLoaderManagerFactory;
import org.apache.sling.maven.jspc.classloader.DynamicClassLoaderManagerImpl;
import org.apache.sling.scripting.jsp.SlingTldLocationsCache;
import org.apache.sling.scripting.jsp.jasper.compiler.TldLocationsCache;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.util.tracker.ServiceTracker;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;


public class FeatureSupport {
    private final Framework framework;
    private final ClassLoader loader;
    private final TldLocationsCache locationsCache;
    private final Feature feature;

    public FeatureSupport(Framework framework, ClassLoader loader, TldLocationsCache locationsCache, Feature feature) {
        this.framework = framework;
        this.loader = loader;
        this.locationsCache = locationsCache;
        this.feature = feature;
    }

    public ClassLoader getClassLoader() {
        return loader;
    }

    public TldLocationsCache getTldLocationsCache() {
        return locationsCache;
    }

    public Feature getFeature() {
        return feature;
    }

    public static FeatureSupport createFeatureSupport(Feature feature, ArtifactProvider provider, ClassLoader loader, boolean failOnUnresolvedBundles,
            Function<Map<String, String>, Map<String, String>> frameworkPropertiesHandler) throws BundleException {
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class, loader).iterator().next();

        Map<String, String> properties = new HashMap<>(feature.getFrameworkProperties());

        properties = frameworkPropertiesHandler.apply(properties);

        Map<String, String> frameworkProperties = new HashMap<>();
        properties.forEach((key, value) -> {
            frameworkProperties.put(key, value.replace("{dollar}", "$"));
        });

        Framework framework = factory.newFramework(frameworkProperties);

        framework.init();

        try {

            List<Bundle> bundles = install(framework, feature, provider);

            framework.start();

            for (Bundle bundle : bundles) {
                if (bundle.getState() != Bundle.RESOLVED) {
                    boolean resolved = framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
                    if (!resolved && failOnUnresolvedBundles) {
                        throw new BundleException("Unable to resolve bundle: " + bundle.getLocation());
                    }
                }
            }

            ServiceTracker<PackageAdmin, PackageAdmin> tracker = new ServiceTracker<>(framework.getBundleContext(), PackageAdmin.class, null);
            tracker.open();

            PackageAdmin admin = tracker.waitForService(1000);

            DynamicClassLoaderManagerImpl manager = new DynamicClassLoaderManagerImpl(framework.getBundleContext(), admin, loader,
                    new DynamicClassLoaderManagerFactory(framework.getBundleContext(), admin));
            SlingTldLocationsCache tldLocationsCache = new SlingTldLocationsCache(framework.getBundleContext());

            FeatureSupport featureSupport = new FeatureSupport(framework, manager.getDynamicClassLoader(), tldLocationsCache, feature);

            return featureSupport;
        } catch (Throwable t) {
            try {
                framework.stop();
                framework.waitForStop(10000);
            } finally {
                throw new RuntimeException(t);
            }
        }
    }

    private static List<Bundle> install(final Framework framework, final Feature feature, final ArtifactProvider provider) throws BundleException {
        final BundleContext bc = framework.getBundleContext();
        int defaultStartLevel = getProperty(bc, "felix.startlevel.bundle", 1);
        Map<Integer, List<Artifact>> bundlesByStartOrder = feature.getBundles().getBundlesByStartOrder();
        List<Bundle> bundles = new ArrayList<>();
        for(final Integer startLevel : sortStartLevels(bundlesByStartOrder.keySet(), defaultStartLevel)) {
            for(final Artifact bundleArtifact : bundlesByStartOrder.get(startLevel)) {
                URL url = provider.provide(bundleArtifact.getId());
                // use reference protocol if possible. This avoids copying the binary to the cache directory
                // of the framework
                String location = "";
                if (url.getProtocol().equals("file")) {
                    location = "reference:";
                }
                location = location + url.toString();

                final Bundle bundle = bc.installBundle(location, null);
                if (!isSystemBundleFragment(bundle) && getFragmentHostHeader(bundle) == null) {
                    bundles.add(bundle);
                }
            }
        }
        return bundles;
    }

    private static int getProperty(BundleContext bc, String propName, int defaultValue) {
        String val = bc.getProperty(propName);
        if (val == null) {
            return defaultValue;
        } else {
            return Integer.parseInt(val);
        }
    }

    /**
     * Sort the start levels in the ascending order. The only exception is the start level
     * "0", which should be put at the position configured in {@code felix.startlevel.bundle}.
     *
     * @param startLevels integer start levels
     * @return sorted start levels
     */
    private static Iterable<Integer> sortStartLevels(final Collection<Integer> startLevels, final int defaultStartLevel) {
        final List<Integer> result = new ArrayList<>(startLevels);
        Collections.sort(result, (o1, o2) -> {
            int i1 = o1 == 0 ? defaultStartLevel : o1;
            int i2 = o2 == 0 ? defaultStartLevel : o2;
            return Integer.compare(i1, i2);
        });
        return result;
    }

    private static boolean isSystemBundleFragment(final Bundle installedBundle) {
        final String fragmentHeader = getFragmentHostHeader(installedBundle);
        return fragmentHeader != null
                && fragmentHeader.indexOf(Constants.EXTENSION_DIRECTIVE) > 0;
    }

    private static String getFragmentHostHeader(final Bundle b) {
        return b.getHeaders().get( Constants.FRAGMENT_HOST );
    }

    public void shutdown(long timeout) throws Exception {
        framework.stop();
        framework.waitForStop(timeout);
    }
}
