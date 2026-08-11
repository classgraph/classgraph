package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for the public API of the classpath finder. */
public class ClassPathFinderTest {
    /** The classpath of the JVM running the tests contains the directory the test classes were compiled to. */
    @Test
    public void theEnvironmentClasspathIsFound() {
        final var classPath = new ClassPathFinder().find();
        assertThat(classPath.getLocations()).anyMatch(location -> location.endsWith("/target/test-classes"));
    }

    /** Every entry records the classloader it was found through, and the package roots to look for within it. */
    @Test
    public void entriesRecordTheirClassLoaderAndPackageRoots() {
        final var entries = new ClassPathFinder().find().getEntries();
        assertThat(entries).isNotEmpty();
        for (final ClassPathEntry entry : entries) {
            assertThat(entry.location()).isNotEmpty();
            assertThat(entry.packageRootPrefixes()).isNotEmpty();
            assertThat(entry.toString()).startsWith(entry.location());
        }
    }

    /** An overridden classpath is reported verbatim, and nothing from the environment is added to it. */
    @Test
    public void anOverriddenClasspathIsUsedInsteadOfTheEnvironment() {
        final var first = new File("first.jar").getAbsoluteFile();
        final var second = new File("second.jar").getAbsoluteFile();
        final var classPath = new ClassPathFinder().overrideClasspath(first + File.pathSeparator + second).find();
        assertThat(classPath.getLocations()).containsExactly(first.getPath().replace(File.separatorChar, '/'),
                second.getPath().replace(File.separatorChar, '/'));
        // Modules are not scanned when the classpath is overridden
        assertThat(classPath.getModules()).isEmpty();
    }

    /** Each classpath element of an overridden classpath is passed through unsplit by the non-String overloads. */
    @Test
    public void theClasspathCanBeOverriddenWithIndividualElements() {
        final var jar = new File("only.jar").getAbsoluteFile();
        final var expected = List.of(jar.getPath().replace(File.separatorChar, '/'));
        assertThat(new ClassPathFinder().overrideClasspath((Object) jar).find().getLocations()).isEqualTo(expected);
        assertThat(new ClassPathFinder().overrideClasspath(List.of(jar)).find().getLocations()).isEqualTo(expected);
        // A single Path is one classpath entry, not a sequence of its name elements
        assertThat(new ClassPathFinder().overrideClasspath(jar.toPath()).find().getLocations()).isEqualTo(expected);
    }

    /** An empty classpath override is a caller error, rather than a silent scan of nothing. */
    @Test
    public void anEmptyClasspathOverrideIsRejected() {
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(new Object[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClassLoaders())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A classloader is passed to {@code overrideClassLoaders}, not to {@code overrideClasspath}. */
    @Test
    public void aClassLoaderIsNotAClasspathElement() {
        assertThatThrownBy(
                () -> new ClassPathFinder().overrideClasspath((Object) ClassPathFinderTest.class.getClassLoader()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The modules the JVM can see are found, and split into the JDK's own modules and everything else. */
    @Test
    public void theModulesAreFoundAndSplitIntoSystemAndNonSystem() {
        final var classPath = new ClassPathFinder().find();
        assertThat(classPath.getSystemModules()).anyMatch(module -> "java.base".equals(module.descriptor().name()));
        assertThat(classPath.getNonSystemModules())
                .noneMatch(module -> module.descriptor().name().startsWith("java."));
        // getModules() lists the system modules first, then the rest
        assertThat(classPath.getModules()).startsWith(classPath.getSystemModules().get(0));
        assertThat(classPath.getModules())
                .hasSize(classPath.getSystemModules().size() + classPath.getNonSystemModules().size());
    }

    /** Module finding can be switched off altogether. */
    @Test
    public void moduleFindingCanBeDisabled() {
        assertThat(new ClassPathFinder().ignoreModules().find().getModules()).isEmpty();
    }

    /** The module path switches the JVM was launched with are reachable from the result. */
    @Test
    public void theModulePathInfoIsReachable() {
        assertThat(new ClassPathFinder().find().getModulePathInfo()).isNotNull();
    }

    /** The result prints one classpath element or module per line. */
    @Test
    public void theClassPathPrintsOneEntryPerLine() {
        final var jar = new File("only.jar").getAbsoluteFile();
        assertThat(new ClassPathFinder().overrideClasspath((Object) jar).find())
                .hasToString(jar.getPath().replace(File.separatorChar, '/') + "\n");
    }
}
