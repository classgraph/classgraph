package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Filtering preserves declaration occurrences and relationship metadata. */
public class ClassInfoListFilterTest {
    /** Two declared type variables erase to the same exception class. */
    public static class Fixture {
        /** The empty body is irrelevant: the throws clause is scanned. */
        @SuppressWarnings("unused")
        public <T extends Exception, U extends Exception> void repeated() throws T, U {
        }
    }

    /** An all-accepting filter and category filters do not turn declaration lists into sets. */
    @Test
    public void filteringThrownExceptionsPreservesRepeatedDeclarations() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Fixture.class.getName())
                .enableClassInfo().enableMethodInfo().scan()) {
            final var exceptions = scanResult.getClassInfo(Fixture.class.getName()).getMethodInfo("repeated")
                    .getSingleMethod("repeated").getThrownExceptions();
            assertThat(exceptions).hasSize(2);
            final var filtered = exceptions.filter(exceptions::contains);
            assertThat(filtered).containsExactlyElementsOf(exceptions);
            assertThat(filtered.directOnly()).isSameAs(filtered);
            assertThat(exceptions.getStandardClasses()).containsExactlyElementsOf(exceptions);
            assertThat(exceptions.filter(ci -> ci.getName().equals("no.such.Class"))).isEmpty();
        }
    }
}
