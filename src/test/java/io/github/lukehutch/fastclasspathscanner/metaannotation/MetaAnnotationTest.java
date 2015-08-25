package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import org.junit.Test;

public class MetaAnnotationTest {
    FastClasspathScanner scanner = new FastClasspathScanner(getClass().getPackage().getName()).scan();

    @Test
    public void oneLevel() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(E.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(F.class)).containsOnly(A.class.getName(),
                B.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(G.class)).containsOnly(C.class.getName());
    }

    @Test
    public void twoLevels() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(I.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(J.class)).containsOnly(A.class.getName(),
                B.class.getName());
    }

    @Test
    public void threeLevels() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(L.class)).containsOnly(A.class.getName());
    }

    @Test
    public void acrossCycle() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(H.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(K.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(M.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(A.class)).containsOnly(E.class.getName(),
                F.class.getName(), H.class.getName(), I.class.getName(), J.class.getName(), K.class.getName(),
                L.class.getName(), M.class.getName());
    }

    @Test
    public void namesOfMetaAnnotations() {
        assertThat(scanner.getNamesOfAnnotationsOnClass(B.class))
                .containsOnly(J.class.getName(), F.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(C.class)).containsOnly(G.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(D.class)).isEmpty();
    }

    @Test
    public void annotationsAnyOf() {
        assertThat(scanner.getNamesOfClassesWithAnnotationsAnyOf(J.class, G.class)).containsOnly(A.class.getName(),
                B.class.getName(), C.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class, G.class)).isEmpty();
        assertThat(scanner.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class)).containsOnly(A.class.getName());
    }
}
