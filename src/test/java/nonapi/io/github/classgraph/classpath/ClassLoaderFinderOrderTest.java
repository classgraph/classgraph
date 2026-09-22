package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Tests the order of the classloaders that are found in the environment. That order decides only where the first
 * classloader of a delegation chain to be reached is placed: after that, the {@code ClassLoaderHandler} for the
 * classloader decides where its ancestors' classpath elements go relative to its own, by delegating to the parent
 * before or after adding the classloader itself. An ancestor left ahead of its own descendant would therefore be
 * pinned in front of the descendant before the descendant's handler could run, which turns parent-last delegation
 * into parent-first delegation, inverting the class masking order.
 */
public class ClassLoaderFinderOrderTest {
    /** A classloader that loads {@link Driver} itself rather than delegating to its parent. */
    public static class SelfFirstLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param urls
         *            The URLs to load classes from.
         * @param parent
         *            The parent classloader.
         */
        public SelfFirstLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Driver.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> cls = findLoadedClass(name);
                if (cls == null) {
                    cls = findClass(name);
                }
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            }
        }
    }

    /**
     * Returns the classloaders that {@link ClassLoaderFinder} finds. Loaded by {@link SelfFirstLoader} itself, so
     * the classloader of the caller of ClassGraph is the child classloader, while the thread context classloader
     * and the classloader of ClassGraph's own classes are still the parent.
     */
    public static class Driver implements Supplier<List<ClassLoader>> {
        @Override
        public List<ClassLoader> get() {
            try {
                // ClassLoaderFinder's constructor is package-private, and this class is loaded by a different
                // classloader, so it is in a different runtime package and cannot call it directly
                final Constructor<ClassLoaderFinder> constructor = ClassLoaderFinder.class
                        .getDeclaredConstructor(ScanSpec.class, CallStackInfo.class, LogNode.class);
                constructor.setAccessible(true);
                // The call stack has to be read here, in the class that the child classloader loaded
                final ClassLoaderFinder classLoaderFinder = constructor.newInstance(new ScanSpec(),
                        CallStackInfo.read(new ReflectionUtils(), null), null);
                return Arrays.asList(classLoaderFinder.getContextClassLoaders());
            } catch (final ReflectiveOperationException e) {
                throw new IllegalArgumentException("Could not call ClassLoaderFinder", e);
            }
        }
    }

    /**
     * A classloader found on the call stack is ordered before its own ancestors, even though an ancestor is the
     * thread context classloader, which is otherwise the first classloader to be tried.
     *
     * @throws Exception
     *             if the child classloader could not be set up
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aClassLoaderIsOrderedBeforeItsOwnAncestors() throws Exception {
        final ClassLoader parent = ClassLoaderFinderOrderTest.class.getClassLoader();
        // The bug this tests for only shows up when an ancestor of the calling code's classloader is one of the
        // classloaders that are looked for before the call stack is read
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(parent);
        final URL testClasses = ClassLoaderFinderOrderTest.class.getProtectionDomain().getCodeSource()
                .getLocation();
        final SelfFirstLoader child = new SelfFirstLoader(new URL[] { testClasses }, parent);
        final List<ClassLoader> classLoaderOrder;
        try {
            final Class<?> driverClass = child.loadClass(Driver.class.getName());
            assertThat(driverClass.getClassLoader()).isSameAs(child);
            classLoaderOrder = ((Supplier<List<ClassLoader>>) driverClass.getDeclaredConstructor().newInstance())
                    .get();
        } finally {
            child.close();
        }

        assertThat(classLoaderOrder).contains(child, parent);
        assertThat(classLoaderOrder.indexOf(child)).isLessThan(classLoaderOrder.indexOf(parent));
    }

    /**
     * The thread context classloader is tried first, since it is how a thread overrides the classloaders it would
     * otherwise use -- even when it has fewer ancestors than a classloader it is unrelated to.
     *
     * @throws Exception
     *             if the classloader could not be closed
     */
    @Test
    public void theContextClassLoaderIsTriedBeforeAnUnrelatedClassLoaderWithMoreAncestors() throws Exception {
        final Thread thread = Thread.currentThread();
        final ClassLoader previousContextClassLoader = thread.getContextClassLoader();
        // A classloader with no parent other than the bootstrap classloader, which is unrelated to the classloader
        // that loaded ClassGraph, and has fewer ancestors than it
        final URLClassLoader contextClassLoader = new URLClassLoader(new URL[0], null);
        try {
            thread.setContextClassLoader(contextClassLoader);
            final ClassLoader[] classLoaders = new ClassLoaderFinder(new ScanSpec(),
                    CallStackInfo.read(new ReflectionUtils(), null), null).getContextClassLoaders();
            assertThat(classLoaders[0]).isSameAs(contextClassLoader);
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
            contextClassLoader.close();
        }
    }
}
