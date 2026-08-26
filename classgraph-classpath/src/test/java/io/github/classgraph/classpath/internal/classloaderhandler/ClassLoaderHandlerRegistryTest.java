package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.classgraph.classpath.ClassLoaderHandler;

/** The package roots and lib dirs that each {@link ClassLoaderHandler} declares. */
class ClassLoaderHandlerRegistryTest {
    /**
     * Every registered handler, including the fallback handler.
     *
     * @return every registered handler.
     */
    static Stream<ClassLoaderHandler> registeredHandlers() {
        return Stream.concat(ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS.stream(),
                Stream.of(ClassLoaderHandlerRegistry.FALLBACK_HANDLER));
    }

    /**
     * Find the registered instance of a {@link ClassLoaderHandler} class.
     *
     * @param handlerClass
     *            the {@link ClassLoaderHandler} class.
     * @return the registered handler.
     */
    private static ClassLoaderHandler handlerOf(final Class<? extends ClassLoaderHandler> handlerClass) {
        return registeredHandlers().filter(handler -> handler.getClass() == handlerClass).findFirst().orElseThrow();
    }

    /**
     * A handler declares a package root or lib dir only if the classloader it handles goes looking for classes or
     * jarfiles in a dir of that name that was never listed as a classpath element. Every other classloader loads
     * only from the classpath elements it was given, as {@link java.net.URLClassLoader} does, so its handler
     * declares nothing.
     *
     * @param handler
     *            the registered handler.
     */
    @ParameterizedTest
    @MethodSource("registeredHandlers")
    void onlyTheHandlersOfClassLoadersThatLookInFixedDirsDeclarePrefixes(final ClassLoaderHandler handler) {
        final var handlerClass = handler.getClass();
        if (handlerClass != TomcatWebappClassLoaderBaseHandler.class
                && handlerClass != UnoOneJarClassLoaderHandler.class) {
            assertThat(handler.getPackageRootPrefixes()).as(handlerClass.getSimpleName()).isEmpty();
            assertThat(handler.getLibDirPrefixes()).as(handlerClass.getSimpleName()).isEmpty();
        }
    }

    /** Every prefix ends in a slash, so that it cannot match a prefix of a longer directory name. */
    @Test
    void everyPrefixEndsInASlash() {
        registeredHandlers().forEach(handler -> assertThat(
                Stream.concat(handler.getPackageRootPrefixes().stream(), handler.getLibDirPrefixes().stream()))
                .allMatch(prefix -> prefix.endsWith("/")));
    }

    /** Catalina serves a webapp's own classes and jarfiles from the two fixed dirs of the war layout. */
    @Test
    void tomcatDeclaresTheWebappClassesAndLibDirs() {
        final var handler = handlerOf(TomcatWebappClassLoaderBaseHandler.class);
        assertThat(handler.getPackageRootPrefixes()).containsExactly("WEB-INF/classes/");
        assertThat(handler.getLibDirPrefixes()).containsExactly("WEB-INF/lib/");
    }

    /**
     * The built-in handlers are listed in alphabetical order. Their order does not affect the result of a scan --
     * when several handlers can handle the same classloader, only the ones that handle the most specific
     * classloader class are used -- so alphabetical order is simply the order that makes a handler easiest to find
     * in the list, and to check the presence of.
     */
    @Test
    void theBuiltInHandlersAreInAlphabeticalOrder() {
        assertThat(ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS.stream()
                .map(handler -> handler.getClass().getSimpleName()).toList())
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    /** An Uno-Jar or One-JAR executable jar puts the jar it launches in {@code "main/"} and its deps in "lib/". */
    @Test
    void unoOneJarDeclaresItsLaunchedJarAndLibDirs() {
        assertThat(handlerOf(UnoOneJarClassLoaderHandler.class).getLibDirPrefixes()).contains("lib/", "main/");
    }
}
