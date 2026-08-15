package nonapi.io.github.classgraph.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;

/** Tests that the package root prefixes each {@link ClassLoaderHandler} declares reach its registry entry. */
public class ClassLoaderHandlerRegistryTest {
    /**
     * Every registry entry, including the fallback entry.
     *
     * @return every registry entry.
     */
    private static List<ClassLoaderHandlerRegistryEntry> registryEntries() {
        final List<ClassLoaderHandlerRegistryEntry> entries = new ArrayList<>(
                ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS);
        entries.add(ClassLoaderHandlerRegistry.FALLBACK_HANDLER);
        return entries;
    }

    /**
     * A registry entry reports the package root prefixes its handler declares. The entries are constructed by the
     * initializer of {@code CLASS_LOADER_HANDLERS}, which runs before the prefix constants declared below it are
     * assigned, so an entry that read the prefixes in its constructor would read null for every handler that
     * returns one of those constants.
     */
    @Test
    public void eachEntryReportsThePrefixesItsHandlerDeclares() {
        for (final ClassLoaderHandlerRegistryEntry entry : registryEntries()) {
            assertThat(entry.getPackageRootPrefixes()).as(entry.getHandlerName())
                    .isEqualTo(entry.classLoaderHandler.getPackageRootPrefixes());
        }
    }

    /** Every package root prefix ends in a slash, so that it cannot match a prefix of a longer directory name. */
    @Test
    public void everyPrefixEndsInASlash() {
        for (final ClassLoaderHandlerRegistryEntry entry : registryEntries()) {
            assertThat(entry.getPackageRootPrefixes()).as(entry.getHandlerName())
                    .allSatisfy(prefix -> assertThat(prefix).endsWith("/"));
        }
    }
}
