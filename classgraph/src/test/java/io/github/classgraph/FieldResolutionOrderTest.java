package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A field declared in more than one supertype of a class is resolved the way the JVM resolves a field reference
 * (JVMS 5.4.3.2), and the way {@link Class#getField(String)} finds it: the class itself, then its direct
 * superinterfaces, then its superclass, each searched the same way in turn.
 */
public class FieldResolutionOrderTest {
    /** An interface that declares the field. */
    public interface J {
        /** The field. */
        int X = 1;
    }

    /** A class that declares the field, and implements an interface that declares it too. */
    public static class B implements J {
        /** The field, hiding the one in {@link J}. */
        @SuppressWarnings("hiding")
        public static final int X = 2;
    }

    /** A class that declares no field, and so inherits the field of its superclass. */
    public static class C extends B {
    }

    /** An interface that declares the field, extending another interface that declares it too. */
    public interface K extends J {
        /** The field, hiding the one in {@link J}. */
        @SuppressWarnings("hiding")
        int X = 3;
    }

    /** A class whose superinterface and superclass both declare the field. */
    public static class D extends B implements K {
    }

    /** The scan of the test classes. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(FieldResolutionOrderTest.class.getName() + "$*").enableClassInfo().enableFieldInfo()
                .scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The class that declares the field that ClassGraph finds for a class, and the class that declares the field
     * that reflection finds for it.
     *
     * @param cls
     *            the class.
     * @throws NoSuchFieldException
     *             if reflection does not find the field.
     */
    private static void assertResolvedAsReflectionResolves(final Class<?> cls) throws NoSuchFieldException {
        final var expected = cls.getField("X").getDeclaringClass().getName();
        final var classInfo = scanResult.getClassInfo(cls.getName());
        assertThat(classInfo.getFieldInfo("X").getClassInfo().getName()).as(cls.getName()).isEqualTo(expected);
        assertThat(classInfo.getFieldInfo()).as(cls.getName()).first()
                .satisfies(fi -> assertThat(fi.getClassInfo().getName()).isEqualTo(expected));
    }

    /**
     * A superclass's field hides a field of an interface that the superclass implements.
     *
     * @throws NoSuchFieldException
     *             if reflection does not find the field.
     */
    @Test
    public void aSuperclassFieldHidesAFieldOfTheSuperclassInterfaces() throws NoSuchFieldException {
        assertResolvedAsReflectionResolves(C.class);
        assertThat(C.class.getField("X").getDeclaringClass()).isEqualTo(B.class);
    }

    /**
     * A field of a class's own interface is found before a field of its superclass.
     *
     * @throws NoSuchFieldException
     *             if reflection does not find the field.
     */
    @Test
    public void aDirectSuperinterfaceIsSearchedBeforeTheSuperclass() throws NoSuchFieldException {
        assertResolvedAsReflectionResolves(D.class);
        assertThat(D.class.getField("X").getDeclaringClass()).isEqualTo(K.class);
    }
}
