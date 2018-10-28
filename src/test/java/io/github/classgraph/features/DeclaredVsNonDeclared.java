package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.assertj.core.api.iterable.Extractor;

@Retention(value = RetentionPolicy.RUNTIME)
@Inherited
@interface InheritedAnnotation {
}

@Retention(value = RetentionPolicy.RUNTIME)
@interface NormalAnnotation {
}

public class DeclaredVsNonDeclared {

    @NormalAnnotation
    @InheritedAnnotation
    public abstract static class A {
        float x;

        String z;

        abstract void y(int x, int y);

        abstract void y(String x);

        abstract void y(Integer x);

        @InheritedAnnotation
        abstract void w();
    }

    public abstract static class B extends A {
        int x;

        @Override
        void y(final int x, final int y) {
        }

        @Override
        void w() {
        }
    }

    @NormalAnnotation
    public abstract static class C extends A {
    }

    @Test
    public void declaredVsNonDeclaredMethods() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
            .whitelistPackages(DeclaredVsNonDeclared.class.getPackage().getName()).scan()) {
            final ClassInfo A = scanResult.getClassInfo(A.class.getName());
            final ClassInfo B = scanResult.getClassInfo(B.class.getName());
            assertThat(B.getFieldInfo("x").getClassInfo().getName()).isEqualTo(B.class.getName());
            assertThat(B.getFieldInfo("z").getClassInfo().getName()).isEqualTo(A.class.getName());
            assertThat(A.getFieldInfo().get(0).getTypeDescriptor().toString()).isEqualTo("float");
            assertThat(B.getFieldInfo().get(0).getTypeDescriptor().toString()).isEqualTo("int");
            assertThat(B.getMethodInfo().toString()).isEqualTo(
                "[void y(int, int), void w(), abstract void y(java.lang.String), abstract void y(java.lang.Integer)]");
            assertThat(B.getDeclaredMethodInfo().toString()).isEqualTo("[void y(int, int), void w()]");
        }
    }

    @Test
    public void annotationsShouldBeAbleToDifferentiateBetweenDirectAndReachable() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
            .whitelistPackages(DeclaredVsNonDeclared.class.getPackage().getName()).scan()) {
            final ClassInfo A = scanResult.getClassInfo(A.class.getName());
            final ClassInfo B = scanResult.getClassInfo(B.class.getName());

            final ClassInfoList annotationsOnA = A.getAnnotations();
            final ClassInfoList annotationsOnB = B.getAnnotations();

            assertThat(annotationsOnA.loadClasses())
                .containsExactlyInAnyOrder(NormalAnnotation.class, InheritedAnnotation.class);
            assertThat(annotationsOnB.loadClasses())
                .containsExactlyInAnyOrder(InheritedAnnotation.class);
            assertThat(annotationsOnA.directOnly().loadClasses())
                .containsExactlyInAnyOrder(NormalAnnotation.class, InheritedAnnotation.class);
            assertThat(annotationsOnB.directOnly()).isEmpty();
        }
    }

    @Test
    public void loadFieldAndMethod() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
            .whitelistPackages(DeclaredVsNonDeclared.class.getPackage().getName()).scan()) {
            final ClassInfo A = scanResult.getClassInfo(A.class.getName());
            final ClassInfo B = scanResult.getClassInfo(B.class.getName());
            assertThat(B.getFieldInfo("x").loadClassAndGetField().getName()).isEqualTo("x");
            assertThat(B.getMethodInfo("y").get(0).loadClassAndGetMethod().getName()).isEqualTo("y");
        }
    }
}