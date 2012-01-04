/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.core.osgi.impl;

import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.BeanType;
import org.apache.openejb.NoSuchApplicationException;
import org.apache.openejb.RpcContainer;
import org.apache.openejb.UndeployException;
import org.apache.openejb.assembler.classic.AppInfo;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.config.AppModule;
import org.apache.openejb.config.ConfigurationFactory;
import org.apache.openejb.config.DeploymentLoader;
import org.apache.openejb.config.UnknownModuleTypeException;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.proxy.LocalBeanProxyFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @version $Rev$ $Date$
 */
public class Deployer implements BundleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(Deployer.class);
    private static Deployer INSTANCE = null;

    private final Map<Bundle, List<ServiceRegistration>> registrations = new ConcurrentHashMap<Bundle, List<ServiceRegistration>>();
    private final Map<Bundle, String> paths = new ConcurrentHashMap<Bundle, String>();

    private final Activator openejbActivator;

    public Deployer(Activator activator) {
        openejbActivator = activator;
        INSTANCE = this;
    }

    public static Deployer instance() {
        return INSTANCE;
    }

    public void bundleChanged(BundleEvent event) {
        openejbActivator.checkServiceManager(OpenEJBBundleContextHolder.get());
        switch (event.getType()) {
            case BundleEvent.STARTED:
                deploy(event.getBundle());
                break;
            case BundleEvent.STOPPED:
                undeploy(event.getBundle());
                break;
            case BundleEvent.UNINSTALLED:
                undeploy(event.getBundle());
                break;
            case BundleEvent.UPDATED:
                try {
                    undeploy(event.getBundle());
                } catch (NullPointerException npe) {
                    // can happen when shutting down an OSGi server
                    // because of all stop events
                    LOGGER.warn("can't undeploy bundle #{}", event.getBundle().getBundleId());
                }
                deploy(event.getBundle());
                break;
        }
    }

    private void deploy(Bundle bundle) {
        final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        final ClassLoader osgiCl = new OSGIClassLoader(bundle, OpenEJBBundleContextHolder.get().getBundle());
        Thread.currentThread().setContextClassLoader(osgiCl);

        try {
            try {
                try {
                    // equinox? found in aries
                    File bundleDump = bundle.getBundleContext().getDataFile(bundle.getSymbolicName() + "/" + bundle.getVersion() + "/");
                    // TODO: what should happen if there is multiple versions?
                    if (!bundleDump.exists() && bundle.getBundleContext().getDataFile("") != null) { // felix. TODO: maybe find something better
                        bundleDump = findFelixJar(bundle.getBundleContext());
                    }

                    if (!bundleDump.exists()) {
                        LOGGER.warn("can't find bundle {}", bundle.getBundleId());
                        return;
                    }

                    LOGGER.info("looking bundle {} in {}", bundle.getBundleId(), bundleDump);
                    final AppModule appModule = new OSGiDeploymentLoader(bundle).load(bundleDump);
                    LOGGER.info("deploying bundle #" + bundle.getBundleId() + " as an EJBModule");

                    final ConfigurationFactory configurationFactory = new ConfigurationFactory();
                    final AppInfo appInfo = configurationFactory.configureApplication(appModule);
                    appInfo.appId = "bundle_" + bundle.getBundleId();

                    final Assembler assembler = SystemInstance.get().getComponent(Assembler.class);
                    final AppContext appContext = assembler.createApplication(appInfo, osgiCl);
                    LOGGER.info("Application deployed: " + appInfo.path);

                    paths.put(bundle, appInfo.path);

                    registrations.put(bundle, new ArrayList<ServiceRegistration>());
                    registerService(bundle, appContext);
                } catch (UnknownModuleTypeException unknowException) {
                    LOGGER.info("bundle #" + bundle.getBundleId() + " is not an EJBModule");
                } catch (Exception ex) {
                    LOGGER.error("can't deploy bundle #" + bundle.getBundleId(), ex);
                }
            } catch (Exception ex1) {
                LOGGER.error("can't deploy bundle #" + bundle.getBundleId(), ex1);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    private static File findFelixJar(BundleContext bundleContext) {
        final File root = bundleContext.getDataFile("").getParentFile();
        int min = 0;
        int max = 0;
        File out;
        File f = null;
        do {
            do {
                out = f;
                f = new File(root, "version" + max + "." + min + "/bundle.jar");
                min++;
            } while (f.exists());
            min = 0;
            max++;
            f = new File(root, "version" + max + "." + min + "/bundle.jar");
        } while (f.exists());
        return out;
    }

    private void undeploy(Bundle bundle) {
        if (registrations.containsKey(bundle)) {
            for (ServiceRegistration registration : registrations.get(bundle)) {
                try {
                    registration.unregister();
                } catch (IllegalStateException ise) {
                    // ignored: already unregistered
                }
            }
            registrations.remove(bundle);
        }

        if (paths.containsKey(bundle)) {
            try {
                Assembler assembler = SystemInstance.get().getComponent(Assembler.class);
                if (assembler != null) { // openejb stopped before bundles when shuttind down the OSGi container
                    assembler.destroyApplication(paths.remove(bundle));
                }
            } catch (IllegalStateException ise) {
                LOGGER.error("Can't undeploy bundle #{}", bundle.getBundleId());
            } catch (UndeployException e) {
                LOGGER.error("Can't undeploy bundle #{}", bundle.getBundleId(), e);
            } catch (NoSuchApplicationException e) {
                LOGGER.error("Can't undeploy non existing bundle #{}", bundle.getBundleId(), e);
            }
        }

        LOGGER.info("[Deployer] Bundle {} has been stopped", bundle.getSymbolicName());
    }

    /**
     * Register OSGi Service for EJB so calling the service will actually call the EJB
     *
     * @param bundle     the deployed bundle
     * @param appContext the appcontext to search EJBs
     */
    private void registerService(Bundle bundle, AppContext appContext) {
        LOGGER.info("Registering remote EJBs as OSGi services");
        final BundleContext context = bundle.getBundleContext();
        for (BeanContext beanContext : appContext.getBeanContexts()) {
            if (beanContext.getBeanClass().equals(BeanContext.Comp.class) || BeanType.STATEFUL.equals(beanContext.getComponentType())) {
                continue;
            }

            try {
                if (beanContext.getBusinessRemoteInterface() != null) {
                    LOGGER.info("registering remote bean: {}", beanContext.getEjbName());
                    registerService(beanContext, context, beanContext.getBusinessRemoteInterfaces());
                }
                if (beanContext.getBusinessLocalInterface() != null) {
                    LOGGER.info("registering local bean: {}", beanContext.getEjbName());
                    registerService(beanContext, context, beanContext.getBusinessLocalInterfaces());
                }
                if (beanContext.isLocalbean()) {
                    LOGGER.info("registering local view bean: {}", beanContext.getEjbName());
                    registerService(beanContext, context, Arrays.asList(beanContext.getBusinessLocalBeanInterface()));
                }
            } catch (Exception e) {
                LOGGER.error("[Deployer] can't register: {}", beanContext.getEjbName());
            }
        }
    }

    private void registerService(BeanContext beanContext, BundleContext context, List<Class> interfaces) {
        if (!interfaces.isEmpty()) {
            Class<?>[] itfs = interfaces.toArray(new Class<?>[interfaces.size()]);
            try {
                Object service;
                if (!beanContext.isLocalbean()) {
                    service = Proxy.newProxyInstance(itfs[0].getClassLoader(), itfs, new Handler(beanContext));
                } else {
                    service = LocalBeanProxyFactory.newProxyInstance(itfs[0].getClassLoader(), itfs[0], new Handler(beanContext));
                }

                registrations.get(context.getBundle()).add(context.registerService(str(itfs), service, new Properties()));
                LOGGER.info("EJB registered: {} for interfaces {}", beanContext.getEjbName(), interfaces);
            } catch (IllegalArgumentException iae) {
                LOGGER.error("can't register: {} for interfaces {}", beanContext.getEjbName(), interfaces);
            }
        }
    }

    public Set<Bundle> deployedBundles() {
        return paths.keySet();
    }

    private static String[] str(Class<?>[] itfs) {
        String[] itfsStr = new String[itfs.length];
        for (int i = 0; i < itfs.length; i++) {
            itfsStr[i] = itfs[i].getName();
        }
        return itfsStr;
    }

    private static class Handler implements InvocationHandler {
        private BeanContext beanContext;

        public Handler(BeanContext bc) {
            beanContext = bc;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            final RpcContainer container = RpcContainer.class.cast(beanContext.getContainer());
            return container.invoke(beanContext.getDeploymentID(),
                    beanContext.getInterfaceType(method.getDeclaringClass()),
                    method.getDeclaringClass(), method, args, null);
        }
    }

    private static class OSGIClassLoader extends ClassLoader {
        private final Bundle backingBundle;
        private final Bundle fallbackBundle;

        public OSGIClassLoader(Bundle bundle, Bundle openejbClassloader) {
            super(null);
            backingBundle = bundle;
            fallbackBundle = openejbClassloader;
        }

        protected Class findClass(String name) throws ClassNotFoundException {
            try {
                return fallbackBundle.loadClass(name);
            } catch (Exception ignored) {
                // no-op
            }

            try {
                return this.backingBundle.loadClass(name);
            } catch (ClassNotFoundException cnfe) {
                if (isInterestingClass(name)) {
                    final Class<?> forced = forceLoadClass(name);
                    if (forced != null) {
                        return forced;
                    }
                }
                throw new ClassNotFoundException(name + " not found from bundle [" + backingBundle.getSymbolicName() + "]", cnfe);
            } catch (NoClassDefFoundError ncdfe) {
                NoClassDefFoundError e = new NoClassDefFoundError(name + " not found from bundle [" + backingBundle + "]");
                e.initCause(ncdfe);
                throw e;
            }
        }

        protected URL findResource(String name) {
            URL url = fallbackBundle.getResource(name);
            if (url != null) {
                return url;
            }
            url = backingBundle.getResource(name);
            if (url != null) {
                return url;
            }
            if (isInterestingClass(name)) {
                return forceLoadResource(name);
            }
            return null;
        }

        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }

        protected Enumeration findResources(String name) throws IOException {
            Enumeration<URL> urls;
            try {
                urls = fallbackBundle.getResources(name);
                if (urls != null && urls.hasMoreElements()) {
                    return urls;
                }
            } catch (IOException ignored) {
                // no-op
            }
            urls = backingBundle.getResources(name);
            if (urls != null && urls.hasMoreElements()) {
                return urls;
            }
            if (isInterestingClass(name)) {
                urls = forceLoadResources(name);
            }
            if (urls != null && urls.hasMoreElements()) {
                return urls;
            }
            return new EmptyEnumeration<URL>();
        }

        public URL getResource(String name) {
            return findResource(name);
        }

        protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class clazz = findClass(name);
            if (resolve) {
                resolveClass(clazz);
            }
            return clazz;
        }

        public String toString() {
            return "OSGIClassLoader for [" + backingBundle + "]";
        }
    }

    private static class EmptyEnumeration<T> implements Enumeration<T> {
        @Override
        public boolean hasMoreElements() {
            return false;
        }

        @Override
        public T nextElement() {
            throw new NoSuchElementException();
        }
    }

    public class OSGiDeploymentLoader extends DeploymentLoader {
        private final Bundle bundle;

        public OSGiDeploymentLoader(Bundle bdl) {
            bundle = bdl;
        }

        @Override protected ClassLoader getOpenEJBClassLoader(URL url) {
            return new OSGIClassLoader(bundle, OpenEJBBundleContextHolder.get().getBundle());
        }
    }

    private static Class<?> forceLoadClass(String name) {
        final Bundle[] bundles = OpenEJBBundleContextHolder.get().getBundles();
        for (Bundle bundle : bundles) {
            try {
                return bundle.loadClass(name);
            } catch (ClassNotFoundException e) {
                // ignored
            }
        }
        return null;
    }

    private static URL forceLoadResource(String name) {
        final Bundle[] bundles = OpenEJBBundleContextHolder.get().getBundles();
        for (Bundle bundle : bundles) {
            URL url = bundle.getResource(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static Enumeration<URL> forceLoadResources(String name) {
        final Bundle[] bundles = OpenEJBBundleContextHolder.get().getBundles();
        for (Bundle bundle : bundles) {
            Enumeration<URL> url = null;
            try {
                url = bundle.getResources(name);
            } catch (IOException e) {
                // ignored
            }
            if (url != null && url.hasMoreElements()) {
                return url;
            }
        }
        return null;
    }

    private static String className(final String name) {
        return name.replace('/', '.');
    }

    private static boolean isInterestingClass(final String rawName) {
        final String name = className(rawName);
        return isJdbcDriver(name) || isJPAProvider(name) || isBValProvider(name)
                || name.contains("org.apache.openejb") // fallback mainly for META-INF resources
                || name.startsWith("javax.management."); // dynamic mbean feature uses this package also used by the jre itself
    }

    private static boolean isJdbcDriver(final String name) {
        return name.startsWith("org.hsqldb") || name.startsWith("com.mysql") || name.startsWith("com.h2") || name.startsWith("oracle.jdbc");
    }

    private static boolean isJPAProvider(String name) {
        return name.contains("openjpa") || name.startsWith("serp.") // openjpa && its dep
                || name.startsWith("org.hibernate") || name.startsWith("oracle.toplink") || name.startsWith("org.eclipse.persistence.jpa");
    }

    private static boolean isBValProvider(String name) {
        return name.contains("org.apache.bval") || name.startsWith("org.hibernate.validator");
    }
}
