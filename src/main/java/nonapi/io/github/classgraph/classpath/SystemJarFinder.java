/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.classpath;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A class to find rt.jar and any JRE "lib/" or "ext/" jars. */
public final class SystemJarFinder {
    /** The paths of any "rt.jar" files found in the JRE. */
    private static final Set<String> RT_JARS = new LinkedHashSet<>();

    /** The path of the first "rt.jar" found. */
    private static final String RT_JAR;

    /** The paths of any "lib/" or "ext/" jars found in the JRE. */
    private static final Set<String> JRE_LIB_OR_EXT_JARS = new LinkedHashSet<>();

    /**
     * Constructor.
     */
    private SystemJarFinder() {
        // Cannot be constructed
    }

    /**
     * Add and search a JRE path.
     *
     * @param dir
     *            the JRE directory
     * @return true if the directory was readable.
     */
    private static boolean addJREPath(final File dir) {
        if (dir != null && !dir.getPath().isEmpty() && FileUtils.canReadAndIsDir(dir)) {
            final File[] dirFiles = dir.listFiles();
            if (dirFiles != null) {
                for (final File file : dirFiles) {
                    final String filePath = file.getPath();
                    if (filePath.endsWith(".jar")) {
                        final String jarPathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, filePath);
                        if (jarPathResolved.endsWith("/rt.jar")) {
                            RT_JARS.add(jarPathResolved);
                        } else {
                            JRE_LIB_OR_EXT_JARS.add(jarPathResolved);
                        }
                        try {
                            final File canonicalFile = file.getCanonicalFile();
                            final String canonicalFilePath = canonicalFile.getPath();
                            if (!canonicalFilePath.equals(filePath)) {
                                final String canonicalJarPathResolved = FastPathResolver
                                        .resolve(FileUtils.CURR_DIR_PATH, filePath);
                                JRE_LIB_OR_EXT_JARS.add(canonicalJarPathResolved);
                            }
                        } catch (IOException | SecurityException e) {
                            // Ignored
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    // Find jars in JRE dirs ({java.home}, {java.home}/lib, {java.home}/lib/ext, etc.)
    static {
        String javaHome = VersionFinder.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            javaHome = System.getenv("JAVA_HOME");
        }
        if (javaHome != null && !javaHome.isEmpty()) {
            final File javaHomeFile = new File(javaHome);
            addJREPath(javaHomeFile);
            if (javaHomeFile.getName().equals("jre")) {
                // Try adding "{java.home}/.." as a JDK root when java.home is a JRE path
                final File jreParent = javaHomeFile.getParentFile();
                addJREPath(jreParent);
                addJREPath(new File(jreParent, "lib"));
                addJREPath(new File(jreParent, "lib/ext"));
            } else {
                // Try adding "{java.home}/jre" as a JRE root when java.home is not a JRE path
                addJREPath(new File(javaHomeFile, "jre"));
            }
            addJREPath(new File(javaHomeFile, "lib"));
            addJREPath(new File(javaHomeFile, "lib/ext"));
            addJREPath(new File(javaHomeFile, "jre/lib"));
            addJREPath(new File(javaHomeFile, "jre/lib/ext"));
            addJREPath(new File(javaHomeFile, "packages"));
            addJREPath(new File(javaHomeFile, "packages/lib"));
            addJREPath(new File(javaHomeFile, "packages/lib/ext"));
        }
        final String javaExtDirs = VersionFinder.getProperty("java.ext.dirs");
        if (javaExtDirs != null && !javaExtDirs.isEmpty()) {
            for (final String javaExtDir : JarUtils.smartPathSplit(javaExtDirs, /* scanSpec = */ null)) {
                if (!javaExtDir.isEmpty()) {
                    addJREPath(new File(javaExtDir));
                }
            }
        }

        // System extension paths -- see: https://docs.oracle.com/javase/tutorial/ext/basics/install.html
        switch (VersionFinder.OS) {
        case Linux:
        case Unix:
        case BSD:
        case Unknown:
            addJREPath(new File("/usr/java/packages"));
            addJREPath(new File("/usr/java/packages/lib"));
            addJREPath(new File("/usr/java/packages/lib/ext"));
            break;
        case MacOSX:
            addJREPath(new File("/System/Library/Java"));
            addJREPath(new File("/System/Library/Java/Libraries"));
            addJREPath(new File("/System/Library/Java/Extensions"));
            break;
        case Windows:
            final String systemRoot = File.separatorChar == '\\' ? System.getenv("SystemRoot") : null;
            if (systemRoot != null) {
                addJREPath(new File(systemRoot, "Sun\\Java"));
                addJREPath(new File(systemRoot, "Sun\\Java\\lib"));
                addJREPath(new File(systemRoot, "Sun\\Java\\lib\\ext"));
                addJREPath(new File(systemRoot, "Oracle\\Java"));
                addJREPath(new File(systemRoot, "Oracle\\Java\\lib"));
                addJREPath(new File(systemRoot, "Oracle\\Java\\lib\\ext"));
            }
            break;
        case Solaris:
            // Solaris paths:
            addJREPath(new File("/usr/jdk/packages"));
            addJREPath(new File("/usr/jdk/packages/lib"));
            addJREPath(new File("/usr/jdk/packages/lib/ext"));
            break;
        default:
            break;
        }

        RT_JAR = RT_JARS.isEmpty() ? null : FastPathResolver.resolve(RT_JARS.iterator().next());
    }

    /**
     * Get the JRE "rt.jar" path.
     *
     * @return The path of rt.jar (in JDK 7 or 8), or null if it wasn't found (e.g. in JDK 9+).
     */
    public static String getJreRtJarPath() {
        // Only include the first rt.jar -- if there is a copy in both the JDK and JRE, no need to scan both
        return RT_JAR;
    }

    /**
     * Get the JRE "lib/" and "ext/" jar paths.
     *
     * @return The paths for any jarfiles found in JRE/JDK "lib/" or "ext/" directories.
     */
    public static Set<String> getJreLibOrExtJars() {
        return JRE_LIB_OR_EXT_JARS;
    }
}
