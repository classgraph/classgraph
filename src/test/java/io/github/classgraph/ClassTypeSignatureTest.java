package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link ClassTypeSignature#equals(Object)}. */
public class ClassTypeSignatureTest {
    /** A class to parse signatures for. */
    public static class First {
    }

    /** Another class to parse signatures for. */
    public static class Second {
    }

    /**
     * Two classes with the same type parameters and supertypes do not have equal type signatures, and neither do
     * two signatures that differ only in their throws suffix.
     *
     * @throws Exception
     *             if a signature could not be parsed.
     */
    @Test
    public void signaturesOfDifferentClassesOrThrowsSuffixesAreNotEqual() throws Exception {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .acceptClasses(First.class.getName(), Second.class.getName()).scan()) {
            final ClassInfo first = scanResult.getClassInfo(First.class.getName());
            final ClassInfo second = scanResult.getClassInfo(Second.class.getName());
            final ClassTypeSignature firstSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;", first);
            final ClassTypeSignature secondSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;", second);
            assertThat(firstSig).isNotEqualTo(secondSig);

            final ClassTypeSignature throwsSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;^Ljava/lang/Exception;", first);
            assertThat(firstSig).isNotEqualTo(throwsSig);
            final ClassTypeSignature sameThrowsSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;^Ljava/lang/Exception;", first);
            assertThat(sameThrowsSig).isEqualTo(throwsSig).hasSameHashCodeAs(throwsSig);
        }
    }
}
