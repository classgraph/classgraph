package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * Two {@link ClassLoader} instances are two classloaders, and each of them can load a different set of classes,
 * whatever either of them says about being equal to the other. TomEE makes an instance of
 * {@code CxfContainerClassLoader} equal to the instance of {@code TomEEWebappClassLoader} that it delegates to
 * (#515), so a classloader set that deduplicates with {@code equals()} drops whichever of the two it sees second,
 * and the classpath entries of the dropped classloader are never scanned.
 */
class ClassLoaderIdentityTest {
    /**
     * A classloader that claims to be equal to the classloader it delegates to, and that shares its hash code, in
     * the way that TomEE's {@code CxfContainerClassLoader} does.
     *
     * <p>
     * It loads {@link CallStackProbe} itself, rather than delegating, so that a frame of the probe names this
     * classloader rather than the classloader that the test itself was loaded by.
     */
    private static final class ImpersonatingClassLoader extends ClassLoader {
        /** The classloader that this classloader delegates to, and claims to be equal to. */
        private final ClassLoader impersonated;

        /**
         * Constructor.
         *
         * @param impersonated
         *            the classloader to delegate to and claim to be equal to.
         */
        ImpersonatingClassLoader(final ClassLoader impersonated) {
            super(impersonated);
            this.impersonated = impersonated;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.equals(CallStackProbe.class.getName())) {
                return super.loadClass(name, resolve);
            }
            // Load the probe class here rather than delegating, so that its frame names this classloader
            synchronized (getClassLoadingLock(name)) {
                var probeClass = findLoadedClass(name);
                if (probeClass == null) {
                    try (var inputStream = impersonated.getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (inputStream == null) {
                            throw new ClassNotFoundException(name);
                        }
                        final var classfileBytes = inputStream.readAllBytes();
                        probeClass = defineClass(name, classfileBytes, 0, classfileBytes.length);
                    } catch (final IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(probeClass);
                }
                return probeClass;
            }
        }

        @Override
        public boolean equals(final Object obj) {
            return obj == this || obj == impersonated;
        }

        @Override
        public int hashCode() {
            return impersonated.hashCode();
        }
    }

    /**
     * Determine whether a collection holds a given object, comparing by reference, since the whole point of the
     * classloader under test is that its {@code equals()} method cannot be trusted.
     *
     * @param collection
     *            the collection.
     * @param object
     *            the object to look for.
     * @return true if the very same object is in the collection.
     */
    private static boolean containsTheSameObject(final Collection<?> collection, final Object object) {
        return collection.stream().anyMatch(element -> element == object);
    }

    /**
     * Read the call stack from a frame of a class that the impersonating classloader loaded, so that the
     * impersonating classloader is reached after the classloader it claims to be equal to.
     *
     * @param impersonatingClassLoader
     *            the classloader to load the probe class with.
     * @return the call stack info.
     * @throws Exception
     *             if the probe class could not be loaded.
     */
    private static CallStackInfo readCallStackThroughImpersonatingClassLoader(
            final ImpersonatingClassLoader impersonatingClassLoader) throws Exception {
        final var probeClass = impersonatingClassLoader.loadClass(CallStackProbe.class.getName());
        assertThat(probeClass.getClassLoader()).isSameAs(impersonatingClassLoader);
        final var probe = (Supplier<?>) probeClass.getDeclaredConstructor().newInstance();
        return (CallStackInfo) probe.get();
    }

    /**
     * A classloader in the call stack that claims to be equal to a classloader that is already in the call stack is
     * still listed, since it is still a classloader that can load classes the other one cannot.
     *
     * @throws Exception
     *             if the probe class could not be loaded.
     */
    // #515
    @Test
    void aClassLoaderThatClaimsToEqualAnotherIsStillListedInTheCallStack() throws Exception {
        final var impersonatingClassLoader = new ImpersonatingClassLoader(
                ClassLoaderIdentityTest.class.getClassLoader());
        final var callStackInfo = readCallStackThroughImpersonatingClassLoader(impersonatingClassLoader);

        assertThat(containsTheSameObject(callStackInfo.getClassLoaders(), impersonatingClassLoader))
                .as("the impersonating classloader is in the call stack classloaders").isTrue();
        assertThat(containsTheSameObject(callStackInfo.getClassLoaders(), impersonatingClassLoader.getParent()))
                .as("the classloader it claims to be equal to is in the call stack classloaders").isTrue();
    }

    /**
     * A classloader that claims to be equal to another classloader in the environment is still one of the
     * classloaders that the scan searches.
     *
     * @throws Exception
     *             if the probe class could not be loaded.
     */
    // #515
    @Test
    void aClassLoaderThatClaimsToEqualAnotherIsStillSearched() throws Exception {
        final var impersonatingClassLoader = new ImpersonatingClassLoader(
                ClassLoaderIdentityTest.class.getClassLoader());
        final var callStackInfo = readCallStackThroughImpersonatingClassLoader(impersonatingClassLoader);
        final var contextClassLoaders = List
                .of(new ClassLoaderFinder(callStackInfo, /* log = */ null).getContextClassLoaders());

        assertThat(containsTheSameObject(contextClassLoaders, impersonatingClassLoader))
                .as("the impersonating classloader is searched").isTrue();
        assertThat(containsTheSameObject(contextClassLoaders, impersonatingClassLoader.getParent()))
                .as("the classloader it claims to be equal to is searched").isTrue();
    }
}
