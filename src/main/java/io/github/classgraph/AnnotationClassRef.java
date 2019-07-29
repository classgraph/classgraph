/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import nonapi.io.github.classgraph.types.ParseException;

/**
 * Stores the type descriptor of a {@code Class<?>}, as found in an annotation parameter value.
 */
public class AnnotationClassRef extends ScanResultObject {
    /** The type descriptor str. */
    private String typeDescriptorStr;

    /** The type signature. */
    private transient TypeSignature typeSignature;

    /** The class name. */
    private transient String className;

    /**
     * Constructor.
     */
    AnnotationClassRef() {
        super();
    }

    /**
     * Constructor.
     *
     * @param typeDescriptorStr
     *            the type descriptor str
     */
    AnnotationClassRef(final String typeDescriptorStr) {
        super();
        this.typeDescriptorStr = typeDescriptorStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the referenced class.
     *
     * @return The name of the referenced class.
     */
    public String getName() {
        return getClassName();
    }

    /**
     * Get the type signature.
     *
     * @return The type signature of the {@code Class<?>} reference. This will be a {@link ClassRefTypeSignature}, a
     *         {@link BaseTypeSignature}, or an {@link ArrayTypeSignature}.
     */
    private TypeSignature getTypeSignature() {
        if (typeSignature == null) {
            try {
                // There can't be any type variables to resolve in ClassRefTypeSignature,
                // BaseTypeSignature or ArrayTypeSignature, so just set definingClassName to null
                typeSignature = TypeSignature.parse(typeDescriptorStr, /* definingClassName = */ null);
                typeSignature.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Loads the referenced class, returning a {@code Class<?>} reference for the referenced class.
     * 
     * @param ignoreExceptions
     *            if true, ignore exceptions and instead return null if the class could not be loaded.
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        getTypeSignature();
        if (typeSignature instanceof BaseTypeSignature) {
            return ((BaseTypeSignature) typeSignature).getType();
        } else if (typeSignature instanceof ClassRefTypeSignature) {
            return ((ClassRefTypeSignature) typeSignature).loadClass(ignoreExceptions);
        } else if (typeSignature instanceof ArrayTypeSignature) {
            return ((ArrayTypeSignature) typeSignature).loadClass(ignoreExceptions);
        } else {
            throw new IllegalArgumentException("Got unexpected type " + typeSignature.getClass().getName()
                    + " for ref type signature: " + typeDescriptorStr);
        }
    }

    /**
     * Loads the referenced class, returning a {@code Class<?>} reference for the referenced class.
     * 
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded.
     */
    @Override
    public Class<?> loadClass() {
        return loadClass(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        if (className == null) {
            getTypeSignature();
            if (typeSignature instanceof BaseTypeSignature) {
                className = ((BaseTypeSignature) typeSignature).getTypeStr();
            } else if (typeSignature instanceof ClassRefTypeSignature) {
                className = ((ClassRefTypeSignature) typeSignature).getFullyQualifiedClassName();
            } else if (typeSignature instanceof ArrayTypeSignature) {
                className = ((ArrayTypeSignature) typeSignature).getClassName();
            } else {
                throw new IllegalArgumentException("Got unexpected type " + typeSignature.getClass().getName()
                        + " for ref type signature: " + typeDescriptorStr);
            }
        }
        return className;
    }

    /**
     * Get the class info.
     *
     * @return The {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     *         encountered during scanning (i.e. if no ClassInfo object was created for the class during scanning).
     *         N.B. even if this method returns null, {@link #loadClass()} may be able to load the referenced class
     *         by name.
     */
    @Override
    public ClassInfo getClassInfo() {
        getTypeSignature();
        return typeSignature.getClassInfo();
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeSignature != null) {
            typeSignature.setScanResult(scanResult);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return getTypeSignature().hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof AnnotationClassRef)) {
            return false;
        }
        return getTypeSignature().equals(((AnnotationClassRef) obj).getTypeSignature());
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        // String prefix = "class ";
        if (scanResult != null) {
            final ClassInfo ci = getClassInfo();
            // The JDK uses "interface" for both interfaces and annotations in Annotation::toString
            if (ci != null && ci.isInterfaceOrAnnotation()) {
                // prefix = "interface ";
            }
        }
        // More recent versions of Annotation::toString() have dropped the "class"/"interface" prefix,
        // and added ".class" to the end of the class reference (which does not actually match the
        // annotation source syntax...)
        return /* prefix + */ getTypeSignature().toString() + ".class";
    }
}