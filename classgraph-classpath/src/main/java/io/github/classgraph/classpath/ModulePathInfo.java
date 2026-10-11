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
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * The module system switches the JVM was launched with: {@code --module-path}, {@code --add-modules},
 * {@code --patch-module}, {@code --add-exports}, {@code --add-opens} and {@code --add-reads}. Only what was given
 * on the command line is listed here, so the traditional classpath and the system modules are not.
 *
 * <p>
 * The command line cannot change while the JVM runs, so there is one instance for the whole JVM, returned by
 * {@link #current()}.
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
     * One module path command line switch, and the values read for it.
     *
     * @param argSwitch
     *            the switch, including its trailing {@code '='}. The JVM normalizes the other spellings of a switch
     *            into this one before it reports its own command line: {@code -p dir} and {@code --module-path dir}
     *            are both reported as {@code --module-path=dir}, so only this spelling has to be recognized.
     * @param argPartSeparatorChar
     *            the character that separates the values of one occurrence of the switch, or {@code '\0'} if the
     *            switch takes a single value and has to be repeated to give more than one.
     * @param lastOccurrenceWins
     *            true if the JVM uses only the last occurrence of the switch when it is given more than once, as it
     *            does for {@code --module-path}; false if it adds the occurrences together.
     * @param values
     *            the values read for the switch, in the order they were listed on the command line.
     */
    private record ModulePathSwitch(String argSwitch, char argPartSeparatorChar, boolean lastOccurrenceWins,
            Set<String> values) {
        /**
         * Add the value of one occurrence of this switch, splitting it into parts if the switch takes several
         * values at once.
         *
         * @param argParam
         *            the text that followed the switch.
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

    /**
     * Holds the instance for this JVM. The command line is read when this class is initialized, which is the first
     * time {@link #current()} is called, rather than when {@link ModulePathInfo} is loaded, since calling
     * {@code ManagementFactory.getRuntimeMXBean()} initializes the JDK's management support, which is wasted work
     * unless the module path info is asked for (#605).
     */
    private static final class Current {
        /** The module path switches this JVM was launched with. */
        static final ModulePathInfo INSTANCE = new ModulePathInfo(readInputArguments());

        /** Constructor. */
        private Current() {
            // Empty
        }

        /**
         * Read the arguments this JVM was launched with.
         *
         * <p>
         * When ClassGraph is on the classpath of a runtime image without the java.management module (e.g. one
         * launched with {@code --limit-modules java.base}), the reference to {@code ManagementFactory} fails to
         * resolve when it is first run, the arguments cannot be read, and an empty list is returned (#404). This
         * must not throw, since an exception here would make every call to {@link ModulePathInfo#current()} fail.
         * Only {@link NoClassDefFoundError} is caught, so another error, such as {@link OutOfMemoryError}, gets
         * through: it escapes the static initializer of this holder class, the call to
         * {@link ModulePathInfo#current()} that ran the initializer throws it, and every later call throws
         * {@link NoClassDefFoundError}.
         *
         * @return the arguments, or an empty list if they could not be read.
         */
        private static List<String> readInputArguments() {
            try {
                return ManagementFactory.getRuntimeMXBean().getInputArguments();
            } catch (final NoClassDefFoundError _) {
                return List.of();
            }
        }
    }

    /**
     * Parse the module switches out of a list of JVM arguments. Arguments that are not module switches are ignored.
     * This does not throw.
     *
     * @param inputArguments
     *            the JVM arguments, in the normalized form that {@code RuntimeMXBean#getInputArguments()} reports
     *            them in.
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
     * Returns the module path switches this JVM was launched with. The command line is read the first time this is
     * called, and every call returns the same instance.
     *
     * @return the module path switches this JVM was launched with.
     */
    public static ModulePathInfo current() {
        return Current.INSTANCE;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the module path provided on the command line by the {@code --module-path} or {@code -p} switch, as an
     * ordered set of module path elements (directories and jarfiles, not module names), in the order they were
     * listed on the command line. If the switch was given more than once, only the last occurrence is reported,
     * since that is the only one the JVM uses.
     *
     * <p>
     * Note that the modules the runtime adds by itself (such as the system modules) are not reached through this
     * module path, so the modules they are defined in will not be found in any of these elements.
     *
     * @return The module path, as an unmodifiable set.
     */
    public Set<String> getModulePath() {
        return Collections.unmodifiableSet(modulePath);
    }

    /**
     * Returns the modules added to the module path on the command line using the {@code --add-modules} switch, as
     * an ordered set of module names, in the order they were listed on the command line. Note that valid module
     * names include {@code ALL-DEFAULT}, {@code ALL-SYSTEM}, and {@code ALL-MODULE-PATH} (see
     * <a href="https://openjdk.java.net/jeps/261">JEP 261</a> for info).
     *
     * @return The added modules, as an unmodifiable set.
     */
    public Set<String> getAddModules() {
        return Collections.unmodifiableSet(addModules);
    }

    /**
     * Returns the module patch directives listed on the command line using the {@code --patch-module} switch, as an
     * ordered set of strings in the format {@code <module>=<file>}, in the order they were listed on the command
     * line.
     *
     * @return The module patch directives, as an unmodifiable set.
     */
    public Set<String> getPatchModules() {
        return Collections.unmodifiableSet(patchModules);
    }

    /**
     * Returns the module {@code exports} directives added on the command line using the {@code --add-exports}
     * switch, as an ordered set of strings in the format
     * {@code <source-module>/<package>=<target-module>(,<target-module>)*}, in the order they were listed on the
     * command line.
     *
     * @return The {@code exports} directives, as an unmodifiable set.
     */
    public Set<String> getAddExports() {
        return Collections.unmodifiableSet(addExports);
    }

    /**
     * Returns the module {@code opens} directives added on the command line using the {@code --add-opens} switch,
     * as an ordered set of strings in the format
     * {@code <source-module>/<package>=<target-module>(,<target-module>)*}, in the order they were listed on the
     * command line.
     *
     * @return The {@code opens} directives, as an unmodifiable set.
     */
    public Set<String> getAddOpens() {
        return Collections.unmodifiableSet(addOpens);
    }

    /**
     * Returns the module {@code reads} directives added on the command line using the {@code --add-reads} switch,
     * as an ordered set of strings in the format {@code <source-module>=<target-module>}, in the order they were
     * listed on the command line.
     *
     * @return The {@code reads} directives, as an unmodifiable set.
     */
    public Set<String> getAddReads() {
        return Collections.unmodifiableSet(addReads);
    }

    /**
     * Return the module path info in command line format.
     *
     * @return the module path command line string.
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
