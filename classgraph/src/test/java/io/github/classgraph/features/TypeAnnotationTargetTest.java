package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.util.EventListener;
import java.util.List;
import java.util.RandomAccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ClassTypeSignature;
import io.github.classgraph.MethodTypeSignature;
import io.github.classgraph.ScanResult;

/**
 * Each kind of type annotation target that a class, method or field can hold is attached to the part of the type
 * signature or type descriptor that its target info names.
 */
public class TypeAnnotationTargetTest {
    /** A type annotation whose value names the place it was written, so a test can check where it ended up. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface At {
        /** @return the place the annotation was written. */
        String value();
    }

    /**
     * A generic class with a type annotation on each class target: a type parameter, a class bound, an interface
     * bound after a class bound, an interface bound with no class bound, the superclass, and each superinterface.
     *
     * @param <A>
     *            a type parameter with an annotation on its declaration.
     * @param <B>
     *            a type parameter with a class bound and an interface bound.
     * @param <C>
     *            a type parameter with only an interface bound.
     */
    static class GenericClass<@At("classTypeParameter") A, //
            B extends @At("classClassBound") Number & @At("classInterfaceBound") Comparable<B>, //
            C extends @At("classInterfaceOnlyBound") Runnable> //
            extends @At("superclass") ThreadLocal<A>
            implements @At("superinterface0") RandomAccess, @At("superinterface1") EventListener {
        /** A field of type {@code B}. */
        public B b;

        /** A field of type {@code C}. */
        public C c;
    }

    /** A class with no type variables, so it has no {@code Signature} attribute, only its type descriptor. */
    static class PlainClass extends @At("plainSuperclass") Object
            implements @At("plainSuperinterface") RandomAccess {
    }

    /** A class whose methods carry a type annotation on each method target. */
    static class Methods {
        /**
         * A generic method with a type annotation on each method target.
         *
         * @param <X>
         *            a type parameter with an annotation on its declaration.
         * @param <Y>
         *            a type parameter with a class bound and an interface bound.
         * @param <Z>
         *            a type parameter with only an interface bound.
         * @param p0
         *            a parameter whose type is annotated.
         * @param p1
         *            a parameter whose type argument is annotated.
         * @param p2
         *            a parameter of type {@code Y}.
         * @param p3
         *            a parameter of type {@code Z}.
         * @return the empty string.
         */
        public <@At("methodTypeParameter") X, //
                Y extends @At("methodClassBound") Number & @At("methodInterfaceBound") Comparable<Y>, //
                Z extends @At("methodInterfaceOnlyBound") Runnable> //
                @At("return") String genericMethod(@At("receiver") Methods this, final @At("parameter0") String p0,
                        final List<@At("parameter1TypeArgument") X> p1, final Y p2, final Z p3) {
            return "";
        }

        /**
         * A method whose throws clause names a type variable, which is what puts the throws clause in the method
         * type signature, with a type annotation on each thrown type.
         *
         * @param <E>
         *            the type of the exception thrown when {@code e} is not null.
         * @param e
         *            the exception to throw, or null to throw an {@link IOException}.
         * @throws IOException
         *             if {@code e} is null.
         */
        public <E extends Exception> void genericThrows(final E e)
                throws @At("throws0") IOException, @At("throws1") E {
            if (e == null) {
                throw new IOException();
            }
            throw e;
        }

        /**
         * A method with no type variables, so it has no {@code Signature} attribute, only its type descriptor.
         *
         * @param s
         *            a parameter whose type is annotated.
         * @return the empty string.
         */
        public @At("plainReturn") String plainMethod(final @At("plainParameter0") String s) {
            return "";
        }
    }

    /** An inner class, whose constructor takes the enclosing instance as an implicit first parameter. */
    class Inner {
        /**
         * A constructor whose declared parameter's type is annotated.
         *
         * @param s
         *            a parameter whose type is annotated.
         */
        Inner(final @At("constructorParameter0") String s) {
        }
    }

    /** A class whose fields carry type annotations. */
    static class Fields {
        /** A field whose type is annotated. */
        public @At("field") String field;

        /** A field whose type argument is annotated. */
        public List<@At("fieldTypeArgument") String> genericField;
    }

    /**
     * A record, whose component's type annotation is copied to its field and to its accessor's return type.
     *
     * @param component
     *            a component whose type is annotated.
     */
    record Rec(@At("component") String component) {
    }

    /**
     * Scan the fixtures.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixtures() {
        return new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility()
                .acceptClasses(TypeAnnotationTargetTest.class.getName(), GenericClass.class.getName(),
                        PlainClass.class.getName(), Methods.class.getName(), Inner.class.getName(),
                        Fields.class.getName(), Rec.class.getName())
                .scan();
    }

    /**
     * Get the places named by the {@link At} annotations in a list of type annotations.
     *
     * @param annotations
     *            the type annotations, or null if there are none.
     * @return the places, in the order of the list.
     */
    private static List<String> places(final AnnotationInfoList annotations) {
        return annotations == null ? List.of()
                : annotations.stream().map(annotation -> (String) annotation.getParameterValues().getValue("value"))
                        .toList();
    }

    /** Each class target is attached to the matching part of the class type signature. */
    @Test
    void classTargets() {
        try (var scanResult = scanFixtures()) {
            final ClassTypeSignature sig = scanResult.getClassInfo(GenericClass.class.getName()).getTypeSignature();
            assertThat(sig).isNotNull();
            final var typeParameters = sig.getTypeParameters();
            assertThat(places(typeParameters.get(0).getTypeAnnotationInfo())).containsExactly("classTypeParameter");
            assertThat(places(typeParameters.get(1).getTypeAnnotationInfo())).isEmpty();
            assertThat(places(typeParameters.get(1).getClassBound().getTypeAnnotationInfo()))
                    .containsExactly("classClassBound");
            assertThat(places(typeParameters.get(1).getInterfaceBounds().get(0).getTypeAnnotationInfo()))
                    .containsExactly("classInterfaceBound");
            assertThat(typeParameters.get(2).getClassBound()).isNull();
            assertThat(places(typeParameters.get(2).getInterfaceBounds().get(0).getTypeAnnotationInfo()))
                    .containsExactly("classInterfaceOnlyBound");
            assertThat(places(sig.getSuperclassSignature().getTypeAnnotationInfo())).containsExactly("superclass");
            assertThat(places(sig.getSuperinterfaceSignatures().get(0).getTypeAnnotationInfo()))
                    .containsExactly("superinterface0");
            assertThat(places(sig.getSuperinterfaceSignatures().get(1).getTypeAnnotationInfo()))
                    .containsExactly("superinterface1");
        }
    }

    /** The supertype targets of a class with no type signature are attached to its type descriptor. */
    @Test
    void classDescriptorTargets() {
        try (var scanResult = scanFixtures()) {
            final var classInfo = scanResult.getClassInfo(PlainClass.class.getName());
            assertThat(classInfo.getTypeSignature()).isNull();
            final var descriptor = classInfo.getTypeSignatureOrTypeDescriptor();
            assertThat(places(descriptor.getSuperclassSignature().getTypeAnnotationInfo()))
                    .containsExactly("plainSuperclass");
            assertThat(places(descriptor.getSuperinterfaceSignatures().get(0).getTypeAnnotationInfo()))
                    .containsExactly("plainSuperinterface");
        }
    }

    /** Each method target is attached to the matching part of the method type signature. */
    @Test
    void methodTargets() {
        try (var scanResult = scanFixtures()) {
            final var methodInfo = scanResult.getClassInfo(Methods.class.getName()).getMethodInfo("genericMethod")
                    .get(0);
            final MethodTypeSignature sig = methodInfo.getTypeSignature();
            assertThat(sig).isNotNull();
            final var typeParameters = sig.getTypeParameters();
            assertThat(places(typeParameters.get(0).getTypeAnnotationInfo()))
                    .containsExactly("methodTypeParameter");
            assertThat(places(typeParameters.get(1).getTypeAnnotationInfo())).isEmpty();
            assertThat(places(typeParameters.get(1).getClassBound().getTypeAnnotationInfo()))
                    .containsExactly("methodClassBound");
            assertThat(places(typeParameters.get(1).getInterfaceBounds().get(0).getTypeAnnotationInfo()))
                    .containsExactly("methodInterfaceBound");
            assertThat(typeParameters.get(2).getClassBound()).isNull();
            assertThat(places(typeParameters.get(2).getInterfaceBounds().get(0).getTypeAnnotationInfo()))
                    .containsExactly("methodInterfaceOnlyBound");
            assertThat(places(sig.getResultType().getTypeAnnotationInfo())).containsExactly("return");
            assertThat(places(sig.getReceiverTypeAnnotationInfo())).containsExactly("receiver");
            final var parameters = methodInfo.getParameterInfo();
            assertThat(parameters).hasSize(4);
            assertThat(places(parameters.get(0).getTypeSignature().getTypeAnnotationInfo()))
                    .containsExactly("parameter0");
            assertThat(places(parameters.get(1).getTypeSignature().getTypeAnnotationInfo())).isEmpty();
            assertThat(places(((ClassRefTypeSignature) parameters.get(1).getTypeSignature()).getTypeArguments()
                    .get(0).getTypeSignature().getTypeAnnotationInfo())).containsExactly("parameter1TypeArgument");
            assertThat(places(parameters.get(2).getTypeSignature().getTypeAnnotationInfo())).isEmpty();
            assertThat(places(parameters.get(3).getTypeSignature().getTypeAnnotationInfo())).isEmpty();

            final var throwsSig = scanResult.getClassInfo(Methods.class.getName()).getMethodInfo("genericThrows")
                    .get(0).getTypeSignature();
            assertThat(throwsSig).isNotNull();
            final var throwsSignatures = throwsSig.getThrowsSignatures();
            assertThat(throwsSignatures).hasSize(2);
            assertThat(places(throwsSignatures.get(0).getTypeAnnotationInfo())).containsExactly("throws0");
            assertThat(places(throwsSignatures.get(1).getTypeAnnotationInfo())).containsExactly("throws1");
        }
    }

    /**
     * The method targets are also attached to the method type descriptor, except the targets in type parameters,
     * which a type descriptor does not have.
     */
    @Test
    void methodDescriptorTargets() {
        try (var scanResult = scanFixtures()) {
            final var methods = scanResult.getClassInfo(Methods.class.getName());

            final var genericMethod = methods.getMethodInfo("genericMethod").get(0);
            final var genericDescriptor = genericMethod.getTypeDescriptor();
            assertThat(genericDescriptor.getTypeParameters()).isEmpty();
            assertThat(places(genericDescriptor.getResultType().getTypeAnnotationInfo())).containsExactly("return");
            assertThat(places(genericDescriptor.getReceiverTypeAnnotationInfo())).containsExactly("receiver");
            assertThat(places(genericMethod.getParameterInfo().get(0).getTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactly("parameter0");

            final var plainMethod = methods.getMethodInfo("plainMethod").get(0);
            assertThat(plainMethod.getTypeSignature()).isNull();
            final var plainDescriptor = plainMethod.getTypeSignatureOrTypeDescriptor();
            assertThat(places(plainDescriptor.getResultType().getTypeAnnotationInfo()))
                    .containsExactly("plainReturn");
            assertThat(places(plainMethod.getParameterInfo().get(0).getTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactly("plainParameter0");
        }
    }

    /**
     * A formal parameter target of an inner class constructor is attached to the declared parameter, not to the
     * implicit enclosing-instance parameter that comes first in the type descriptor.
     */
    @Test
    void innerClassConstructorParameterTarget() {
        try (var scanResult = scanFixtures()) {
            final var constructor = scanResult.getClassInfo(Inner.class.getName()).getDeclaredConstructorInfo()
                    .get(0);
            assertThat(constructor.getTypeSignature()).isNull();
            final var parameters = constructor.getParameterInfo();
            assertThat(parameters).hasSize(2);
            assertThat(places(parameters.get(0).getTypeDescriptor().getTypeAnnotationInfo())).isEmpty();
            assertThat(places(parameters.get(1).getTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactly("constructorParameter0");
        }
    }

    /** A field target is attached to the field type, following the type path into a type argument. */
    @Test
    void fieldTargets() {
        try (var scanResult = scanFixtures()) {
            final var fields = scanResult.getClassInfo(Fields.class.getName());
            assertThat(
                    places(fields.getFieldInfo("field").getTypeSignatureOrTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactly("field");
            final var genericFieldType = (ClassRefTypeSignature) fields.getFieldInfo("genericField")
                    .getTypeSignature();
            assertThat(places(genericFieldType.getTypeAnnotationInfo())).isEmpty();
            assertThat(
                    places(genericFieldType.getTypeArguments().get(0).getTypeSignature().getTypeAnnotationInfo()))
                    .containsExactly("fieldTypeArgument");
        }
    }

    /** A record component's type annotation is attached to its field type and to its accessor's return type. */
    @Test
    void recordComponentTargets() {
        try (var scanResult = scanFixtures()) {
            final var rec = scanResult.getClassInfo(Rec.class.getName());
            assertThat(places(
                    rec.getFieldInfo("component").getTypeSignatureOrTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactly("component");
            assertThat(places(rec.getMethodInfo("component").get(0).getTypeSignatureOrTypeDescriptor()
                    .getResultType().getTypeAnnotationInfo())).containsExactly("component");
        }
    }

    /**
     * Build one {@code type_annotation} structure with an empty type path and no element values.
     *
     * @param target
     *            the {@code target_type} and {@code target_info}.
     * @param typeIndex
     *            the constant pool index of the annotation's type descriptor.
     * @return the bytes of the structure.
     */
    private static byte[] typeAnnotation(final byte[] target, final int typeIndex) {
        final var bytes = new byte[target.length + 5];
        System.arraycopy(target, 0, bytes, 0, target.length);
        bytes[target.length] = 0; // type_path: path_length
        bytes[target.length + 1] = (byte) (typeIndex >> 8); // type_index
        bytes[target.length + 2] = (byte) typeIndex;
        bytes[target.length + 3] = 0; // num_element_value_pairs
        bytes[target.length + 4] = 0;
        return bytes;
    }

    /** The constant pool index of the type descriptor {@code LIgnored;}. */
    private static final int IGNORED = 6;

    /** The constant pool index of the type descriptor {@code LReturn;}. */
    private static final int RETURN = 7;

    /**
     * Generate an abstract class with one abstract method {@code String m()}, whose
     * {@code RuntimeVisibleTypeAnnotations} attribute holds the given type annotations.
     *
     * @param className
     *            the name of the class.
     * @param typeAnnotations
     *            the {@code type_annotation} structures of the method.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] methodClassfileBytes(final String className, final byte[]... typeAnnotations)
            throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(10); // Constant pool count (one greater than the number of entries)
            out.writeByte(1); // #1
            out.writeUTF(className);
            out.writeByte(7); // #2: class ref to #1 (this_class)
            out.writeShort(1);
            out.writeByte(1); // #3
            out.writeUTF("java/lang/Object");
            out.writeByte(7); // #4: class ref to #3 (super_class)
            out.writeShort(3);
            out.writeByte(1); // #5
            out.writeUTF("RuntimeVisibleTypeAnnotations");
            out.writeByte(1); // #6 (IGNORED)
            out.writeUTF("LIgnored;");
            out.writeByte(1); // #7 (RETURN)
            out.writeUTF("LReturn;");
            out.writeByte(1); // #8
            out.writeUTF("m");
            out.writeByte(1); // #9
            out.writeUTF("()Ljava/lang/String;");

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(1); // Number of methods
            out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
            out.writeShort(8); // name_index: "m"
            out.writeShort(9); // descriptor_index: "()Ljava/lang/String;"
            out.writeShort(1); // Number of method attributes
            out.writeShort(5); // "RuntimeVisibleTypeAnnotations"
            var attributeLength = 2;
            for (final byte[] typeAnnotation : typeAnnotations) {
                attributeLength += typeAnnotation.length;
            }
            out.writeInt(attributeLength);
            out.writeShort(typeAnnotations.length); // num_annotations
            for (final byte[] typeAnnotation : typeAnnotations) {
                out.write(typeAnnotation);
            }

            out.writeShort(0); // Number of class attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * The two class and field targets that some compilers wrongly put on a method (#861, #797) are read and
     * ignored, and the type annotations after them are still read from the right place.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    @Test
    void ignoredMethodTargetsAreSkipped(@TempDir final File tempDir) throws IOException {
        final var className = "IgnoredMethodTargets";
        Files.write(new File(tempDir, className + ".class").toPath(), methodClassfileBytes(className,
                // target_type 0x10 (type in extends clause), supertype_index 65535 (the superclass)
                typeAnnotation(new byte[] { 0x10, (byte) 0xFF, (byte) 0xFF }, IGNORED),
                // target_type 0x13 (type in field or record component declaration), with an empty target_info
                typeAnnotation(new byte[] { 0x13 }, IGNORED),
                // target_type 0x14 (return type of method), with an empty target_info
                typeAnnotation(new byte[] { 0x14 }, RETURN)));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(className);
            assertThat(classInfo).isNotNull();
            final var resultType = classInfo.getMethodInfo("m").get(0).getTypeDescriptor().getResultType();
            assertThat(resultType.getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactly("Return");
        }
    }

    /**
     * A method target that is not permitted in a method, and whose size is therefore unknown, stops the class from
     * being read, rather than being skipped.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be written.
     */
    @Test
    void unknownMethodTargetDropsTheClass(@TempDir final File tempDir) throws IOException {
        final var className = "UnknownMethodTarget";
        // target_type 0x40 (type in local variable declaration), which is only permitted in a Code attribute
        Files.write(new File(tempDir, className + ".class").toPath(),
                methodClassfileBytes(className, typeAnnotation(new byte[] { 0x40, 0, 0 }, RETURN)));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            assertThat(scanResult.getClassInfo(className)).isNull();
        }
    }
}
