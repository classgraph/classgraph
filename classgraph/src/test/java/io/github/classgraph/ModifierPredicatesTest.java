package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The modifiers of a class, field or method are read from the classfile, so ClassGraph reports them without loading
 * the class -- including the modifiers that reflection cannot show, such as the synthetic flag on the members the
 * compiler adds by itself.
 */
public class ModifierPredicatesTest {
    /** A public final class. */
    public static final class PublicFinal {
    }

    /** A class with default (package) visibility. */
    static class PackageVisible {
    }

    /** A protected class. */
    protected static class Protected {
    }

    /** A private class. */
    private static class Private {
    }

    /** An abstract class, with methods that only an abstract class can declare. */
    public abstract static class AbstractClass {
        /** An abstract method. */
        public abstract void abstractMethod();

        /** A native method. */
        public native void nativeMethod();
    }

    /** An enum, whose constants are fields marked as enum constants. */
    public enum Color {
        /** A constant. */
        RED,
        /** A second constant. */
        GREEN
    }

    /** A record. */
    public record Point(int x, int y) {
    }

    /** An interface. */
    public interface Interface {
    }

    /** An annotation. */
    public @interface Annotation {
    }

    /** A generic class, whose subclass gets a bridge method. */
    public static class Box<T> {
        /**
         * Get the contents.
         *
         * @return the contents.
         */
        public T get() {
            return null;
        }
    }

    /** A subclass that narrows the return type, so that the compiler adds a bridge method to it. */
    public static class StringBox extends Box<String> {
        @Override
        public String get() {
            return "";
        }
    }

    /** A class with members of every kind that carries a modifier. */
    @SuppressWarnings("unused")
    public static class Members {
        /** A private field. */
        private int privateField;

        /** A protected field. */
        protected int protectedField;

        /** A transient field. */
        public transient int transientField;

        /** A static final field. */
        public static final int CONSTANT = 1;

        /** A private synchronized method. */
        private synchronized void privateSynchronizedMethod() {
        }

        /**
         * A protected varargs method.
         *
         * @param values
         *            the values.
         */
        protected final void protectedFinalVarargsMethod(final int... values) {
        }
    }

    /** A non-static inner class, whose reference to its outer instance is a synthetic field. */
    public class Inner {
    }

    /** The scan of this test's own package. */
    private static ScanResult scanResult;

    /** Scan this test's own package, with nothing ignored because of its visibility. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().enableClasspath().acceptClasses(ModifierPredicatesTest.class.getName() + "$*")
                .enableAllInfo().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The {@link ClassInfo} for one of the nested test classes.
     *
     * @param cls
     *            the class.
     * @return the {@link ClassInfo}.
     */
    private static ClassInfo classInfo(final Class<?> cls) {
        final var ci = scanResult.getClassInfo(cls.getName());
        assertThat(ci).as(cls.getName()).isNotNull();
        return ci;
    }

    /** The visibility of a class is read from the classfile, and a class with no visibility modifier is package. */
    @Test
    public void theVisibilityOfAClassIsRead() {
        assertThat(classInfo(PublicFinal.class).isPublic()).isTrue();
        assertThat(classInfo(Protected.class).isProtected()).isTrue();
        assertThat(classInfo(Private.class).isPrivate()).isTrue();

        // Package visibility is the absence of the other three, since there is no modifier bit for it
        assertThat(classInfo(PackageVisible.class).isPackageVisible()).isTrue();
        assertThat(classInfo(PublicFinal.class).isPackageVisible()).isFalse();
        assertThat(classInfo(Protected.class).isPackageVisible()).isFalse();
        assertThat(classInfo(Private.class).isPackageVisible()).isFalse();
    }

    /** The other modifiers of a class are read from the classfile too. */
    @Test
    public void theOtherModifiersOfAClassAreRead() {
        assertThat(classInfo(PublicFinal.class).isFinal()).isTrue();
        assertThat(classInfo(PublicFinal.class).isAbstract()).isFalse();
        assertThat(classInfo(PublicFinal.class).isStatic()).isTrue();
        assertThat(classInfo(Inner.class).isStatic()).isFalse();

        assertThat(classInfo(AbstractClass.class).isAbstract()).isTrue();
        assertThat(classInfo(AbstractClass.class).isFinal()).isFalse();

        // An interface and an annotation are abstract, and an enum and a record are final, without either modifier
        // being written in the source
        assertThat(classInfo(Interface.class).isAbstract()).isTrue();
        assertThat(classInfo(Annotation.class).isAbstract()).isTrue();
        assertThat(classInfo(Color.class).isFinal()).isTrue();
        assertThat(classInfo(Point.class).isFinal()).isTrue();
    }

    /** The visibility and the modifiers of a field are read from the classfile. */
    @Test
    public void theModifiersOfAFieldAreRead() {
        final var fields = classInfo(Members.class).getFieldInfo();

        assertThat(fields.get("privateField").isPrivate()).isTrue();
        assertThat(fields.get("protectedField").isProtected()).isTrue();
        assertThat(fields.get("transientField").isPublic()).isTrue();
        assertThat(fields.get("transientField").isTransient()).isTrue();
        assertThat(fields.get("privateField").isTransient()).isFalse();

        assertThat(fields.get("CONSTANT").isStatic()).isTrue();
        assertThat(fields.get("CONSTANT").isFinal()).isTrue();
        assertThat(fields.get("privateField").isFinal()).isFalse();

        // An enum constant is a static final field of the enum type, marked as an enum constant
        final var constants = classInfo(Color.class).getFieldInfo();
        assertThat(constants.get("RED").isEnum()).isTrue();
        assertThat(fields.get("CONSTANT").isEnum()).isFalse();
    }

    /** The visibility and the modifiers of a method are read from the classfile. */
    @Test
    public void theModifiersOfAMethodAreRead() {
        final var methods = classInfo(Members.class).getMethodInfo();

        assertThat(methods.getSingleMethod("privateSynchronizedMethod").isPrivate()).isTrue();
        assertThat(methods.getSingleMethod("privateSynchronizedMethod").isSynchronized()).isTrue();
        assertThat(methods.getSingleMethod("protectedFinalVarargsMethod").isProtected()).isTrue();
        assertThat(methods.getSingleMethod("protectedFinalVarargsMethod").isFinal()).isTrue();
        assertThat(methods.getSingleMethod("protectedFinalVarargsMethod").isVarArgs()).isTrue();
        assertThat(methods.getSingleMethod("privateSynchronizedMethod").isVarArgs()).isFalse();
        assertThat(methods.getSingleMethod("privateSynchronizedMethod").isSynchronized()).isTrue();

        final var abstractClassMethods = classInfo(AbstractClass.class).getMethodInfo();
        assertThat(abstractClassMethods.getSingleMethod("abstractMethod").isAbstract()).isTrue();
        assertThat(abstractClassMethods.getSingleMethod("abstractMethod").isNative()).isFalse();
        assertThat(abstractClassMethods.getSingleMethod("nativeMethod").isNative()).isTrue();
        assertThat(abstractClassMethods.getSingleMethod("nativeMethod").isAbstract()).isFalse();
    }

    /**
     * The members that the compiler adds by itself are marked synthetic, which is how a caller can tell them apart
     * from the members that were written in the source.
     */
    @Test
    public void theMembersTheCompilerAddsAreMarkedSynthetic() {
        // The array of constants that an enum's values() method copies
        final var enumValues = classInfo(Color.class).getFieldInfo().get("$VALUES");
        assertThat(enumValues).as("the synthetic $VALUES field of an enum").isNotNull();
        assertThat(enumValues.isSynthetic()).isTrue();
        assertThat(classInfo(Color.class).getFieldInfo().get("RED").isSynthetic()).isFalse();

        // The reference an inner class keeps to the instance of the class that encloses it
        assertThat(classInfo(Inner.class).getFieldInfo()).allSatisfy(f -> assertThat(f.isSynthetic()).isTrue());

        // The bridge method that lets a call through Box#get() reach StringBox#get(), which returns a String rather
        // than the Object that the erased signature of the overridden method returns
        final var get = classInfo(StringBox.class).getMethodInfo("get");
        assertThat(get).hasSize(2);
        final var bridge = get.filter(MethodInfo::isBridge);
        assertThat(bridge).hasSize(1);
        assertThat(bridge.get(0).isSynthetic()).isTrue();
        assertThat(bridge.get(0).getTypeDescriptorString()).isEqualTo("()Ljava/lang/Object;");
        assertThat(get.filter(mi -> !mi.isBridge()).get(0).isSynthetic()).isFalse();
    }

    /**
     * The {@code strictfp} modifier is reported for a classfile that carries it. Every method is strict as of Java
     * 17, so the compiler stops writing the modifier into classfiles of that version onwards -- but ClassGraph
     * reads classfiles written by any compiler, including the older ones that do carry it, so the classfile here is
     * compiled to the older format.
     *
     * @param tempDir
     *            a temporary directory to compile into.
     * @throws IOException
     *             if the source file could not be written.
     */
    @Test
    public void theStrictfpModifierOfAnOlderClassfileIsRead(@TempDir final Path tempDir) throws IOException {
        final var sourceFile = tempDir.resolve("Strict.java");
        Files.writeString(sourceFile, "public class Strict { public strictfp double half() { return 0.5; } }");
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, "--release", "11", "-nowarn", "-d", tempDir.toString(),
                sourceFile.toString())).as("javac exit code").isZero();

        try (var strictScanResult = new ClassGraph().enableClasspathEntries(tempDir.toString()).enableMethodInfo()
                .scan()) {
            final var half = strictScanResult.getClassInfo("Strict").getMethodInfo().getSingleMethod("half");
            assertThat(half.isStrict()).isTrue();
            assertThat(half.getModifiersString()).contains("strictfp");
        }
    }
}
