package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.strategy.OsgiBundleStrategy;
import org.codehaus.plexus.classworlds.strategy.ParentFirstStrategy;
import org.codehaus.plexus.classworlds.strategy.SelfFirstStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that a Maven plugin's classpath is read from its Plexus ClassWorlds realm in the order the realm's strategy
 * loads classes in, since that order decides which copy of a duplicated class the plugin sees.
 */
public class PlexusClassRealmTest {
    /**
     * Find the classpath element locations of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath element locations.
     */
    private static List<String> locations(final ClassLoader classLoader) {
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader).find()) {
            return classpath.getLocations();
        }
    }

    /**
     * Create a jar file, and return its URL.
     *
     * @param dir
     *            the directory to create the jar in.
     * @param name
     *            the name of the jar.
     * @return the jar's URL.
     * @throws IOException
     *             if the jar could not be created.
     */
    private static URL jarUrl(final Path dir, final String name) throws IOException {
        return Files.createFile(dir.resolve(name)).toUri().toURL();
    }

    /**
     * The location that a jar created by {@link #jarUrl} is reported as.
     *
     * @param dir
     *            the directory the jar was created in.
     * @param name
     *            the name of the jar.
     * @return the location.
     */
    private static String jarLocation(final Path dir, final String name) {
        return location(dir.resolve(name));
    }

    /**
     * A realm that looks in itself for a class before asking its parent realm has its own jars ahead of its
     * parent's on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aSelfFirstRealmHasItsOwnJarsFirst(@TempDir final Path tempDir) throws IOException {
        final var parent = new URLClassLoader(new URL[] { jarUrl(tempDir, "parent.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withStrategy(new SelfFirstStrategy())
                .withParentRealm(parent);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "plugin.jar"),
                jarLocation(tempDir, "parent.jar"));
    }

    /**
     * A realm that asks its parent realm for a class first has its parent's jars ahead of its own on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aParentFirstRealmHasItsParentsJarsFirst(@TempDir final Path tempDir) throws IOException {
        final var parent = new URLClassLoader(new URL[] { jarUrl(tempDir, "parent.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withStrategy(new ParentFirstStrategy())
                .withParentRealm(parent);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "parent.jar"),
                jarLocation(tempDir, "plugin.jar"));
    }

    /**
     * A realm that loads classes the way an OSGi bundle does looks in itself first, like a self-first realm.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void anOsgiBundleRealmHasItsOwnJarsFirst(@TempDir final Path tempDir) throws IOException {
        final var parent = new URLClassLoader(new URL[] { jarUrl(tempDir, "parent.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withStrategy(new OsgiBundleStrategy())
                .withParentRealm(parent);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "plugin.jar"),
                jarLocation(tempDir, "parent.jar"));
    }

    /**
     * A realm whose strategy is a subclass of one of the Plexus strategies loads classes in the same order as the
     * strategy it extends. Plexus lets a caller supply its own {@code Strategy}, so a strategy class need not be
     * one of the three that Plexus ships.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aRealmWithASubclassOfASelfFirstStrategyHasItsOwnJarsFirst(@TempDir final Path tempDir)
            throws IOException {
        final var parent = new URLClassLoader(new URL[] { jarUrl(tempDir, "parent.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withStrategy(new SelfFirstStrategy() {
            // A caller's own strategy, which extends one of the strategies Plexus ships
        }).withParentRealm(parent);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "plugin.jar"),
                jarLocation(tempDir, "parent.jar"));
    }

    /**
     * A realm whose strategy cannot be read is treated as parent-first, which is Plexus' own default.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aRealmWithNoReadableStrategyIsTreatedAsParentFirst(@TempDir final Path tempDir) throws IOException {
        final var parent = new URLClassLoader(new URL[] { jarUrl(tempDir, "parent.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withParentRealm(parent);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "parent.jar"),
                jarLocation(tempDir, "plugin.jar"));
    }

    /**
     * A realm can import packages from realms that are not its parent, and classes in those packages are loaded
     * from the exporting realm rather than from the realm's own jars, so the exporting realm's jars come first.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void theRealmsThatPackagesAreImportedFromAreOnTheClasspathFirst(@TempDir final Path tempDir)
            throws IOException {
        final var exporter = new URLClassLoader(new URL[] { jarUrl(tempDir, "exporter.jar") }, null);
        final var realm = new ClassRealm(jarUrl(tempDir, "plugin.jar")).withStrategy(new SelfFirstStrategy())
                .importingFrom("org.example.api", exporter);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "exporter.jar"),
                jarLocation(tempDir, "plugin.jar"));
    }

    /**
     * A realm's parent realm is not the same thing as its parent classloader, and both are on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void bothTheParentRealmAndTheParentClassLoaderAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var parentRealm = new URLClassLoader(new URL[] { jarUrl(tempDir, "parentRealm.jar") }, null);
        final var parentClassLoader = new URLClassLoader(new URL[] { jarUrl(tempDir, "parentLoader.jar") }, null);
        final var realm = new ClassRealm(parentClassLoader, jarUrl(tempDir, "plugin.jar"))
                .withParentRealm(parentRealm);
        assertThat(locations(realm)).containsExactly(jarLocation(tempDir, "parentRealm.jar"),
                jarLocation(tempDir, "parentLoader.jar"), jarLocation(tempDir, "plugin.jar"));
    }
}
