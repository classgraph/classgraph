package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.catalina.loader.WebappClassLoader;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.EmptyResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.JarWarResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathEntry;
import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a Tomcat webapp is read out of the {@code WebResourceSet}s that Catalina serves the
 * webapp's classes and resources from.
 */
public class TomcatWebappClassLoaderTest {
    /**
     * Find the classpath of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath entries.
     */
    private static List<ClasspathEntry> entries(final ClassLoader classLoader) {
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader).find()) {
            return classpath.getEntries();
        }
    }

    /**
     * Find the classpath element locations of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath element locations.
     */
    private static List<String> locations(final ClassLoader classLoader) {
        return entries(classLoader).stream().map(ClasspathEntry::getLocation).toList();
    }

    /**
     * Classes served from a directory, which is how a webapp's own {@code WEB-INF/classes} is served, are on the
     * classpath. Tomcat 10.1 removed {@code WebappClassLoaderBase#getResources()}, so the resource root has to be
     * read from the field that method used to return, or none of the webapp's resource sets are found.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void webappClassesDirIsOnTheClasspathOnTomcat101(@TempDir final Path tempDir) throws IOException {
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(classesDir.toFile(), "/"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * The same webapp, on a Tomcat old enough to still have {@code getResources()}, gives the same classpath.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void webappClassesDirIsOnTheClasspathOnOlderTomcat(@TempDir final Path tempDir) throws IOException {
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(classesDir.toFile(), "/"));
        assertThat(locations(new WebappClassLoader(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * Catalina serves a webapp's own classes from {@code WEB-INF/classes} within the webapp's directory or war, and
     * does not list that directory as a classpath element of its own, so it is looked for within every classpath
     * element obtained from this classloader. No other directory is: {@code StandardRoot} mounts the webapp's own
     * classes at that one fixed path.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void webInfClassesIsLookedForWithinEveryClasspathElement(@TempDir final Path tempDir)
            throws IOException {
        final var webappDir = Files.createDirectories(tempDir.resolve("webapp"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(webappDir.toFile(), "/"));
        assertThat(entries(new WebappClassLoaderBase(root, /* parent = */ null))).allSatisfy(
                entry -> assertThat(entry.getPackageRootPrefixes()).containsExactly("WEB-INF/classes/"));
    }

    /**
     * A resource jar serves its resources from a directory within the jarfile, so it goes on the classpath as a
     * path within the jar, not as a directory path.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aResourceJarIsOnTheClasspathAsAPathWithinTheJar(@TempDir final Path tempDir) throws IOException {
        final var resourceJar = Files.createFile(tempDir.resolve("resources.jar"));
        final var root = new StandardRoot()
                .addResourceSets(new JarResourceSet(resourceJar.toUri().toURL().toString(), "/META-INF/resources"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(resourceJar) + "!/META-INF/resources");
    }

    /**
     * A subclass of a resource set is read the same way as the resource set it extends, so a resource jar served by
     * a subclass of {@code JarResourceSet} still goes on the classpath as a path within the jar.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aSubclassOfAResourceSetIsReadTheSameWay(@TempDir final Path tempDir) throws IOException {
        final var resourceJar = Files.createFile(tempDir.resolve("resources.jar"));
        final var root = new StandardRoot()
                .addResourceSets(new JarResourceSet(resourceJar.toUri().toURL().toString(), "/META-INF/resources") {
                    // A subclass of a Catalina resource set, as a container that embeds Tomcat might have
                });
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(resourceJar) + "!/META-INF/resources");
    }

    /**
     * A jarfile nested inside a WAR file goes on the classpath as a path within the WAR.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aJarWithinAWarIsOnTheClasspathAsAPathWithinTheWar(@TempDir final Path tempDir) throws IOException {
        final var war = Files.createFile(tempDir.resolve("app.war"));
        final var root = new StandardRoot().addResourceSets(
                new JarWarResourceSet(war.toUri().toURL().toString(), "WEB-INF/lib/dependency.jar", "/"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(war) + "!/WEB-INF/lib/dependency.jar");
    }

    /**
     * A resource set that serves nothing from the filesystem contributes no classpath element.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aResourceSetThatServesNothingContributesNoClasspathElement(@TempDir final Path tempDir)
            throws IOException {
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new EmptyResourceSet(),
                new DirResourceSet(classesDir.toFile(), "/"), new EmptyResourceSet());
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * A webapp classloader that delegates to its parent first, which is Tomcat's default, contributes its parent's
     * classpath elements before its own, since that is the order in which a duplicated class is resolved.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aParentFirstWebappContributesItsParentsClasspathFirst(@TempDir final Path tempDir)
            throws IOException {
        final var sharedDir = Files.createDirectories(tempDir.resolve("shared"));
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(classesDir.toFile(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            final var webappClassLoader = new WebappClassLoaderBase(root, parent);
            webappClassLoader.delegate = true;
            assertThat(locations(webappClassLoader)).containsExactly(location(sharedDir), location(classesDir));
        }
    }

    /**
     * A webapp classloader configured to look in the webapp before delegating to its parent -- which is what
     * {@code <Context delegate="false">} does, and is the Servlet specification's delegation order -- contributes
     * its own classpath elements first.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aParentLastWebappContributesItsOwnClasspathFirst(@TempDir final Path tempDir) throws IOException {
        final var sharedDir = Files.createDirectories(tempDir.resolve("shared"));
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(classesDir.toFile(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            final var webappClassLoader = new WebappClassLoaderBase(root, parent);
            webappClassLoader.delegate = false;
            assertThat(locations(webappClassLoader)).containsExactly(location(classesDir), location(sharedDir));
        }
    }

    /**
     * {@code WebappClassLoaderBase} extends {@link URLClassLoader}, so it can load from URLs that no
     * {@code WebResourceSet} of the webapp serves. Those URLs are on the classpath as well as the resource sets --
     * the handler for a subclass of {@link URLClassLoader} reads what the subclass adds, and the URLs too.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void theUrlsOfTheWebappClassLoaderAreOnTheClasspathToo(@TempDir final Path tempDir) throws IOException {
        final var classesDir = Files.createDirectories(tempDir.resolve("webapp/WEB-INF/classes"));
        final var urlDir = Files.createDirectories(tempDir.resolve("url"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet(classesDir.toFile(), "/"));
        final var urls = new URL[] { urlDir.toUri().toURL() };
        assertThat(locations(new WebappClassLoaderBase(root, urls, /* parent = */ null)))
                .containsExactly(location(classesDir), location(urlDir));
    }

    /**
     * The base URLs of the webapp are on the classpath too.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void theWebappBaseUrlsAreOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var webappDir = Files.createDirectories(tempDir.resolve("webapp"));
        final var root = new StandardRoot().addBaseUrl(webappDir.toUri().toURL());
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(webappDir));
    }
}
