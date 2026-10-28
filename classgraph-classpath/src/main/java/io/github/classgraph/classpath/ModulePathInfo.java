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

import org.jetbrains.annotations.Unmodifiable;

import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * Module system command-line options with which the running JVM was launched ({@code --module-path},
 * {@code --add-modules}, {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and
 * {@code --add-reads}).
 *
 * <p>
 * Obtain the singleton instance for the running JVM via {@link #current()}.
 */
public final class ModulePathInfo {
    // The sets below are filled in by the constructor and never changed afterward, so an instance can be read from
    // any thread without locking, and the getters can return unmodifiable views of them rather than copies

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

    /**
     * Descriptor and collected values for a single module command-line option.
     *
     * @param argSwitch
     *            the normalized option prefix including trailing {@code '='}.
     * @param argPartSeparatorChar
     *            the value delimiter character, or {@code '\0'} if the option takes a single value.
     * @param lastOccurrenceWins
     *            true if a later occurrence replaces earlier occurrences.
     * @param values
     *            the target set of parsed values.
     */
    private record ModulePathSwitch(String argSwitch, char argPartSeparatorChar, boolean lastOccurrenceWins,
            Set<String> values) {
        /**
         * Parses and adds the argument value for one occurrence of this switch.
         *
         * @param argParam
         *            the argument text following the switch prefix.
         */
        private void addArgParam(final String argParam) {
            if (lastOccurrenceWins) {
                // An earlier occurrence of the switch is overridden by this one
                values.clear();
            }
            if (argPartSeparatorChar == '\0') {
                values.add(argParam);
            } else {
                values.addAll(Arrays
                        .asList(PathList.split(argParam, argPartSeparatorChar, /* allowedURLSchemes = */ null)));
            }
        }
    }

    /** The module path command line switches, each paired with the values read for it. */
    private final List<ModulePathSwitch> modulePathSwitches = List.of( //
            new ModulePathSwitch("--module-path=", File.pathSeparatorChar, /* lastOccurrenceWins = */ true,
                    modulePath), //
            new ModulePathSwitch("--add-modules=", ',', false, addModules), //
            new ModulePathSwitch("--patch-module=", '\0', false, patchModules), //
            new ModulePathSwitch("--add-exports=", '\0', false, addExports), //
            new ModulePathSwitch("--add-opens=", '\0', false, addOpens), //
            new ModulePathSwitch("--add-reads=", '\0', false, addReads) //
    );

    /** Lazy holder for the JVM-wide {@link ModulePathInfo} instance. */
    private static final class Current {
        /** The module path switches this JVM was launched with. */
        static final ModulePathInfo INSTANCE = new ModulePathInfo(readInputArguments());

        /** Constructor. */
        private Current() {
            // Empty
        }

        /**
         * Reads the JVM launch arguments via {@code ManagementFactory.getRuntimeMXBean()}.
         *
         * @return the input arguments, or an empty list if {@code java.management} is unavailable.
         */
        private static List<String> readInputArguments() {
            final Class<?> managementFactory = ReflectionUtils
                    .classForNameOrNull("java.lang.management.ManagementFactory");
            final var runtimeMXBean = managementFactory == null ? null
                    : ReflectionUtils.invokeStaticMethod(/* throwException = */ false, managementFactory,
                            "getRuntimeMXBean");
            @SuppressWarnings("unchecked")
            final var inputArguments = runtimeMXBean == null ? null
                    : (List<String>) ReflectionUtils.invokeMethod(/* throwException = */ false, runtimeMXBean,
                            "getInputArguments");
            return inputArguments == null ? List.of() : inputArguments;
        }
    }

    /**
     * Parses module command-line options from the given normalized JVM input arguments.
     *
     * @param inputArguments
     *            the normalized JVM input arguments.
     */
    ModulePathInfo(final List<String> inputArguments) {
        for (final String arg : inputArguments) {
            for (final ModulePathSwitch modulePathSwitch : modulePathSwitches) {
                if (arg.startsWith(modulePathSwitch.argSwitch())) {
                    modulePathSwitch.addArgParam(arg.substring(modulePathSwitch.argSwitch().length()));
                }
            }
        }
    }

    /**
     * Returns the {@link ModulePathInfo} for the running JVM.
     *
     * @return the {@link ModulePathInfo} for the running JVM.
     */
    public static ModulePathInfo current() {
        return Current.INSTANCE;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the module path entries specified via {@code --module-path} or {@code -p} (from the last occurrence
     * if specified multiple times), in command-line order.
     *
     * @return the module path entries, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getModulePath() {
        return Collections.unmodifiableSet(modulePath);
    }

    /**
     * Returns the module names specified via {@code --add-modules} (which may include {@code ALL-DEFAULT},
     * {@code ALL-SYSTEM}, and {@code ALL-MODULE-PATH}), in command-line order.
     *
     * @return the added module names, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getAddModules() {
        return Collections.unmodifiableSet(addModules);
    }

    /**
     * Returns the {@code --patch-module} directives in {@code <module>=<file>} format, in command-line order.
     *
     * @return the module patch directives, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getPatchModules() {
        return Collections.unmodifiableSet(patchModules);
    }

    /**
     * Returns the {@code --add-exports} directives in
     * {@code <source-module>/<package>=<target-module>(,<target-module>)*} format, in command-line order.
     *
     * @return the {@code --add-exports} directives, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getAddExports() {
        return Collections.unmodifiableSet(addExports);
    }

    /**
     * Returns the {@code --add-opens} directives in
     * {@code <source-module>/<package>=<target-module>(,<target-module>)*} format, in command-line order.
     *
     * @return the {@code --add-opens} directives, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getAddOpens() {
        return Collections.unmodifiableSet(addOpens);
    }

    /**
     * Returns the {@code --add-reads} directives in {@code <source-module>=<target-module>} format, in command-line
     * order.
     *
     * @return the {@code --add-reads} directives, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getAddReads() {
        return Collections.unmodifiableSet(addReads);
    }

    /**
     * Formats the module options as a space-separated command-line string.
     *
     * @return the module options in command-line format.
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder(1024);
        for (final ModulePathSwitch modulePathSwitch : modulePathSwitches) {
            final var values = modulePathSwitch.values();
            if (values.isEmpty()) {
                continue;
            }
            if (modulePathSwitch.argPartSeparatorChar() == '\0') {
                // The switch takes a single value, so it is repeated once per value
                for (final String value : values) {
                    if (!buf.isEmpty()) {
                        buf.append(' ');
                    }
                    buf.append(modulePathSwitch.argSwitch()).append(value);
                }
            } else {
                if (!buf.isEmpty()) {
                    buf.append(' ');
                }
                buf.append(modulePathSwitch.argSwitch())
                        .append(StringUtils.join(String.valueOf(modulePathSwitch.argPartSeparatorChar()), values));
            }
        }
        return buf.toString();
    }
}
