package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;

/** The package roots and lib dirs that each {@link ClassLoaderHandler} declares reach its registry entry. */
class ClassLoaderHandlerRegistryTest {
    /**
     * Every registry entry, including the fallback entry.
     *
     * @return every registry entry.
     */
    static Stream<ClassLoaderHandlerRegistryEntry> registryEntries() {
        return Stream.concat(ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS.stream(),
                Stream.of(ClassLoaderHandlerRegistry.FALLBACK_HANDLER));
    }

    /**
     * Find the registry entry of a {@link ClassLoaderHandler} class.
     *
     * @param handlerClass
     *            the {@link ClassLoaderHandler} class.
     * @return the registry entry.
     */
    private static ClassLoaderHandlerRegistryEntry entryOf(final Class<? extends ClassLoaderHandler> handlerClass) {
        return registryEntries().filter(entry -> entry.classLoaderHandler.getClass() == handlerClass).findFirst()
                .orElseThrow();
    }

    /**
     * A registry entry reports the prefixes its handler declares. The entries are constructed while
     * {@link ClassLoaderHandlerRegistry} is still initializing, so an entry that read the prefixes in its
     * constructor would read them before they had been assigned.
     *
     * @param entry
     *            the registry entry.
     */
    // #119
    @ParameterizedTest
    @MethodSource("registryEntries")
    void eachEntryReportsThePrefixesItsHandlerDeclares(final ClassLoaderHandlerRegistryEntry entry) {
        assertThat(entry.getPackageRootPrefixes()).isEqualTo(entry.classLoaderHandler.getPackageRootPrefixes());
        assertThat(entry.getLibDirPrefixes()).isEqualTo(entry.classLoaderHandler.getLibDirPrefixes());
    }

    /**
     * A handler declares a package root or lib dir only if the classloader it handles goes looking for classes or
     * jarfiles in a dir of that name that was never listed as a classpath element. Every other classloader loads
     * only from the classpath elements it was given, as {@link java.net.URLClassLoader} does, so its handler
     * declares nothing.
     *
     * @param entry
     *            the registry entry.
     */
    @ParameterizedTest
    @MethodSource("registryEntries")
    void onlyTheHandlersOfClassLoadersThatLookInFixedDirsDeclarePrefixes(
            final ClassLoaderHandlerRegistryEntry entry) {
        final var handlerClass = entry.classLoaderHandler.getClass();
        if (handlerClass != TomcatWebappClassLoaderBaseHandler.class
                && handlerClass != UnoOneJarClassLoaderHandler.class) {
            assertThat(entry.getPackageRootPrefixes()).as(handlerClass.getSimpleName()).isEmpty();
            assertThat(entry.getLibDirPrefixes()).as(handlerClass.getSimpleName()).isEmpty();
        }
    }

    /** Every prefix ends in a slash, so that it cannot match a prefix of a longer directory name. */
    @Test
    void everyPrefixEndsInASlash() {
        registryEntries().forEach(entry -> assertThat(
                Stream.concat(entry.getPackageRootPrefixes().stream(), entry.getLibDirPrefixes().stream()))
                .allMatch(prefix -> prefix.endsWith("/")));
    }

    /** Catalina serves a webapp's own classes and jarfiles from the two fixed dirs of the war layout. */
    @Test
    void tomcatDeclaresTheWebappClassesAndLibDirs() {
        final var entry = entryOf(TomcatWebappClassLoaderBaseHandler.class);
        assertThat(entry.getPackageRootPrefixes()).containsExactly("WEB-INF/classes/");
        assertThat(entry.getLibDirPrefixes()).containsExactly("WEB-INF/lib/");
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
                .map(entry -> entry.classLoaderHandler.getClass().getSimpleName()).toList())
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    /** An Uno-Jar or One-JAR executable jar puts the jar it launches in {@code "main/"} and its deps in "lib/". */
    @Test
    void unoOneJarDeclaresItsLaunchedJarAndLibDirs() {
        assertThat(entryOf(UnoOneJarClassLoaderHandler.class).getLibDirPrefixes()).contains("lib/", "main/");
    }
}
