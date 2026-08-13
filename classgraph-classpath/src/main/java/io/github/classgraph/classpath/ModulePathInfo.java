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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * Information on the module path. Note that this will only include module system parameters actually listed in
 * commandline arguments -- in particular this does not include classpath elements from the traditional classpath,
 * or system modules.
 */
public class ModulePathInfo {
    /** The module path provided by the {@code --module-path} or {@code -p} switch. */
    private final Set<String> modulePath = new LinkedHashSet<>();

    /** The modules added by the {@code --add-modules} switch. */
    private final Set<String> addModules = new LinkedHashSet<>();

    /** The module patch directives provided by the {@code --patch-module} switch. */
    private final Set<String> patchModules = new LinkedHashSet<>();

    /** The module {@code exports} directives added by the {@code --add-exports} switch. */
    private final Set<String> addExports = new LinkedHashSet<>();

    /** The module {@code opens} directives added by the {@code --add-opens} switch. */
    private final Set<String> addOpens = new LinkedHashSet<>();

    /** The module {@code reads} directives added by the {@code --add-reads} switch. */
    private final Set<String> addReads = new LinkedHashSet<>();

    /** The fields. */
    private final List<Set<String>> fields = List.of( //
            modulePath, //
            addModules, //
            patchModules, //
            addExports, //
            addOpens, //
            addReads //
    );

    /** The module path commandline switches. */
    private static final List<String> argSwitches = List.of( //
            "--module-path=", //
            "--add-modules=", //
            "--patch-module=", //
            "--add-exports=", //
            "--add-opens=", //
            "--add-reads=" //
    );

    /** The module path commandline switch value delimiters. */
    private static final List<Character> argPartSeparatorChars = List.of( //
            File.pathSeparatorChar, // --module-path (delimited path format)
            ',', // --add-modules (comma-delimited)
            '\0', // --patch-module (only one param per switch)
            '\0', // --add-exports (only one param per switch)
            '\0', // --add-opens (only one param per switch)
            '\0' // --add-reads (only one param per switch)
    );

    /** Set to true once the commandline arguments have been read. */
    private boolean readCommandLineArguments;

    /** Constructor. */
    public ModulePathInfo() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the module path provided on the commandline by the {@code --module-path} or {@code -p} switch, as an
     * ordered set of module path elements (directories and jarfiles, not module names), in the order they were
     * listed on the commandline.
     *
     * <p>
     * Note that the modules the runtime adds by itself (such as the system modules) are not reached through this
     * module path, so the modules they are defined in will not be found in any of these elements.
     *
     * @return The module path, as an unmodifiable set.
     */
    public Set<String> getModulePath() {
        return snapshot(modulePath);
    }

    /**
     * Returns the modules added to the module path on the commandline using the {@code --add-modules} switch, as an
     * ordered set of module names, in the order they were listed on the commandline. Note that valid module names
     * include {@code ALL-DEFAULT}, {@code ALL-SYSTEM}, and {@code ALL-MODULE-PATH} (see
     * <a href="https://openjdk.java.net/jeps/261">JEP 261</a> for info).
     *
     * @return The added modules, as an unmodifiable set.
     */
    public Set<String> getAddModules() {
        return snapshot(addModules);
    }

    /**
     * Returns the module patch directives listed on the commandline using the {@code --patch-module} switch, as an
     * ordered set of strings in the format {@code <module>=<file>}, in the order they were listed on the
     * commandline.
     *
     * @return The module patch directives, as an unmodifiable set.
     */
    public Set<String> getPatchModules() {
        return snapshot(patchModules);
    }

    /**
     * Returns the module {@code exports} directives added on the commandline using the {@code --add-exports}
     * switch, as an ordered set of strings in the format
     * {@code <source-module>/<package>=<target-module>(,<target-module>)*}, in the order they were listed on the
     * commandline. Additionally, any {@code Add-Exports} entries found in jarfile manifests during classpath
     * scanning are appended to this set, in the format {@code <source-module>/<package>=ALL-UNNAMED}.
     *
     * @return The {@code exports} directives, as an unmodifiable set.
     */
    public Set<String> getAddExports() {
        return snapshot(addExports);
    }

    /**
     * Returns the module {@code opens} directives added on the commandline using the {@code --add-opens} switch, as
     * an ordered set of strings in the format {@code <source-module>/<package>=<target-module>(,<target-module>)*},
     * in the order they were listed on the commandline. Additionally, any {@code Add-Opens} entries found in
     * jarfile manifests during classpath scanning are appended to this set, in the format
     * {@code <source-module>/<package>=ALL-UNNAMED}.
     *
     * @return The {@code opens} directives, as an unmodifiable set.
     */
    public Set<String> getAddOpens() {
        return snapshot(addOpens);
    }

    /**
     * Returns the module {@code reads} directives added on the commandline using the {@code --add-reads} switch, as
     * an ordered set of strings in the format {@code <source-module>=<target-module>}, in the order they were
     * listed on the commandline.
     *
     * @return The {@code reads} directives, as an unmodifiable set.
     */
    public Set<String> getAddReads() {
        return snapshot(addReads);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add an {@code Add-Exports} entry found in a jarfile manifest during scanning.
     *
     * @param addExportsEntry
     *            the entry, in the format {@code <source-module>/<package>=ALL-UNNAMED}.
     */
    public synchronized void addExportsEntry(final String addExportsEntry) {
        addExports.add(addExportsEntry);
    }

    /**
     * Add an {@code Add-Opens} entry found in a jarfile manifest during scanning.
     *
     * @param addOpensEntry
     *            the entry, in the format {@code <source-module>/<package>=ALL-UNNAMED}.
     */
    public synchronized void addOpensEntry(final String addOpensEntry) {
        addOpens.add(addOpensEntry);
    }

    /**
     * Read the commandline arguments if they have not been read yet, then return an unmodifiable copy of one of the
     * field sets.
     *
     * <p>
     * A copy rather than an unmodifiable view, since {@link #addExportsEntry(String)} and
     * {@link #addOpensEntry(String)} can be called from a scan thread while the caller is still iterating the
     * returned set, and the field sets are plain {@link LinkedHashSet}s.
     *
     * @param field
     *            the field set to snapshot.
     * @return the snapshot.
     */
    private synchronized Set<String> snapshot(final Set<String> field) {
        readCommandLineArguments();
        return Collections.unmodifiableSet(new LinkedHashSet<>(field));
    }

    /**
     * Fill in the module path fields from the VM commandline arguments, the first time any of them is read.
     *
     * <p>
     * Synchronized rather than guarded by an atomic flag, so that a second thread calling this concurrently blocks
     * until the first thread has finished populating the field sets. An atomic test-and-set would let the second
     * thread return immediately and read the (plain, non-thread-safe) {@link LinkedHashSet} fields while the first
     * thread was still adding to them.
     */
    private synchronized void readCommandLineArguments() {
        // The commandline arguments are only read if the module path info is actually asked for, to avoid an
        // illegal access warning on some JREs, e.g. Adopt JDK 11 (#605)
        if (!readCommandLineArguments) {
            readCommandLineArguments = true;
            // Read the raw commandline arguments to get the module path override parameters. If the java.management
            // module is not present in the deployed runtime (for JDK 9+), or the runtime does not contain the
            // java.lang.management package (e.g. the Android build system, which also does not support JPMS
            // currently), then skip trying to read the commandline arguments (#404).
            final Class<?> managementFactory = ReflectionUtils
                    .classForNameOrNull("java.lang.management.ManagementFactory");
            final var runtimeMXBean = managementFactory == null ? null
                    : ReflectionUtils.invokeStaticMethod(/* throwException = */ false, managementFactory,
                            "getRuntimeMXBean");
            @SuppressWarnings("unchecked")
            final var commandlineArguments = runtimeMXBean == null ? null
                    : (List<String>) ReflectionUtils.invokeMethod(/* throwException = */ false, runtimeMXBean,
                            "getInputArguments");
            if (commandlineArguments != null) {
                for (final String arg : commandlineArguments) {
                    for (var i = 0; i < fields.size(); i++) {
                        final var argSwitch = argSwitches.get(i);
                        if (arg.startsWith(argSwitch)) {
                            final var argParam = arg.substring(argSwitch.length());
                            final var argField = fields.get(i);
                            final char sepChar = argPartSeparatorChars.get(i);
                            if (sepChar == '\0') {
                                // Only one param per switch
                                argField.add(argParam);
                            } else {
                                // Split arg param into parts
                                argField.addAll(Arrays.asList(
                                        JarUtils.smartPathSplit(argParam, sepChar, /* classpathSpec = */ null)));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Return the module path info in commandline format.
     *
     * <p>
     * Synchronized for the same reason {@link #snapshot(Set)} is: this reads the plain {@link LinkedHashSet} fields
     * directly, and {@link #addExportsEntry(String)} and {@link #addOpensEntry(String)} can be called from a scan
     * thread while it is doing so.
     *
     * @return the module path commandline string.
     */
    @Override
    public synchronized String toString() {
        readCommandLineArguments();
        final StringBuilder buf = new StringBuilder(1024);
        if (!modulePath.isEmpty()) {
            buf.append("--module-path=");
            buf.append(StringUtils.join(File.pathSeparator, modulePath));
        }
        if (!addModules.isEmpty()) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append("--add-modules=");
            buf.append(StringUtils.join(",", addModules));
        }
        for (final String patchModulesEntry : patchModules) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append("--patch-module=");
            buf.append(patchModulesEntry);
        }
        for (final String addExportsEntry : addExports) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append("--add-exports=");
            buf.append(addExportsEntry);
        }
        for (final String addOpensEntry : addOpens) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append("--add-opens=");
            buf.append(addOpensEntry);
        }
        for (final String addReadsEntry : addReads) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append("--add-reads=");
            buf.append(addReadsEntry);
        }
        return buf.toString();
    }
}
