package io.github.classgraph.issues.issue884;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import nonapi.io.github.classgraph.scanspec.AcceptReject.AcceptRejectPrefix;

/**
 * A reject criterion containing a glob wildcard was not applied to the
 * sub-packages of a matched package (#884).
 *
 * <p>
 * {@code rejectPackages("javax.swing.*")} adds {@code "javax.swing.*."} as a
 * rejected <i>prefix</i>, which is how "and everything below a matched package"
 * is implemented. {@link AcceptRejectPrefix} stores a prefix containing a
 * wildcard as a regexp rather than as a literal {@code String#startsWith}
 * prefix, but {@link AcceptRejectPrefix#isRejected(String)} only tested the
 * literal prefixes -- so the glob prefixes it stored were never consulted, and
 * the sub-packages of a glob-matched package were scanned anyway.
 *
 * <p>
 * Recursive directory scanning prunes a rejected directory before descending
 * into it, so this was only visible where classfile entries are enumerated flat
 * rather than walked as a tree, as they are for modules.
 */
public class Issue884Test {

    /**
     * A glob prefix must be applied by
     * {@link AcceptRejectPrefix#isRejected(String)}, not just stored.
     */
    @Test
    public void globRejectPrefixIsApplied() {
        final var acceptReject = new AcceptRejectPrefix('.');
        acceptReject.addToReject("javax.swing.*.");
        assertThat(acceptReject.isRejected("javax.swing.plaf.basic")).isTrue();
        assertThat(acceptReject.isRejected("javax.swing.text.html.parser")).isTrue();
        // The wildcard matches within a single segment only, so "javax.swing" itself is
        // not matched
        assertThat(acceptReject.isRejected("javax.swing")).isFalse();
        // ... and nor is an unrelated package
        assertThat(acceptReject.isRejected("javax.sound.midi")).isFalse();
    }

    /**
     * End-to-end: {@code rejectPackages("javax.swing.*")} must reject every
     * sub-package of {@code javax.swing}, leaving only {@code javax.swing} itself.
     * {@code javax.swing} is used because it is in the system class library on
     * every supported JDK, and is reached by flat enumeration rather than by
     * directory recursion.
     */
    @Test
    public void globRejectIsRecursiveWhenEnumeratingFlatly() {
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules().acceptPackages("javax.swing")
                .rejectPackages("javax.swing.*").scan()) {
            final TreeSet<String> packageNames = new TreeSet<>();
            for (final ClassInfo classInfo : scanResult.getAllClasses()) {
                packageNames.add(classInfo.getPackageName());
            }
            assertThat(packageNames).containsExactly("javax.swing");
        }
    }
}
