package io.github.classgraph.issues.issue902;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * Test that the hierarchy above an accepted class is completed from classfiles in a non-system module that is not
 * being scanned, as it is for a system module (see {@link Issue902Test}). Enabling system modules alone must not
 * cut the superclass chain short at a class in a non-system module.
 *
 * <p>
 * The module is compiled at test time and defined in a child layer of the boot layer, and the accepted class, which
 * extends a class in the module, is compiled into a directory on the classpath.
 */
public class Issue902NonSystemModuleTest {
    /** The name of the module. */
    private static final String MODULE_NAME = "issue902.mod";

    /** An interface in the module. */
    private static final String IFACE = "issue902.mod.api.Iface";

    /** A class in the module that implements {@link #IFACE}. */
    private static final String ROOT = "issue902.mod.api.Root";

    /** A class in the module that extends {@link #ROOT}. */
    private static final String BASE = "issue902.mod.api.Base";

    /** The package of the classpath class. */
    private static final String SUB_PACKAGE = "issue902.cp";

    /** A classpath class that extends {@link #BASE}. */
    private static final String SUB = SUB_PACKAGE + ".Sub";

    /** The directory holding the compiled classpath class. */
    private static Path classpathDir;

    /** The layer holding the module. */
    private static ModuleLayer moduleLayer;

    /**
     * Write a source file.
     *
     * @param srcDir
     *            the source root.
     * @param relativePath
     *            the path of the source file within the source root.
     * @param source
     *            the source.
     * @return the source file.
     * @throws IOException
     *             if the file could not be written.
     */
    private static Path writeSource(final Path srcDir, final String relativePath, final String source)
            throws IOException {
        final var file = srcDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    /**
     * Compile the module and the classpath class, and define the module in a child layer of the boot layer.
     *
     * @param tempDir
     *            the temporary directory to compile in.
     * @throws IOException
     *             if the sources could not be written.
     */
    @BeforeAll
    public static void compile(@TempDir final Path tempDir) throws IOException {
        final var moduleSrcDir = tempDir.resolve("modsrc");
        final var moduleSources = List.of( //
                writeSource(moduleSrcDir, "module-info.java", "module " + MODULE_NAME + " {\n" //
                        + "    exports issue902.mod.api;\n" //
                        + "}\n"),
                writeSource(moduleSrcDir, "issue902/mod/api/Iface.java", "package issue902.mod.api;\n" //
                        + "public interface Iface {\n" //
                        + "}\n"),
                writeSource(moduleSrcDir, "issue902/mod/api/Root.java", "package issue902.mod.api;\n" //
                        + "public class Root implements Iface {\n" //
                        + "}\n"),
                writeSource(moduleSrcDir, "issue902/mod/api/Base.java", "package issue902.mod.api;\n" //
                        + "public class Base extends Root {\n" //
                        + "}\n"));
        final var subSource = writeSource(tempDir.resolve("cpsrc"), "issue902/cp/Sub.java", "package issue902.cp;\n" //
                + "public class Sub extends issue902.mod.api.Base {\n" //
                + "}\n");

        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        final var moduleDir = tempDir.resolve("mods").resolve(MODULE_NAME);
        Files.createDirectories(moduleDir);
        final List<String> moduleArgs = new ArrayList<>(List.of("-d", moduleDir.toString()));
        moduleSources.forEach(source -> moduleArgs.add(source.toString()));
        assertThat(compiler.run(null, null, null, moduleArgs.toArray(String[]::new))).as("javac exit code")
                .isZero();

        classpathDir = tempDir.resolve("cp");
        Files.createDirectories(classpathDir);
        assertThat(compiler.run(null, null, null, "-d", classpathDir.toString(), "--module-path",
                moduleDir.getParent().toString(), "--add-modules", MODULE_NAME, subSource.toString()))
                .as("javac exit code").isZero();

        final var finder = ModuleFinder.of(moduleDir.getParent());
        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(MODULE_NAME));
        moduleLayer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(bootLayer),
                Issue902NonSystemModuleTest.class.getClassLoader()).layer();
    }

    /**
     * Create a scan of the classpath class, with the module's layer searched for system modules only, so that the
     * non-system module is searched but not scanned.
     *
     * @return the configured {@link ClassGraph}.
     */
    private static ClassGraph systemModulesOnly() {
        return new ClassGraph().enableModuleLayers(moduleLayer).enableSystemModules()
                .enableClasspathEntries(classpathDir.toString()).acceptPackages(SUB_PACKAGE).enableClassInfo();
    }

    /**
     * The superclass chain and the inherited interface of a classpath class are completed through a non-system
     * module, even when only system modules are enabled. External classes are enabled, since every class in the
     * chain is external here.
     */
    @Test
    public void superclassChainIsCompletedThroughAnUnscannedNonSystemModule() {
        try (var scanResult = systemModulesOnly().enableExternalClasses().scan()) {
            final var sub = scanResult.getClassInfo(SUB);
            assertThat(sub).isNotNull();
            assertThat(sub.getAllSuperclasses().getNames()).containsExactly(BASE, ROOT, "java.lang.Object");
            assertThat(sub.implementsInterface(IFACE)).isTrue();
        }
    }

    /**
     * A class read from a non-system module that is not being scanned is an external class, so it is not listed
     * among the scanned classes, and its module is not listed among the scanned modules.
     */
    @Test
    public void classesOfAnUnscannedNonSystemModuleAreExternal() {
        try (var scanResult = systemModulesOnly().scan()) {
            final var base = scanResult.getClassInfo(BASE);
            assertThat(base).isNotNull();
            // The classfile was read, so this is not just a placeholder for a class that was referenced
            assertThat(base.getClassfileMajorVersion()).isPositive();
            assertThat(base.isExternalClass()).isTrue();
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(SUB);
            assertThat(scanResult.getModuleInfo().getNames()).doesNotContain(MODULE_NAME);
        }
    }

    /** Rejecting the non-system module still stops its classfiles from being read. */
    @Test
    public void rejectedNonSystemModuleIsNotRead() {
        try (var scanResult = systemModulesOnly().rejectModules(MODULE_NAME).enableExternalClasses().scan()) {
            final var sub = scanResult.getClassInfo(SUB);
            assertThat(sub).isNotNull();
            assertThat(sub.getAllSuperclasses().getNames()).containsExactly(BASE);
            assertThat(sub.implementsInterface(IFACE)).isFalse();
        }
    }
}
