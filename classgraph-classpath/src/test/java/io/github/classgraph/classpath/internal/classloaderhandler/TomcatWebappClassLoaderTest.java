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
import org.apache.catalina.webresources.WarResourceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathEntry;
import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a Tomcat webapp is read out of the {@code WebResourceSet}s that Catalina serves the
 * webapp's classes and resources from. The webapp's classloader loads a resource {@code "x"} from whichever
 * resource set serves {@code "/WEB-INF/classes/x"}, so only the part of each resource set that is served under
 * {@code "/WEB-INF/classes"} is on the classpath.
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
     * Catalina serves an unpacked webapp's directory mounted at {@code "/"}, so only its {@code WEB-INF/classes}
     * dir is on the classpath. Tomcat 10.1 removed {@code WebappClassLoaderBase#getResources()}, so the resource
     * root has to be read from the field that method used to return, or none of the webapp's resource sets are
     * found.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void webappClassesDirIsOnTheClasspathOnTomcat101(@TempDir final Path tempDir) throws IOException {
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
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
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        assertThat(locations(new WebappClassLoader(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * The rest of the webapp, such as {@code index.jsp} or {@code WEB-INF/web.xml}, is not on the classpath: the
     * webapp's directory is not, even with {@code WEB-INF/classes} as a package root within it, since the
     * classloader cannot load anything from outside {@code WEB-INF/classes}.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void theRestOfTheWebappIsNotOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var webappDir = tempDir.resolve("webapp");
        Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        assertThat(entries(new WebappClassLoaderBase(root, /* parent = */ null)))
                .allSatisfy(entry -> assertThat(entry.getPackageRootPrefixes()).isEmpty())
                .noneSatisfy(entry -> assertThat(entry.getLocation()).isEqualTo(location(webappDir)));
    }

    /**
     * Catalina serves a packed WAR file mounted at {@code "/"}, so only its {@code WEB-INF/classes} dir is on the
     * classpath, as a path within the WAR file.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aPackedWarsClassesDirIsOnTheClasspathAsAPathWithinTheWar(@TempDir final Path tempDir)
            throws IOException {
        final var war = Files.createFile(tempDir.resolve("app.war"));
        final var root = new StandardRoot().addResourceSets(new WarResourceSet("/", war.toString()));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(war) + "!/WEB-INF/classes");
    }

    /**
     * Catalina serves each jarfile in an unpacked webapp's {@code WEB-INF/lib} mounted at
     * {@code "/WEB-INF/classes"}, so the whole jarfile is on the classpath. It serves a resource jar's
     * {@code META-INF/resources} dir mounted at {@code "/"} as well, but for static content only, so that dir is
     * not on the classpath as a classpath element of its own -- the classloader serves {@code META-INF/resources/x}
     * from the jarfile, not {@code x}.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aLibJarIsOnTheClasspathButNotItsStaticResources(@TempDir final Path tempDir) throws IOException {
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var libJar = Files
                .createFile(Files.createDirectories(webappDir.resolve("WEB-INF/lib")).resolve("resources.jar"));
        final var classesJar = new JarResourceSet("/WEB-INF/classes", libJar.toString(), "/");
        classesJar.setClassLoaderOnly(true);
        final var resourceJar = new JarResourceSet("/", libJar.toString(), "/META-INF/resources");
        resourceJar.setStaticOnly(true);
        final var root = new StandardRoot() //
                .addResourceSets() // preResources
                .addResourceSets(new DirResourceSet("/", webappDir.toString(), "/")) // mainResources
                .addResourceSets(classesJar) // classResources
                .addResourceSets(resourceJar) // jarResources
                .addResourceSets(); // postResources
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir), location(libJar));
    }

    /**
     * Catalina serves each jarfile in a packed WAR file's {@code WEB-INF/lib} from within the WAR, mounted at
     * {@code "/WEB-INF/classes"}, so the jarfile is on the classpath as a path within the WAR.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aJarWithinAWarIsOnTheClasspathAsAPathWithinTheWar(@TempDir final Path tempDir) throws IOException {
        final var war = Files.createFile(tempDir.resolve("app.war"));
        final var classesJar = new JarWarResourceSet("/WEB-INF/classes", war.toString(),
                "WEB-INF/lib/dependency.jar", "/");
        classesJar.setClassLoaderOnly(true);
        final var root = new StandardRoot().addResourceSets(new WarResourceSet("/", war.toString()))
                .addResourceSets(classesJar);
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null))).containsExactly(
                location(war) + "!/WEB-INF/classes", location(war) + "!/WEB-INF/lib/dependency.jar");
    }

    /**
     * A subclass of a resource set is read the same way as the resource set it extends, so a jarfile served by a
     * subclass of {@code JarResourceSet} still goes on the classpath as a path within the jarfile.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aSubclassOfAResourceSetIsReadTheSameWay(@TempDir final Path tempDir) throws IOException {
        final var jar = Files.createFile(tempDir.resolve("classes.jar"));
        final var classesJar = new JarResourceSet("/WEB-INF/classes", jar.toString(), "/lib-classes") {
            // A subclass of a Catalina resource set, as a container that embeds Tomcat might have
        };
        classesJar.setClassLoaderOnly(true);
        final var root = new StandardRoot().addResourceSets(classesJar);
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(jar) + "!/lib-classes");
    }

    /**
     * A directory added with {@code <PreResources webAppMount="/WEB-INF/classes" internalPath="/target/classes">},
     * which is how a project's build output is often added to a webapp, is on the classpath as that path within the
     * directory.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void anExtraClassesDirIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var projectDir = tempDir.resolve("project");
        final var classesDir = Files.createDirectories(projectDir.resolve("target/classes"));
        final var root = new StandardRoot()
                .addResourceSets(new DirResourceSet("/WEB-INF/classes", projectDir.toString(), "/target/classes"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * A directory mounted above {@code "/WEB-INF/classes"} is on the classpath only as far as the part of it that
     * is served under {@code "/WEB-INF/classes"}. A mount written with a trailing slash is the same mount, since
     * Catalina matches a mount as a plain prefix of the path it looks up.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aDirMountedAboveTheClassesDirIsOnTheClasspathOnlyAsFarAsItsClassesDir(@TempDir final Path tempDir)
            throws IOException {
        final var webInfDir = tempDir.resolve("web-inf");
        final var classesDir = Files.createDirectories(webInfDir.resolve("classes"));
        final var otherWebInfDir = tempDir.resolve("other-web-inf");
        final var otherClassesDir = Files.createDirectories(otherWebInfDir.resolve("classes"));
        final var root = new StandardRoot().addResourceSets(
                new DirResourceSet("/WEB-INF", webInfDir.toString(), "/"),
                new DirResourceSet("/WEB-INF/", otherWebInfDir.toString(), "/"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir), location(otherClassesDir));
    }

    /**
     * A directory mounted anywhere outside {@code "/WEB-INF/classes"}, as static content usually is, is not on the
     * classpath. Nor is a directory mounted below it, which the classloader serves its files from with the rest of
     * the mount as a prefix -- something a classpath element cannot express.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aDirMountedElsewhereIsNotOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var staticDir = Files.createDirectories(tempDir.resolve("static"));
        final var subDir = Files.createDirectories(tempDir.resolve("sub"));
        final var root = new StandardRoot()
                .addResourceSets(new DirResourceSet("/WEB-INF/classes/sub", subDir.toString(), "/"))
                .addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"))
                .addResourceSets(new DirResourceSet("/static", staticDir.toString(), "/"));
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
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
        final var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        final var root = new StandardRoot().addResourceSets(new EmptyResourceSet(),
                new DirResourceSet("/WEB-INF/classes", classesDir.toString(), "/"), new EmptyResourceSet());
        assertThat(locations(new WebappClassLoaderBase(root, /* parent = */ null)))
                .containsExactly(location(classesDir));
    }

    /**
     * A webapp classloader that delegates to its parent first, which is what {@code <Context delegate="true">}
     * does, contributes its parent's classpath elements before its own, since that is the order in which a
     * duplicated class is resolved.
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
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            final var webappClassLoader = new WebappClassLoaderBase(root, parent);
            webappClassLoader.delegate = true;
            assertThat(locations(webappClassLoader)).containsExactly(location(sharedDir), location(classesDir));
        }
    }

    /**
     * A webapp classloader that looks in the webapp before delegating to its parent -- which is Tomcat's default,
     * what {@code <Context delegate="false">} does, and the Servlet specification's delegation order -- contributes
     * its own classpath elements first.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aWebappContributesItsOwnClasspathFirstByDefault(@TempDir final Path tempDir) throws IOException {
        final var sharedDir = Files.createDirectories(tempDir.resolve("shared"));
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            assertThat(locations(new WebappClassLoaderBase(root, parent))).containsExactly(location(classesDir),
                    location(sharedDir));
        }
    }

    /**
     * A webapp classloader set explicitly to look in the webapp before delegating to its parent contributes its own
     * classpath elements first.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aParentLastWebappContributesItsOwnClasspathFirst(@TempDir final Path tempDir) throws IOException {
        final var sharedDir = Files.createDirectories(tempDir.resolve("shared"));
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            final var webappClassLoader = new WebappClassLoaderBase(root, parent);
            webappClassLoader.delegate = false;
            assertThat(locations(webappClassLoader)).containsExactly(location(classesDir), location(sharedDir));
        }
    }

    /**
     * A subclass of the webapp classloader that declares a field of its own named {@code delegate}, which hides
     * Catalina's {@code boolean delegate} field from a lookup by name.
     */
    private static class DelegatingWebappClassLoader extends WebappClassLoaderBase {
        /**
         * The classloader this classloader forwards some lookups to, which is not a {@link Boolean}. It is only
         * read by reflection.
         */
        @SuppressWarnings({ "hiding", "unused" })
        final ClassLoader delegate;

        /**
         * Constructor.
         *
         * @param resources
         *            the {@code WebResourceRoot} of the webapp.
         * @param parent
         *            the parent classloader, which is also the classloader that lookups are forwarded to.
         */
        DelegatingWebappClassLoader(final Object resources, final ClassLoader parent) {
            super(resources, parent);
            this.delegate = parent;
        }
    }

    /**
     * When a subclass of the webapp classloader declares a {@code delegate} field that is not a {@link Boolean},
     * the delegation order is read from Catalina's {@code getDelegate()} method instead.
     *
     * @param tempDir
     *            a temporary directory to create the webapp in.
     * @throws IOException
     *             if the webapp could not be created.
     */
    @Test
    public void aDelegateFieldThatIsNotABooleanIsReadThroughGetDelegate(@TempDir final Path tempDir)
            throws IOException {
        final var sharedDir = Files.createDirectories(tempDir.resolve("shared"));
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        try (var parent = new URLClassLoader(new URL[] { sharedDir.toUri().toURL() }, /* parent = */ null)) {
            final var parentFirst = new DelegatingWebappClassLoader(root, parent);
            ((WebappClassLoaderBase) parentFirst).delegate = true;
            assertThat(locations(parentFirst)).containsExactly(location(sharedDir), location(classesDir));

            final var parentLast = new DelegatingWebappClassLoader(root, parent);
            ((WebappClassLoaderBase) parentLast).delegate = false;
            assertThat(locations(parentLast)).containsExactly(location(classesDir), location(sharedDir));
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
        final var webappDir = tempDir.resolve("webapp");
        final var classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final var urlDir = Files.createDirectories(tempDir.resolve("url"));
        final var root = new StandardRoot().addResourceSets(new DirResourceSet("/", webappDir.toString(), "/"));
        final var urls = new URL[] { urlDir.toUri().toURL() };
        assertThat(locations(new WebappClassLoaderBase(root, urls, /* parent = */ null)))
                .containsExactly(location(classesDir), location(urlDir));
    }
}
