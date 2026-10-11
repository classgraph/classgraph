package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link AutomaticModuleName}. */
public class AutomaticModuleNameTest {
    /**
     * The automatic module name is derived from the jar's leafname, following the algorithm documented by
     * {@link java.lang.module.ModuleFinder#of(java.nio.file.Path...)}: the extension and any version suffix are
     * dropped, and the remaining non-alphanumeric characters become dots.
     */
    @Test
    public void automaticModuleNamesAreDerivedFromTheJarName() {
        assertThat(AutomaticModuleName.derive("/a/b/foo.jar")).isEqualTo("foo");
        assertThat(AutomaticModuleName.derive("foo.jar")).isEqualTo("foo");
        assertThat(AutomaticModuleName.derive("/a/b/commons-lang3-3.12.0.jar")).isEqualTo("commons.lang3");
        assertThat(AutomaticModuleName.derive("/a/b/my_lib.jar")).isEqualTo("my.lib");
        // Leading, trailing and repeated dots are all collapsed away
        assertThat(AutomaticModuleName.derive("/a/b/-foo--bar-.jar")).isEqualTo("foo.bar");
        // A jar nested inside another jar is named after the inner jar, whether or not its name has an extension
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/BOOT-INF/lib/inner-1.0.jar")).isEqualTo("inner");
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/lib/inner")).isEqualTo("inner");
        // A '!' that is not followed by '/' is part of a name, not a separator (#903)
        assertThat(AutomaticModuleName.derive("/a/outer.jar!/lib/we!rd-1.0.jar")).isEqualTo("we.rd");
        // A jar without an extension, in a directory whose name ends in '!', is named after the jar
        assertThat(AutomaticModuleName.derive("/a/build!/foo")).isEqualTo("foo");
    }

    /**
     * A package root within a jarfile is in the module of the jarfile, so it is named after the jarfile, not after
     * the package root.
     *
     * @param dir
     *            a directory to write the jarfile to.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void aPackageRootIsNamedAfterItsJarfile(@TempDir final Path dir) throws IOException {
        final var jar = writeJar(dir.resolve("outer-1.0.jar"), "BOOT-INF/classes/");
        assertThat(moduleNamesOf(jar + "!/BOOT-INF/classes")).containsExactly("outer");
    }

    /**
     * A jarfile without an extension, in a directory whose name ends in '!', is named after the jarfile, not after
     * the directory.
     *
     * @param dir
     *            a directory to write the jarfile to.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void aJarWithoutAnExtensionIsNamedAfterItself(@TempDir final Path dir) throws IOException {
        final var jar = writeJar(Files.createDirectory(dir.resolve("build!")).resolve("foo"), "");
        assertThat(moduleNamesOf(jar.toString())).containsExactly("foo");
    }

    /**
     * Write a jarfile that holds a copy of this class's classfile.
     *
     * @param jar
     *            the jarfile to write.
     * @param entryPrefix
     *            a prefix to store the classfile under, or the empty string to store it at the root of the jarfile.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jar, final String entryPrefix) throws IOException {
        final var classfilePath = AutomaticModuleNameTest.class.getName().replace('.', '/') + ".class";
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jar));
                var in = AutomaticModuleNameTest.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).isNotNull();
            jarOut.putNextEntry(new ZipEntry(entryPrefix + classfilePath));
            in.transferTo(jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }

    /**
     * Scan one classpath element, and return the names of the modules the scan found.
     *
     * @param classpathEntry
     *            the classpath element to scan.
     * @return the module names.
     */
    private static List<String> moduleNamesOf(final String classpathEntry) {
        try (var scanResult = new ClassGraph().enableClasspathEntries(List.of(classpathEntry)).enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(AutomaticModuleNameTest.class.getName());
            return scanResult.getModuleInfo().stream().map(ModuleInfo::getName).toList();
        }
    }

    /**
     * The name derived for a jarfile is the name the module system itself gives it.
     *
     * @param dir
     *            a directory to write the jarfiles to.
     * @throws IOException
     *             if a jarfile could not be written.
     */
    @Test
    public void theDerivedNameIsTheNameTheModuleSystemGives(@TempDir final Path dir) throws IOException {
        for (final String jarName : new String[] { "foo.jar", "commons-lang3-3.12.0.jar", "my_lib.jar",
                "-foo--bar-.jar", "..foo...jar", "a...b.jar", "x-y-2.0-SNAPSHOT.jar", "foo.bar-baz.jar",
                "foo-bar-1.jar", "foo-1.x.jar", "foo2-3.jar" }) {
            final var jarPath = dir.resolve(jarName);
            // An empty jarfile is all the module system needs to derive a name
            new JarOutputStream(Files.newOutputStream(jarPath)).close();
            final var moduleName = ModuleFinder.of(jarPath).findAll().iterator().next().descriptor().name();
            assertThat(AutomaticModuleName.derive(jarPath.toString())).as(jarName).isEqualTo(moduleName);
            Files.delete(jarPath);
        }
    }

    /**
     * When the name derived from a jarfile name is not a legal module name, because a part of it starts with a
     * digit or is a Java keyword, the module system cannot derive a name for the jarfile, and neither can
     * ClassGraph.
     *
     * @param dir
     *            a directory to write the jarfiles to.
     * @throws IOException
     *             if a jarfile could not be written.
     */
    @Test
    public void noNameIsDerivedWhenTheModuleSystemCannotDeriveOne(@TempDir final Path dir) throws IOException {
        for (final String jarName : new String[] { "x-1a.jar", "Foo-Bar_2.jar", "foo-1-2.jar", "foo-int.jar" }) {
            final var jarPath = dir.resolve(jarName);
            new JarOutputStream(Files.newOutputStream(jarPath)).close();
            assertThatExceptionOfType(FindException.class).as(jarName)
                    .isThrownBy(() -> ModuleFinder.of(jarPath).findAll());
            assertThat(AutomaticModuleName.derive(jarPath.toString())).as(jarName).isEmpty();
            Files.delete(jarPath);
        }
    }
}
