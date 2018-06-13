/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderFinder {
    /**
     * Used for resolving the classes in the call stack. Requires RuntimePermission("createSecurityManager").
     */
    private static CallerResolver CALLER_RESOLVER;

    static {
        try {
            // This can fail if the current SecurityManager does not allow RuntimePermission
            // ("createSecurityManager"):
            CALLER_RESOLVER = new CallerResolver();
        } catch (final SecurityException e) {
            // Handled in findAllClassLoaders()
        }
    }

    // Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
    // https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
    // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get ModuleReferences from a Layer Configuration. */
    private static void findModuleReferences(final Object /* Configuration */ configuration,
            final Set<Object> /* Set<ModuleReference> */ moduleReferencesOut) {
        final Set<?> /* Set<ResolvedModule> */ modules = (Set<?>) ReflectionUtils.invokeMethod(configuration,
                "modules", /* throwException = */ false);
        if (modules != null) {
            // Get ModuleReferences from layer configuration
            for (final Object /* ResolvedModule */ module : modules) {
                final Object /* ModuleReference */ moduleReference = ReflectionUtils.invokeMethod(module,
                        "reference", /* throwException = */ false);
                if (moduleReference != null) {
                    moduleReferencesOut.add(moduleReference);
                }
            }
            // Recurse to parent layer configurations
            final List<?> /* List<Configuration> */ parents = (List<?>) ReflectionUtils.invokeMethod(configuration,
                    "parents", /* throwException = */ false);
            if (parents != null) {
                for (final Object /* Configuration */ parent : parents) {
                    if (parent != null) {
                        findModuleReferences(parent, moduleReferencesOut);
                    }
                }
            }
        }
    }

    /** Get ModuleReferences from a Class reference. */
    private static void findModuleReferences(final Class<?> cls,
            final Set<Object> /* Set<ModuleReference> */ moduleReferencesOut) {
        final Object /* Module */ module = ReflectionUtils.invokeMethod(cls, "getModule",
                /* throwException = */ false);
        if (module != null) {
            final Object /* ModuleLayer */ layer = ReflectionUtils.invokeMethod(module, "getLayer",
                    /* throwException = */ false);
            if (layer != null) {
                final Object /* Configuration */ configuration = ReflectionUtils.invokeMethod(layer,
                        "configuration", /* throwException = */ false);
                if (configuration != null) {
                    findModuleReferences(configuration, moduleReferencesOut);
                }
            }
        }
    }

    /** Find system modules, or return null if not running on JDK9+. */
    private static Set<Object> findSystemModules() {
        // Find system module references
        Set<Object> /* Set<ModuleReference> */ systemModules = null;
        try {
            final Class<?> moduleFinder = Class.forName("java.lang.module.ModuleFinder");
            if (moduleFinder != null) {
                final Object systemModuleFinder = ReflectionUtils.invokeStaticMethod(moduleFinder, "ofSystem",
                        /* throwException = */ false);
                if (systemModuleFinder != null) {
                    @SuppressWarnings("unchecked")
                    final Set<Object> sysMods = (Set<Object>) ReflectionUtils.invokeMethod(systemModuleFinder,
                            "findAll", /* throwException = */ true);
                    systemModules = sysMods;
                }
            }
        } catch (final Exception e) {
            // Not running on JDK9+
        }
        return systemModules;
    }

    // -------------------------------------------------------------------------------------------------------------

    public static class EnvClassLoadersAndModules {
        public ClassLoader[] classLoaders;
        public Set<Object> systemModules;
        public Set<Object> nonSystemModules;

        public EnvClassLoadersAndModules(final ClassLoader[] classLoaders, final Set<Object> systemModules,
                final Set<Object> nonSystemModules) {
            this.classLoaders = classLoaders;
            this.systemModules = systemModules;
            this.nonSystemModules = nonSystemModules;
        }
    }

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The scan spec.
     * @param log
     *            The log.
     * @return The list of classloaders for this environment, and any system and non-system modules that are
     *         discovered (in JDK9+).
     */
    public static EnvClassLoadersAndModules findEnvClassLoaders(final ScanSpec scanSpec, final LogNode log) {
        AdditionOrderedSet<ClassLoader> classLoadersUnique;
        LogNode classLoadersFoundLog = null;
        Set<Object> systemModules = null;
        Set<Object> nonSystemModules = null;
        if (scanSpec.overrideClassLoaders == null) {
            // ClassLoaders were not overridden

            // Add the ClassLoaders in the order system, caller, context; then remove any of them that are
            // parents/ancestors of one or more other classloaders (performed below). There will generally only be
            // one class left after this. In rare cases, you may have a separate callerLoader and contextLoader, but
            // those cases are ill-defined -- see:
            // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2

            // Get system classloader
            classLoadersUnique = new AdditionOrderedSet<>();
            final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null) {
                classLoadersUnique.add(systemClassLoader);
            }

            // There is one more classloader in JDK9+, the platform classloader (used for handling extensions),
            // see: http://openjdk.java.net/jeps/261#Class-loaders
            // The method call to get it is ClassLoader.getPlatformClassLoader()
            // However, since it's not possible to get URLs from this classloader, and it is the parent of
            // the application classloader returned by ClassLoader.getSystemClassLoader() (so is delegated to
            // by the application classloader), there is no point adding it here.

            // Module references (for JDK9+ / Project Jigsaw)
            final Set<Object> moduleReferences = new HashSet<>();

            // Get caller classloader
            if (CALLER_RESOLVER == null) {
                if (log != null) {
                    log.log(ClassLoaderFinder.class.getSimpleName() + " could not create "
                            + CallerResolver.class.getSimpleName() + ", current SecurityManager does not grant "
                            + "RuntimePermission(\"createSecurityManager\")");
                }
            } else {
                final Class<?>[] callStack = CALLER_RESOLVER.getClassContext();
                if (callStack == null) {
                    if (log != null) {
                        log.log(ClassLoaderFinder.class.getSimpleName() + ": "
                                + CallerResolver.class.getSimpleName() + "#getClassContext() returned null");
                    }
                } else {
                    for (int i = callStack.length - 1; i >= 0; --i) {
                        final ClassLoader callerClassLoader = callStack[i].getClassLoader();
                        if (callerClassLoader != null) {
                            classLoadersUnique.add(callerClassLoader);
                        }
                        // Find module references (for JDK9+)
                        findModuleReferences(callStack[i], moduleReferences);
                    }
                }
            }

            // Find system module references
            systemModules = findSystemModules();

            // Find non-system modules
            if (systemModules == null) {
                if (!moduleReferences.isEmpty()) {
                    // Should not happen
                    throw new RuntimeException(
                            "Failed to find system modules, but found modules through CallerResolver");
                }
            } else {
                nonSystemModules = new HashSet<>(moduleReferences);
                nonSystemModules.removeAll(systemModules);
            }

            // Get context classloader
            final ClassLoader threadClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadClassLoader != null) {
                classLoadersUnique.add(threadClassLoader);
            }

            // Add any custom-added classloaders after system/context classloaders
            if (scanSpec.addedClassLoaders != null) {
                classLoadersUnique.addAll(scanSpec.addedClassLoaders);
            }
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");

        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new AdditionOrderedSet<>(scanSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Remove all ancestral classloaders (they are called automatically during class load)
        final Set<ClassLoader> ancestralClassLoaders = new HashSet<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            for (ClassLoader cl = classLoader.getParent(); cl != null; cl = cl.getParent()) {
                ancestralClassLoaders.add(cl);
            }
        }
        final List<ClassLoader> classLoaderFinalOrder = new ArrayList<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            // Build final ClassLoader order, with ancestral classloaders removed
            if (!ancestralClassLoaders.contains(classLoader)) {
                classLoaderFinalOrder.add(classLoader);
            }
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader cl : classLoaderFinalOrder) {
                classLoadersFoundLog.log("" + cl);
            }
        }

        // Log any identified modules
        if (systemModules != null && log != null) {
            final LogNode subLog = log.log("Found system modules:");
            for (final Object m : systemModules) {
                subLog.log(m.toString());
            }
        }
        if (nonSystemModules != null && log != null) {
            final LogNode subLog = log.log("Found non-system modules:");
            for (final Object m : nonSystemModules) {
                subLog.log(m.toString());
            }
        }

        return new EnvClassLoadersAndModules(
                classLoaderFinalOrder.toArray(new ClassLoader[classLoaderFinalOrder.size()]), systemModules,
                nonSystemModules);
    }
}
