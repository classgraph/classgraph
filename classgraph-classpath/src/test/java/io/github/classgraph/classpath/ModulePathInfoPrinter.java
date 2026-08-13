package io.github.classgraph.classpath;

import java.util.Set;

/**
 * Prints what {@link ModulePathInfo} reports about the module switches the JVM was launched with, one
 * {@code <field>TAB<value>} line per value, followed by a {@code commandlineTAB<value>} line with the whole thing
 * in commandline form. Run in a child JVM by {@link ModulePathInfoTest}, since the module switches can only be read
 * from the commandline of the JVM that was launched with them.
 */
public final class ModulePathInfoPrinter {
    /** Cannot be instantiated. */
    private ModulePathInfoPrinter() {
    }

    /**
     * Print the values of one field.
     *
     * @param fieldName
     *            the name of the field.
     * @param values
     *            the values of the field.
     */
    private static void print(final String fieldName, final Set<String> values) {
        for (final String value : values) {
            System.out.println(fieldName + "\t" + value);
        }
    }

    /**
     * Print what {@link ModulePathInfo} reports.
     *
     * @param args
     *            ignored.
     */
    public static void main(final String[] args) {
        final var modulePathInfo = new ModulePathInfo();
        print("modulePath", modulePathInfo.getModulePath());
        print("addModules", modulePathInfo.getAddModules());
        print("patchModules", modulePathInfo.getPatchModules());
        print("addExports", modulePathInfo.getAddExports());
        print("addOpens", modulePathInfo.getAddOpens());
        print("addReads", modulePathInfo.getAddReads());
        System.out.println("commandline\t" + modulePathInfo);
    }
}
