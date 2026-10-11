package io.github.classgraph.features.innerclasses;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * A classfile's {@code InnerClasses} attribute lists every nested class that the classfile names, with its
 * enclosing class, not only the class's own enclosing and nested classes. Only the entries for the class itself,
 * the classes nested in it, the classes that the classfile brings into the class graph for another reason, and the
 * enclosing classes of these, may create a {@link ClassInfo}: a class named only in a type descriptor or a type
 * signature gets none.
 */
public class InnerClassesAttributeTest {
    /** The package of the test classes. */
    private static final String PKG = InnerClassesAttributeTest.class.getPackage().getName();

    /**
     * Get a {@link ClassGraph} that reads everything about the classes it scans, and reports external classes.
     *
     * @return the {@link ClassGraph}
     */
    private static ClassGraph allInfo() {
        return new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().ignoreFieldVisibility().ignoreMethodVisibility().enableExternalClasses();
    }

    /**
     * Get the {@link ClassInfo} for a class, which must exist.
     *
     * @param scanResult
     *            the scan result
     * @param className
     *            the class name
     * @return the {@link ClassInfo}
     */
    private static ClassInfo classInfo(final ScanResult scanResult, final String className) {
        final var classInfo = scanResult.getClassInfo(className);
        assertThat(classInfo).as(className).isNotNull();
        return classInfo;
    }

    /** A field of type {@code Map.Entry} does not bring in {@code java.util.Map$Entry} or {@code java.util.Map}. */
    @Test
    public void fieldOfNestedTypeCreatesNoClassInfo() {
        for (final String className : new String[] { "FieldOfNestedType", "FieldOfListOfNestedType" }) {
            try (var scanResult = allInfo().acceptClasses(PKG + "." + className).scan()) {
                assertThat(scanResult.getAllClasses().getNames()).as(className)
                        .doesNotContain("java.util.Map$Entry", "java.util.Map");
                assertThat(scanResult.getClassInfo("java.util.Map$Entry")).as(className).isNull();
                assertThat(scanResult.getClassInfo("java.util.Map")).as(className).isNull();
            }
        }
    }

    /**
     * With {@link ClassGraph#enableInterClassDependencies()}, a nested class that a class depends on keeps its
     * enclosing class and its modifiers, whether it is named in a type descriptor or only in a type signature.
     */
    @Test
    public void nestedClassDependency() {
        for (final String className : new String[] { "FieldOfNestedType", "FieldOfListOfNestedType" }) {
            try (var scanResult = allInfo().enableInterClassDependencies().acceptClasses(PKG + "." + className)
                    .scan()) {
                assertThat(classInfo(scanResult, PKG + "." + className).getClassDependencies().getNames())
                        .contains("java.util.Map$Entry");
                final var entry = classInfo(scanResult, "java.util.Map$Entry");
                assertThat(entry.getResource()).isNull();
                assertThat(entry.getEnclosingClasses().getNames()).as(className).containsExactly("java.util.Map");
                assertThat(entry.getModifiersString()).as(className).isEqualTo("public abstract static");
                assertThat(entry.isInterface()).as(className).isTrue();
            }
        }
    }

    /**
     * Each kind of reference that brings a nested class into the class graph links the classfile's
     * {@code InnerClasses} entry for it, so a nested class whose own classfile is not read still has its enclosing
     * class and its modifiers.
     */
    @Test
    public void linkedNestedClassesKeepTheirEnclosingClasses() {
        // The class to scan, the nested class it brings into the class graph, and the nested class's modifiers.
        // The nested classes are not public, so their classfiles are not read, and only the InnerClasses entry
        // in the classfile of the scanned class says what encloses them.
        final var holder = PKG + ".NonPublicNestedClasses";
        final String[][] cases = { //
                // Superclass
                { "ExtendsNestedClass", holder + "$Superclass", "static" },
                // Implemented interface
                { "ImplementsNestedInterface", holder + "$Interface", "abstract static" },
                // Class annotation
                { "AnnotatedWithNestedAnnotation", holder + "$OnClass", "abstract static" },
                // Field, method and method parameter annotations
                { "AnnotatedMembers", holder + "$OnField", "abstract static" },
                { "AnnotatedMembers", holder + "$OnMethod", "abstract static" },
                { "AnnotatedMembers", holder + "$OnParameter", "abstract static" }, //
        };
        for (final String[] c : cases) {
            try (var scanResult = allInfo().acceptClasses(PKG + "." + c[0]).scan()) {
                final var nested = classInfo(scanResult, c[1]);
                assertThat(nested.getResource()).as(c[1]).isNull();
                assertThat(nested.getEnclosingClasses().getNames()).as(c[1]).containsExactly(holder);
                assertThat(nested.getModifiersString()).as(c[1]).isEqualTo(c[2]);
                assertThat(nested.isMemberClass()).as(c[1]).isTrue();
                assertThat(classInfo(scanResult, holder).getNestedClasses().getNames()).as(c[1]).contains(c[1]);
            }
        }
    }

    /** Local and anonymous classes keep their enclosing classes and their defining method. */
    @Test
    public void localAndAnonymousClasses() {
        // Local and anonymous classes are not public
        try (var scanResult = allInfo().ignoreClassVisibility()
                .acceptClasses(PKG + ".LocalAndAnonymous$Member$1Local", PKG + ".LocalAndAnonymous$Member$1")
                .scan()) {
            for (final String className : new String[] { PKG + ".LocalAndAnonymous$Member$1Local",
                    PKG + ".LocalAndAnonymous$Member$1" }) {
                final var classInfo = classInfo(scanResult, className);
                assertThat(classInfo.getEnclosingClasses().getNames()).as(className)
                        .containsExactly(PKG + ".LocalAndAnonymous$Member", PKG + ".LocalAndAnonymous");
                assertThat(classInfo.getFullyQualifiedDefiningMethodName()).as(className)
                        .isEqualTo(PKG + ".LocalAndAnonymous$Member.declare");
            }
            assertThat(classInfo(scanResult, PKG + ".LocalAndAnonymous$Member$1Local").isLocalClass()).isTrue();
            assertThat(classInfo(scanResult, PKG + ".LocalAndAnonymous$Member$1").isAnonymousClass()).isTrue();
        }
    }

    /** A nested class scanned on its own keeps its chain of enclosing classes. */
    @Test
    public void innermostClassScannedAlone() {
        try (var scanResult = allInfo().acceptClasses(PKG + ".MemberChain$B$C").scan()) {
            assertThat(classInfo(scanResult, PKG + ".MemberChain$B$C").getEnclosingClasses().getNames())
                    .containsExactly(PKG + ".MemberChain$B", PKG + ".MemberChain");
            assertThat(classInfo(scanResult, PKG + ".MemberChain$B").getNestedClasses().getNames())
                    .containsExactly(PKG + ".MemberChain$B$C");
        }
    }

    /** A nested class scanned on its own keeps its enclosing class and the classes nested in it. */
    @Test
    public void middleClassScannedAlone() {
        try (var scanResult = allInfo().acceptClasses(PKG + ".MemberChain$B").scan()) {
            final var middle = classInfo(scanResult, PKG + ".MemberChain$B");
            assertThat(middle.getEnclosingClasses().getNames()).containsExactly(PKG + ".MemberChain");
            assertThat(middle.getNestedClasses().getNames()).containsExactly(PKG + ".MemberChain$B$C");
            assertThat(classInfo(scanResult, PKG + ".MemberChain$B$C").getModifiersString())
                    .isEqualTo("public static");
        }
    }

    /**
     * A scanned class keeps the classes nested in it at any depth that its classfile lists, even if they are not
     * scanned. (javac 11 and later list every class nested in the class, but javac 8 and ecj list only the classes
     * declared directly in it.)
     */
    @Test
    public void unscannedNestedClasses() {
        try (var scanResult = allInfo().acceptClasses(PKG + ".MemberChain").scan()) {
            assertThat(classInfo(scanResult, PKG + ".MemberChain").getNestedClasses().getNames())
                    .containsExactly(PKG + ".MemberChain$B", PKG + ".MemberChain$B$C", PKG + ".MemberChain$D");
            final var member = classInfo(scanResult, PKG + ".MemberChain$D");
            assertThat(member.getResource()).isNull();
            assertThat(member.getEnclosingClasses().getNames()).containsExactly(PKG + ".MemberChain");
            assertThat(member.getModifiersString()).isEqualTo("public static");
            assertThat(classInfo(scanResult, PKG + ".MemberChain$B$C").getEnclosingClasses().getNames())
                    .containsExactly(PKG + ".MemberChain$B", PKG + ".MemberChain");
        }
    }

    /**
     * Get, for the outermost class of a class reference type and then for each class nested in it, whether
     * {@link TypeUse} is on that class.
     *
     * @param typeSignature
     *            the class reference type
     * @return whether {@link TypeUse} is on each class
     */
    private static List<Boolean> typeUseLocations(final TypeSignature typeSignature) {
        assertThat(typeSignature).isInstanceOf(ClassRefTypeSignature.class);
        final var classRefTypeSignature = (ClassRefTypeSignature) typeSignature;
        final List<Boolean> typeUseLocations = new ArrayList<>();
        typeUseLocations.add(hasTypeUse(classRefTypeSignature.getTypeAnnotationInfo()));
        for (final AnnotationInfoList suffixAnnotations : classRefTypeSignature.getSuffixTypeAnnotationInfo()) {
            typeUseLocations.add(hasTypeUse(suffixAnnotations));
        }
        return typeUseLocations;
    }

    /**
     * Test whether a list of type annotations contains {@link TypeUse}.
     *
     * @param annotations
     *            the type annotations
     * @return true if {@link TypeUse} is in the list
     */
    private static boolean hasTypeUse(final AnnotationInfoList annotations) {
        return annotations.containsName(TypeUse.class.getName());
    }

    /**
     * A type annotation on a nested type applies to the right class whether or not the nested classes have a
     * {@link ClassInfo}. Where a type annotation goes depends on which of the nested classes are inner classes, and
     * these classes name the nested classes only in types, so the nested classes have a {@link ClassInfo} only if
     * {@link ClassGraph#enableInterClassDependencies()} was called. Each class has only one kind of type annotation
     * (on fields, on methods, or on the class), so that each kind is tested on its own.
     */
    @Test
    public void typeAnnotationsOnNestedTypes() {
        for (final boolean enableInterClassDependencies : new boolean[] { false, true }) {
            final var classGraph = allInfo().acceptClasses(PKG + ".AnnotatedNestedFieldTypes",
                    PKG + ".AnnotatedNestedMethodTypes", PKG + ".AnnotatedNestedClassTypes");
            if (enableInterClassDependencies) {
                classGraph.enableInterClassDependencies();
            }
            try (var scanResult = classGraph.scan()) {
                final var deps = "dependencies " + (enableInterClassDependencies ? "enabled" : "disabled");
                assertThat(scanResult.getClassInfo(PKG + ".NestedTypes$Inner") != null).as(deps)
                        .isEqualTo(enableInterClassDependencies);

                // Field types
                final var fieldTypes = classInfo(scanResult, PKG + ".AnnotatedNestedFieldTypes");
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("annotatedInner").getTypeSignatureOrTypeDescriptor())).as(deps)
                        .containsExactly(false, true);
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("annotatedOuter").getTypeSignatureOrTypeDescriptor())).as(deps)
                        .containsExactly(true, false);
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("annotatedStaticNested").getTypeSignatureOrTypeDescriptor()))
                        .as(deps).containsExactly(false, true);
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("annotatedDeepInInner").getTypeSignatureOrTypeDescriptor()))
                        .as(deps).containsExactly(false, false, true);
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("deepInAnnotatedInner").getTypeSignatureOrTypeDescriptor()))
                        .as(deps).containsExactly(false, true, false);
                assertThat(typeUseLocations(
                        fieldTypes.getFieldInfo("annotatedDeepInStaticNested").getTypeSignatureOrTypeDescriptor()))
                        .as(deps).containsExactly(false, false, true);
                final var listType = (ClassRefTypeSignature) fieldTypes.getFieldInfo("listOfAnnotatedInner")
                        .getTypeSignatureOrTypeDescriptor();
                assertThat(typeUseLocations(listType.getTypeArguments().get(0).getTypeSignature())).as(deps)
                        .containsExactly(false, true);

                // Method type parameter bound, result type and parameter type
                final var methodInfo = classInfo(scanResult, PKG + ".AnnotatedNestedMethodTypes")
                        .getMethodInfo("method").get(0);
                final var methodType = methodInfo.getTypeSignatureOrTypeDescriptor();
                assertThat(typeUseLocations(methodType.getTypeParameters().get(0).getClassBound())).as(deps)
                        .containsExactly(false, true, false);
                assertThat(typeUseLocations(methodType.getResultType())).as(deps).containsExactly(false, true);
                assertThat(
                        typeUseLocations(methodInfo.getParameterInfo().get(0).getTypeSignatureOrTypeDescriptor()))
                        .as(deps).containsExactly(false, false, true);

                // Class type parameter bound, superclass type argument and superinterface type argument
                final var classType = classInfo(scanResult, PKG + ".AnnotatedNestedClassTypes").getTypeSignature();
                assertThat(classType).isNotNull();
                assertThat(typeUseLocations(classType.getTypeParameters().get(0).getClassBound())).as(deps)
                        .containsExactly(false, true);
                assertThat(typeUseLocations(
                        classType.getSuperclassSignature().getTypeArguments().get(0).getTypeSignature())).as(deps)
                        .containsExactly(false, true);
                assertThat(typeUseLocations(classType.getSuperinterfaceSignatures().get(0).getTypeArguments().get(0)
                        .getTypeSignature())).as(deps).containsExactly(false, false, true);
            }
        }
    }
}
