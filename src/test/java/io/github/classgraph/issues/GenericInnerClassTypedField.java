package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Closeable;
import java.io.IOException;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.utils.Recycler;

public class GenericInnerClassTypedField {

    Recycler<Closeable, IOException>.Recyclable recyclable;

    @Test
    public void testGenericInnerClassTypedField() {
        final FieldInfoList fields = new ClassGraph()
                .whitelistPackages(GenericInnerClassTypedField.class.getPackage().getName()).enableAllInfo().scan()
                .getClassInfo(GenericInnerClassTypedField.class.getName()).getFieldInfo();
        final ClassRefTypeSignature classRefTypeSignature = (ClassRefTypeSignature) fields.get(0)
                .getTypeSignature();
        assertThat(classRefTypeSignature.toString()).isEqualTo(
                "io.github.classgraph.utils.Recycler<java.io.Closeable, java.io.IOException>.Recyclable");
        assertThat(classRefTypeSignature.getFullyQualifiedClassName())
                .isEqualTo("io.github.classgraph.utils.Recycler$Recyclable");
    }

}
