package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * All accept/reject criteria and {@code getResourcesMatchingWildcard()} share a single glob syntax: {@code '*'}
 * matches zero or more characters within one package or path segment, {@code "**"} (forming a complete segment)
 * matches zero or more whole segments, {@code '?'} matches exactly one character other than the separator, and
 * every other character is matched literally.
 */
public class GlobTest {
    /** The package containing the class name glob fixtures. */
    private static final String GLOBS = "io.github.classgraph.features.globs";

    /** A fixture directly in {@link #GLOBS}. */
    private static final String SHALLOW = GLOBS + ".ShallowGlobSuffix";

    /** A fixture one package below {@link #GLOBS}. */
    private static final String NESTED = GLOBS + ".nested.NestedGlobSuffix";

    /** A fixture two packages below {@link #GLOBS}. */
    private static final String DEEP = GLOBS + ".nested.deep.DeepGlobSuffix";

    /** Scan with the given class name globs, and return the class names found. */
    private static Iterable<String> classesMatching(final String... classNameGlobs) {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath().acceptClasses(classNameGlobs)
                .scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /** Scan {@code /globtest}, and return the resource paths matching a wildcard. */
    private static Iterable<String> resourcesMatching(final String wildcard) {
        try (var scanResult = new ClassGraph().enableClasspath().acceptPaths("globtest").scan()) {
            return scanResult.getResourcesMatchingWildcard(wildcard).getPaths();
        }
    }

    /** In a class name glob, {@code '*'} matches within a single segment. */
    @Test
    public void singleAsteriskMatchesOneSegmentOfAClassName() {
        assertThat(classesMatching(GLOBS + ".*.*Suffix")).containsExactly(NESTED);
    }

    /** In a class name glob, {@code "**"} matches zero or more whole packages. */
    @Test
    public void doubleAsteriskMatchesAnyNumberOfPackages() {
        assertThat(classesMatching(GLOBS + ".**.*Suffix")).containsExactlyInAnyOrder(SHALLOW, NESTED, DEEP);
    }

    /**
     * A character that is a regexp metacharacter but not a glob wildcard is matched literally. {@code '$'} occurs
     * in the binary name of every inner class, and was previously copied into the regexp unescaped, where it acted
     * as an end-of-input anchor and matched nothing.
     */
    @Test
    public void regexpMetacharactersInAClassNameGlobAreMatchedLiterally() {
        assertThat(classesMatching(SHALLOW + "$Inn?r")).containsExactly(SHALLOW + "$Inner");
    }

    /** In a resource wildcard, {@code '*'} does not span {@code '/'}. */
    @Test
    public void singleAsteriskMatchesOnePathSegment() {
        assertThat(resourcesMatching("globtest/*.txt")).containsExactly("globtest/a.txt");
    }

    /**
     * In a resource wildcard, {@code "**"} matches zero or more whole path segments -- including zero, so
     * {@code "globtest/**&#47;a.txt"} matches {@code globtest/a.txt}.
     */
    @Test
    public void doubleAsteriskMatchesAnyNumberOfPathSegments() {
        assertThat(resourcesMatching("globtest/**/*.txt")).containsExactlyInAnyOrder("globtest/a.txt",
                "globtest/sub/b.txt", "globtest/sub/deep/c.txt");
    }

    /** In a resource wildcard, {@code '?'} matches exactly one character. */
    @Test
    public void questionMarkMatchesOneCharacter() {
        assertThat(resourcesMatching("globtest/sub/?.txt")).containsExactly("globtest/sub/b.txt");
    }

    /** {@code "**"} must form a complete segment, rather than part of one. */
    @Test
    public void doubleAsteriskMustFormAWholeSegment() {
        assertThatIllegalArgumentException().isThrownBy(() -> resourcesMatching("globtest/**.txt"))
                .withMessageContaining("complete segment");
    }
}
