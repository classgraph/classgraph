package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.utils.AcceptReject.AcceptRejectLeafname;
import io.github.classgraph.base.internal.utils.AcceptReject.AcceptRejectPrefix;
import io.github.classgraph.base.internal.utils.AcceptReject.AcceptRejectWholeString;

/** Tests for {@link AcceptReject} and its three subclasses. */
public class AcceptRejectTest {
    /** Converting a glob to a regexp. */
    @Nested
    class GlobToPattern {
        /**
         * Match a glob against a string.
         *
         * @param glob
         *            the glob.
         * @param str
         *            the string to match against it.
         * @return whether the string matches the glob.
         */
        private boolean matches(final String glob, final String str) {
            return AcceptReject.globToPattern(glob, '.', /* prefixMatch = */ false).matcher(str).matches();
        }

        /** A single {@code '*'} matches zero or more characters, but does not span a separator. */
        @Test
        public void aSingleStarDoesNotSpanASeparator() {
            assertThat(matches("com.*.impl", "com.a.impl")).isTrue();
            assertThat(matches("com.*.impl", "com..impl")).isTrue();
            assertThat(matches("com.*.impl", "com.a.b.impl")).isFalse();
            assertThat(matches("com.x*", "com.xyz")).isTrue();
            assertThat(matches("com.x*", "com.xyz.abc")).isFalse();
        }

        /** A {@code '?'} matches exactly one character, which is not a separator. */
        @Test
        public void aQuestionMarkMatchesOneNonSeparatorCharacter() {
            assertThat(matches("com.a?c", "com.abc")).isTrue();
            assertThat(matches("com.a?c", "com.ac")).isFalse();
            assertThat(matches("com.a?c", "com.abbc")).isFalse();
            assertThat(matches("com?abc", "com.abc")).isFalse();
        }

        /** {@code "**"} matches zero or more whole segments, including none at all. */
        @Test
        public void aDoubleStarMatchesZeroOrMoreWholeSegments() {
            assertThat(matches("com.**.impl", "com.impl")).isTrue();
            assertThat(matches("com.**.impl", "com.a.impl")).isTrue();
            assertThat(matches("com.**.impl", "com.a.b.impl")).isTrue();
            assertThat(matches("com.**.impl", "org.a.impl")).isFalse();
            // A "**" that is the whole glob matches anything at all
            assertThat(matches("**", "anything.at.all")).isTrue();
            assertThat(matches("**", "")).isTrue();
        }

        /** A trailing {@code "**"} matches the preceding segments plus zero or more segments below them. */
        @Test
        public void aTrailingDoubleStarMatchesEverythingBelow() {
            // (Callers normally strip a trailing "**" before the glob reaches globToPattern, since "and everything
            // below" is already what the recursive accept and reject methods do)
            assertThat(matches("com.a.**", "com.a")).isTrue();
            assertThat(matches("com.a.**", "com.a.b")).isTrue();
            assertThat(matches("com.a.**", "com.a.b.c")).isTrue();
            assertThat(matches("com.a.**", "com.ab")).isFalse();
        }

        /** {@code "**"} that does not form a complete segment is rejected, rather than silently misinterpreted. */
        @Test
        public void aDoubleStarThatIsNotAWholeSegmentIsRejected() {
            for (final String glob : new String[] { "com.a**b.impl", "com.a**.impl", "com.**b.impl", "com.a**",
                    "**b" }) {
                assertThatThrownBy(() -> AcceptReject.globToPattern(glob, '.', false)).as(glob)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("may only be used as a complete segment");
            }
        }

        /** Regexp metacharacters in a glob are matched literally, rather than being interpreted. */
        @Test
        public void regexpMetacharactersAreMatchedLiterally() {
            assertThat(matches("com.a+b", "com.a+b")).isTrue();
            assertThat(matches("com.a+b", "com.aab")).isFalse();
            assertThat(matches("com.a|b", "com.a|b")).isTrue();
            assertThat(matches("com.a|b", "com.a")).isFalse();
            assertThat(matches("com.(a)", "com.(a)")).isTrue();
            assertThat(matches("com.[ab]", "com.[ab]")).isTrue();
            assertThat(matches("com.[ab]", "com.a")).isFalse();
            assertThat(matches("com.a$", "com.a$")).isTrue();
            assertThat(matches("com.a\\b", "com.a\\b")).isTrue();
        }

        /**
         * The separator is matched literally too, even when it is itself a regexp metacharacter, as {@code '.'} is.
         */
        @Test
        public void theSeparatorIsMatchedLiterally() {
            // '.' is a regexp metacharacter, so an unescaped separator would match any character here
            assertThat(matches("com.*.impl", "comXaXimpl")).isFalse();
            // '/' is not a metacharacter, and needs no escaping
            final var slashSeparated = AcceptReject.globToPattern("com/*/impl", '/', /* prefixMatch = */ false);
            assertThat(slashSeparated.matcher("com/a/impl").matches()).isTrue();
            assertThat(slashSeparated.matcher("com/a/b/impl").matches()).isFalse();
        }

        /** A prefix-match pattern matches any string that starts with a string matching the glob. */
        @Test
        public void aPrefixMatchPatternMatchesAnythingBelowTheGlob() {
            final var pattern = AcceptReject.globToPattern("com.*.impl", '.', /* prefixMatch = */ true);
            assertThat(pattern.matcher("com.a.impl").matches()).isTrue();
            assertThat(pattern.matcher("com.a.impl.sub").matches()).isTrue();
            assertThat(pattern.matcher("com.a.other").matches()).isFalse();
        }
    }

    /** A trailing {@code "**"} segment is stripped, since it means "and everything below". */
    @Test
    public void aTrailingDoubleGlobSegmentIsStripped() {
        assertThat(AcceptReject.stripTrailingDoubleGlob("com.a.**", '.')).isEqualTo("com.a");
        assertThat(AcceptReject.stripTrailingDoubleGlob("com/a/**", '/')).isEqualTo("com/a");
        assertThat(AcceptReject.stripTrailingDoubleGlob("**", '.')).isEmpty();
        // "**" anywhere else has its own meaning, and is left alone
        assertThat(AcceptReject.stripTrailingDoubleGlob("com.**.impl", '.')).isEqualTo("com.**.impl");
        assertThat(AcceptReject.stripTrailingDoubleGlob("com.a", '.')).isEqualTo("com.a");
        // A trailing "**" that is not a whole segment is not a trailing "**" segment
        assertThat(AcceptReject.stripTrailingDoubleGlob("com.a**", '.')).isEqualTo("com.a**");
    }

    /** A string is only compiled to a pattern if it contains a wildcard. */
    @Test
    public void wildcardsAreDetected() {
        assertThat(AcceptReject.containsWildcard("com.a*")).isTrue();
        assertThat(AcceptReject.containsWildcard("com.a?")).isTrue();
        assertThat(AcceptReject.containsWildcard("com.a")).isFalse();
        assertThat(AcceptReject.containsWildcard("")).isFalse();
    }

    /** Paths and package names are normalized before they are matched, so that they can be compared as strings. */
    @Test
    public void pathsAndPackageNamesAreNormalized() {
        // The leading separators are stripped here, and the trailing one by FastPathResolver
        assertThat(AcceptReject.normalizePath("/com/a/")).isEqualTo("com/a");
        assertThat(AcceptReject.normalizePath("///com/a")).isEqualTo("com/a");
        assertThat(AcceptReject.normalizePath("com/a")).isEqualTo("com/a");
        assertThat(AcceptReject.normalizePackageOrClassName(".com.a.")).isEqualTo("com.a");
        assertThat(AcceptReject.normalizePackageOrClassName("com.a.B")).isEqualTo("com.a.B");
        assertThat(AcceptReject.pathToPackageName("com/a/B")).isEqualTo("com.a.B");
    }

    /** Prefix accept/reject, as used for package names and directory paths. */
    @Nested
    class Prefix {
        /**
         * Create a prefix accept/reject criterion with the given accepts and rejects, with its prefixes sorted, as
         * they are once the spec that holds it has been built.
         *
         * @param accepts
         *            the strings to accept.
         * @param rejects
         *            the strings to reject.
         * @return the criterion.
         */
        private AcceptRejectPrefix criterion(final String[] accepts, final String[] rejects) {
            final var acceptReject = new AcceptRejectPrefix('.');
            for (final String accept : accepts) {
                acceptReject.addToAccept(accept);
            }
            for (final String reject : rejects) {
                acceptReject.addToReject(reject);
            }
            // The accepted prefixes are only moved into the list that is matched against by sortPrefixes(), so a
            // criterion that has not been sorted accepts everything
            acceptReject.sortPrefixes();
            return acceptReject;
        }

        /** A string is accepted if it starts with an accepted prefix. */
        @Test
        public void aStringIsAcceptedIfItStartsWithAnAcceptedPrefix() {
            final var acceptReject = criterion(new String[] { "com.a." }, new String[0]);
            assertThat(acceptReject.isAccepted("com.a.B")).isTrue();
            assertThat(acceptReject.isAccepted("com.a.b.C")).isTrue();
            assertThat(acceptReject.isAccepted("com.b.C")).isFalse();
        }

        /** With nothing accepted, everything is accepted; with nothing rejected, nothing is rejected. */
        @Test
        public void anEmptyCriterionAcceptsEverything() {
            final var acceptReject = criterion(new String[0], new String[0]);
            assertThat(acceptReject.isAccepted("com.a.B")).isTrue();
            assertThat(acceptReject.isRejected("com.a.B")).isFalse();
            assertThat(acceptReject.isAcceptedAndNotRejected("com.a.B")).isTrue();
            assertThat(acceptReject.acceptAndRejectAreEmpty()).isTrue();
            // "specifically accepted" is false when nothing was accepted, even though isAccepted() is true
            assertThat(acceptReject.isSpecificallyAccepted("com.a.B")).isFalse();
            assertThat(acceptReject.isSpecificallyAcceptedAndNotRejected("com.a.B")).isFalse();
        }

        /** A rejected prefix wins over an accepted one, so that a sub-package can be excluded. */
        @Test
        public void aRejectedPrefixOverridesAnAcceptedOne() {
            final var acceptReject = criterion(new String[] { "com." }, new String[] { "com.a.internal." });
            assertThat(acceptReject.isAcceptedAndNotRejected("com.a.B")).isTrue();
            assertThat(acceptReject.isAcceptedAndNotRejected("com.a.internal.B")).isFalse();
            assertThat(acceptReject.isRejected("com.a.internal.B")).isTrue();
            assertThat(acceptReject.isRejected("com.a.B")).isFalse();
        }

        /** An accept containing a wildcard is recursive into sub-packages, just as a literal accept is. */
        // #870
        @Test
        public void aGlobAcceptIsRecursive() {
            final var acceptReject = criterion(new String[] { "eu.*.domain." }, new String[0]);
            assertThat(acceptReject.isAccepted("eu.core.domain.")).isTrue();
            assertThat(acceptReject.isAccepted("eu.core.domain.sub.C")).isTrue();
            assertThat(acceptReject.isAccepted("eu.core.other.")).isFalse();
        }

        /** A reject containing a wildcard applies to sub-packages of what it matches, not only to the match. */
        // #884
        @Test
        public void aGlobRejectIsRecursive() {
            final var acceptReject = criterion(new String[0], new String[] { "javax.swing.*" });
            assertThat(acceptReject.isRejected("javax.swing.plaf")).isTrue();
            assertThat(acceptReject.isRejected("javax.swing.plaf.basic")).isTrue();
            assertThat(acceptReject.isRejected("javax.print")).isFalse();
            assertThat(acceptReject.isAcceptedAndNotRejected("javax.swing.plaf.basic")).isFalse();
        }

        /** Asking whether a string is a prefix of an accepted string is meaningless for a prefix criterion. */
        @Test
        public void prefixOfAPrefixIsRejectedAsMeaningless() {
            assertThatThrownBy(() -> new AcceptRejectPrefix('.').acceptHasPrefix("com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Can only find prefixes of whole strings");
        }

        /**
         * A criterion knows whether anything was accepted or rejected, before and after its prefixes are sorted.
         */
        @Test
        public void emptinessIsReportedBeforeAndAfterSorting() {
            final var acceptReject = new AcceptRejectPrefix('.');
            assertThat(acceptReject.acceptAndRejectAreEmpty()).isTrue();
            acceptReject.addToAccept("com.a.");
            // The accept is not empty even though sortPrefixes() has not been called yet
            assertThat(acceptReject.acceptIsEmpty()).isFalse();
            assertThat(acceptReject.rejectIsEmpty()).isTrue();
            acceptReject.sortPrefixes();
            assertThat(acceptReject.acceptIsEmpty()).isFalse();
            acceptReject.addToReject("com.a.internal.");
            assertThat(acceptReject.rejectIsEmpty()).isFalse();
        }

        /** A glob accept counts as an accept, so a criterion holding only a glob is not empty. */
        @Test
        public void aGlobCriterionIsNotEmpty() {
            final var acceptReject = new AcceptRejectPrefix('.');
            acceptReject.addToAccept("com.*.impl.");
            assertThat(acceptReject.acceptIsEmpty()).isFalse();
            final var rejectOnly = new AcceptRejectPrefix('.');
            rejectOnly.addToReject("com.*.impl.");
            assertThat(rejectOnly.rejectIsEmpty()).isFalse();
        }
    }

    /** Whole-string accept/reject, as used for class names and resource paths. */
    @Nested
    class WholeString {
        /** A string is accepted only if it matches an accepted string exactly. */
        @Test
        public void onlyAnExactMatchIsAccepted() {
            final var acceptReject = new AcceptRejectWholeString('.');
            acceptReject.addToAccept("com.a.B");
            assertThat(acceptReject.isAccepted("com.a.B")).isTrue();
            assertThat(acceptReject.isAccepted("com.a.BC")).isFalse();
            assertThat(acceptReject.isAccepted("com.a")).isFalse();
        }

        /** Class names and resource paths are case-sensitive, so a whole-string criterion is matched by case. */
        @Test
        public void aWholeStringIsMatchedByCase() {
            final var acceptReject = new AcceptRejectWholeString('.');
            acceptReject.addToAccept("com.a.B");
            acceptReject.addToAccept("com.a.C*");
            assertThat(acceptReject.isAccepted("com.a.b")).isFalse();
            assertThat(acceptReject.isAccepted("com.a.cD")).isFalse();
        }

        /** A glob accept matches the whole string, rather than anything below it. */
        @Test
        public void aGlobMatchesTheWholeString() {
            final var acceptReject = new AcceptRejectWholeString('.');
            acceptReject.addToAccept("com.*.B");
            assertThat(acceptReject.isAccepted("com.a.B")).isTrue();
            assertThat(acceptReject.isAccepted("com.a.B.Inner")).isFalse();
        }

        /** A rejected string is rejected whether it was accepted or not. */
        @Test
        public void aRejectedStringIsNotAccepted() {
            final var acceptReject = new AcceptRejectWholeString('.');
            acceptReject.addToAccept("com.a.B");
            acceptReject.addToReject("com.a.B");
            assertThat(acceptReject.isAccepted("com.a.B")).isTrue();
            assertThat(acceptReject.isRejected("com.a.B")).isTrue();
            assertThat(acceptReject.isAcceptedAndNotRejected("com.a.B")).isFalse();
        }

        /**
         * Every parent of an accepted path is recorded as a prefix, so that a directory scan can tell whether
         * descending into a directory could still reach an accepted path.
         */
        // #338
        @Test
        public void everyParentOfAnAcceptedPathIsAPrefixOfIt() {
            final var acceptReject = new AcceptRejectWholeString('/');
            acceptReject.addToAccept("com/a/b/");
            assertThat(acceptReject.acceptHasPrefix("com/")).isTrue();
            assertThat(acceptReject.acceptHasPrefix("com/a/")).isTrue();
            assertThat(acceptReject.acceptHasPrefix("com/a/b/")).isTrue();
            // The root is always a prefix, so that scanning starts at all
            assertThat(acceptReject.acceptHasPrefix("")).isTrue();
            assertThat(acceptReject.acceptHasPrefix("/")).isTrue();
            assertThat(acceptReject.acceptHasPrefix("org/")).isFalse();
            assertThat(acceptReject.acceptHasPrefix("com/a/b/c/")).isFalse();
        }

        /**
         * A path prefix of an accepted glob that itself contains a wildcard is recorded as a pattern, since such
         * prefixes cannot be enumerated. Without this, a recursive scan would stop before reaching the accepted
         * path.
         */
        // #870, #643
        @Test
        public void aWildcardPathPrefixOfAnAcceptedGlobIsAPrefixOfIt() {
            final var acceptReject = new AcceptRejectWholeString('/');
            acceptReject.addToAccept("eu/*/domain/");
            // The literal prefix search stops at the first wildcard
            assertThat(acceptReject.acceptHasPrefix("eu/")).isTrue();
            // ... so the wildcard-containing prefix is matched as a pattern instead
            assertThat(acceptReject.acceptHasPrefix("eu/core/")).isTrue();
            assertThat(acceptReject.acceptHasPrefix("org/core/")).isFalse();
        }

        /**
         * A criterion with nothing accepted has no prefixes, and reports that nothing is a prefix of its accept.
         */
        @Test
        public void anEmptyCriterionHasNoPrefixes() {
            assertThat(new AcceptRejectWholeString('/').acceptHasPrefix("com/")).isFalse();
        }
    }

    /** Leafname accept/reject, as used for jarfile names. */
    @Nested
    class Leafname {
        /** Only the leafname of a path is matched, so that a jar can be named without naming its directory. */
        @Test
        public void onlyTheLeafnameIsMatched() {
            final var acceptReject = new AcceptRejectLeafname('/');
            acceptReject.addToAccept("some.jar");
            assertThat(acceptReject.isAccepted("some.jar")).isTrue();
            assertThat(acceptReject.isAccepted("/path/to/some.jar")).isTrue();
            assertThat(acceptReject.isAccepted("/path/to/other.jar")).isFalse();
        }

        /** The path of a criterion added as a whole path is reduced to its leafname too. */
        @Test
        public void anAddedPathIsReducedToItsLeafname() {
            final var acceptReject = new AcceptRejectLeafname('/');
            acceptReject.addToReject("/some/dir/rejected.jar");
            assertThat(acceptReject.isRejected("/another/dir/rejected.jar")).isTrue();
            assertThat(acceptReject.isRejected("/some/dir/kept.jar")).isFalse();
            assertThat(acceptReject.isAcceptedAndNotRejected("/another/dir/rejected.jar")).isFalse();
        }

        /**
         * A leafname is a filename, and two filenames differing only in case name the same file on a filesystem
         * that ignores case, so a leafname criterion is matched ignoring case.
         */
        @Test
        public void aLeafnameIsMatchedIgnoringCase() {
            final var acceptReject = new AcceptRejectLeafname('/');
            acceptReject.addToAccept("MyLib.jar");
            acceptReject.addToReject("BadLib.jar");
            assertThat(acceptReject.isAccepted("mylib.jar")).isTrue();
            assertThat(acceptReject.isAccepted("MYLIB.JAR")).isTrue();
            assertThat(acceptReject.isAccepted("/path/to/mylib.jar")).isTrue();
            assertThat(acceptReject.isAccepted("otherlib.jar")).isFalse();
            assertThat(acceptReject.isRejected("badlib.jar")).isTrue();
            assertThat(acceptReject.isAcceptedAndNotRejected("MYLIB.JAR")).isTrue();
        }

        /** A leafname glob is matched ignoring case too, so that globs and literals agree. */
        @Test
        public void aLeafnameGlobIsMatchedIgnoringCase() {
            final var acceptReject = new AcceptRejectLeafname('/');
            acceptReject.addToAccept("MyLib-*.jar");
            acceptReject.addToReject("BadLib-?.jar");
            assertThat(acceptReject.isAccepted("mylib-1.0.jar")).isTrue();
            assertThat(acceptReject.isAccepted("MYLIB-1.0.JAR")).isTrue();
            assertThat(acceptReject.isAccepted("otherlib-1.0.jar")).isFalse();
            assertThat(acceptReject.isRejected("badlib-2.jar")).isTrue();
        }

        /** A leafname criterion is printed with the spelling it was given, not the spelling it matched. */
        @Test
        public void aLeafnameCriterionIsPrintedAsGiven() {
            final var acceptReject = new AcceptRejectLeafname('/');
            acceptReject.addToAccept("MyLib.jar");
            acceptReject.addToReject("BadLib-*.jar");
            assertThat(acceptReject.isAccepted("mylib.jar")).isTrue();
            assertThat(acceptReject).hasToString("accept: [\"MyLib.jar\"]; rejectGlobs: [\"BadLib-*.jar\"]");
        }

        /** Asking whether a string is a prefix of an accepted leafname is meaningless. */
        @Test
        public void prefixOfALeafnameIsRejectedAsMeaningless() {
            assertThatThrownBy(() -> new AcceptRejectLeafname('/').acceptHasPrefix("some"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Can only find prefixes of whole strings");
        }
    }

    /** The criteria are printed as their accepts and rejects, so that a scan's log shows what it was given. */
    @Test
    public void theCriteriaArePrintedAsTheirAcceptsAndRejects() {
        final var acceptReject = new AcceptRejectPrefix('.');
        acceptReject.addToAccept("com.a.");
        acceptReject.addToAccept("com.*.impl.");
        acceptReject.addToReject("com.a.internal.");
        acceptReject.addToReject("com.*.gen.");
        acceptReject.sortPrefixes();

        assertThat(acceptReject).hasToString("acceptPrefixes: [\"com.a.\"]; acceptGlobs: [\"com.*.impl.\"]; "
                + "rejectPrefixes: [\"com.a.internal.\"]; rejectGlobs: [\"com.*.gen.\"]");
    }

    /** A criterion with nothing added prints as nothing at all, rather than as empty lists. */
    @Test
    public void anEmptyCriterionPrintsAsNothing() {
        assertThat(new AcceptRejectPrefix('.')).hasToString("");
    }

    /** A quote in a criterion is escaped, so that the printed lists can be read back unambiguously. */
    @Test
    public void aQuoteInACriterionIsEscaped() {
        final var acceptReject = new AcceptRejectWholeString('/');
        acceptReject.addToAccept("has\"quote");
        assertThat(acceptReject).hasToString("accept: [\"has\\\"quote\"]");
    }
}
