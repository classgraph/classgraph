package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test that the modules that are only looked in for external classes, and not scanned, are listed by
 * {@link ScanResult#getModuleInfo()} in module order, so that two such modules of the same name are listed in the
 * same order in every scan, and {@link ScanResult#getModuleInfo(String)} returns the first of them.
 *
 * <p>
 * Two modules of the same name, each with a different package, are compiled at test time and defined in two
 * different layers. A classpath class extends a class in the first module and implements an interface in the
 * second, so both modules are looked in to complete the class graph above it.
 */
public class LookupOnlyModuleOrderTest {
    /** The name of both modules. */
    private static final String MODULE_NAME = "lookuponly.mod";

    /** The package of the classpath class. */
    private static final String SUB_PACKAGE = "lookuponly.cp";

    /** The directory of the first module. */
    private static Path firstModuleDir;

    /** The directory of the second module. */
    private static Path secondModuleDir;

    /** The directory holding the compiled classpath class. */
    private static Path classpathDir;

    /** The layer holding the first module. */
    private static ModuleLayer firstLayer;

    /** The layer holding the second module. */
    private static ModuleLayer secondLayer;

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
     * Compile a module that exports one package holding one type.
     *
     * @param tempDir
     *            the temporary directory to compile in.
     * @param dirName
     *            the name of the directory to compile the module into.
     * @param packageName
     *            the package of the type.
     * @param typeSource
     *            the declaration of the type, without its package.
     * @param typeName
     *            the simple name of the type.
     * @return the directory holding the compiled module.
     * @throws IOException
     *             if the sources could not be written.
     */
    private static Path compileModule(final Path tempDir, final String dirName, final String packageName,
            final String typeSource, final String typeName) throws IOException {
        final var srcDir = tempDir.resolve(dirName + "-src");
        final List<Path> sources = List.of(
                writeSource(srcDir, "module-info.java",
                        "module " + MODULE_NAME + " {\n    exports " + packageName + ";\n}\n"),
                writeSource(srcDir, packageName.replace('.', '/') + "/" + typeName + ".java",
                        "package " + packageName + ";\n" + typeSource + "\n"));
        final var moduleDir = tempDir.resolve(dirName);
        Files.createDirectories(moduleDir);
        final List<String> args = new ArrayList<>(List.of("-d", moduleDir.toString()));
        sources.forEach(source -> args.add(source.toString()));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .as("javac exit code").isZero();
        return moduleDir;
    }

    /**
     * Define a module in a child layer of the boot layer.
     *
     * @param moduleDir
     *            the directory holding the compiled module.
     * @return the layer.
     */
    private static ModuleLayer defineLayer(final Path moduleDir) {
        final var finder = ModuleFinder.of(moduleDir);
        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(MODULE_NAME));
        return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(bootLayer),
                LookupOnlyModuleOrderTest.class.getClassLoader()).layer();
    }

    /**
     * Compile the two modules and the classpath class, and define each module in its own layer.
     *
     * @param tempDir
     *            the temporary directory to compile in.
     * @throws IOException
     *             if the sources could not be written.
     */
    @BeforeAll
    public static void compile(@TempDir final Path tempDir) throws IOException {
        assertThat(ToolProvider.getSystemJavaCompiler()).as("the tests must be run on a JDK, not a JRE")
                .isNotNull();
        firstModuleDir = compileModule(tempDir, "first", "lookuponly.first", "public class Base {\n}", "Base");
        secondModuleDir = compileModule(tempDir, "second", "lookuponly.second", "public interface Iface {\n}",
                "Iface");

        // The classpath class is compiled against both modules' classes as plain classpath directories, since the
        // two modules have the same name and cannot both be on one module path
        final var subSource = writeSource(tempDir.resolve("cpsrc"), "lookuponly/cp/Sub.java",
                "package lookuponly.cp;\n" //
                        + "public class Sub extends lookuponly.first.Base implements lookuponly.second.Iface {\n" //
                        + "}\n");
        classpathDir = tempDir.resolve("cp");
        Files.createDirectories(classpathDir);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classpathDir.toString(), "-cp",
                firstModuleDir + File.pathSeparator + secondModuleDir, subSource.toString())).as("javac exit code")
                .isZero();

        firstLayer = defineLayer(firstModuleDir);
        secondLayer = defineLayer(secondModuleDir);
    }

    /**
     * The two modules of the same name are listed in module order, which puts the first layer's module first, in
     * every scan. Before the order was fixed, it depended on identity hash codes, so it changed from scan to scan.
     */
    @Test
    public void lookupOnlyModulesOfTheSameNameAreListedInModuleOrder() {
        final var firstLocation = firstModuleDir.toUri();
        final var secondLocation = secondModuleDir.toUri();
        for (var i = 0; i < 20; i++) {
            try (var scanResult = new ClassGraph().enableModuleLayers(firstLayer, secondLayer).enableSystemModules()
                    .enableClasspathEntries(classpathDir.toString()).acceptPackages(SUB_PACKAGE).enableClassInfo()
                    .enableExternalClasses().scan()) {
                // Both modules were looked in, to complete the class graph above the classpath class
                final var sub = scanResult.getClassInfo(SUB_PACKAGE + ".Sub");
                assertThat(sub).isNotNull();
                assertThat(sub.getSuperclass().getName()).isEqualTo("lookuponly.first.Base");
                assertThat(sub.implementsInterface("lookuponly.second.Iface")).isTrue();

                final List<URI> locations = new ArrayList<>();
                for (final ModuleInfo moduleInfo : scanResult.getModuleInfo()) {
                    if (moduleInfo.getName().equals(MODULE_NAME)) {
                        locations.add(moduleInfo.getLocationURI());
                    }
                }
                assertThat(locations).as("scan " + i).containsExactly(firstLocation, secondLocation);
                assertThat(scanResult.getModuleInfo(MODULE_NAME).getLocationURI()).as("scan " + i)
                        .isEqualTo(firstLocation);
            }
        }
    }
}
