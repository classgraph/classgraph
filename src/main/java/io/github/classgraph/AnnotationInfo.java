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
 * Copyright (c) 2018 Luke Hutchison
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

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.annotation.Inherited;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** Holds metadata about a specific annotation instance on a class, method, method parameter or field. */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo>, HasName {

    private String name;
    AnnotationParameterValueList annotationParamValues;

    /**
     * Set to true once any Object[] arrays of boxed types in annotationParamValues have been lazily converted to
     * primitive arrays.
     */
    private transient boolean annotationParamValuesHasBeenConvertedToPrimitive;

    private transient AnnotationParameterValueList annotationParamValuesWithDefaults;

    /** Default constructor for deserialization. */
    AnnotationInfo() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param name
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    AnnotationInfo(final String name, final AnnotationParameterValueList annotationParamValues) {
        this.name = name;
        this.annotationParamValues = annotationParamValues;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The name of the annotation class.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * @return true if this annotation is meta-annotated with {@link Inherited}.
     */
    public boolean isInherited() {
        return getClassInfo().isInherited;
    }

    /**
     * @return the list of default parameter values for this annotation, or the empty list if none.
     */
    public AnnotationParameterValueList getDefaultParameterValues() {
        return getClassInfo().getAnnotationDefaultParameterValues();
    }

    /**
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition, or the empty list if none.
     */
    public AnnotationParameterValueList getParameterValues() {
        if (annotationParamValuesWithDefaults == null) {
            final ClassInfo classInfo = getClassInfo();
            if (classInfo == null) {
                // ClassInfo has not yet been set, just return values without defaults
                // (happens when trying to log AnnotationInfo during scanning, before ScanResult is available)
                return annotationParamValues == null ? AnnotationParameterValueList.EMPTY_LIST
                        : annotationParamValues;
            }

            // Lazily convert any Object[] arrays of boxed types to primitive arrays
            if (annotationParamValues != null && !annotationParamValuesHasBeenConvertedToPrimitive) {
                annotationParamValues.convertWrapperArraysToPrimitiveArrays(classInfo);
                annotationParamValuesHasBeenConvertedToPrimitive = true;
            }
            if (classInfo.annotationDefaultParamValues != null
                    && !classInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive) {
                classInfo.annotationDefaultParamValues.convertWrapperArraysToPrimitiveArrays(classInfo);
                classInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive = true;
            }

            // Check if one or both of the defaults and the values in this annotation instance are null (empty)
            final AnnotationParameterValueList defaultParamValues = classInfo.annotationDefaultParamValues;
            if (defaultParamValues == null && annotationParamValues == null) {
                return AnnotationParameterValueList.EMPTY_LIST;
            } else if (defaultParamValues == null) {
                return annotationParamValues;
            } else if (annotationParamValues == null) {
                return defaultParamValues;
            }

            // Overwrite defaults with non-defaults
            final Map<String, Object> allParamValues = new HashMap<>();
            for (final AnnotationParameterValue defaultParamValue : defaultParamValues) {
                allParamValues.put(defaultParamValue.getName(), defaultParamValue.getValue());
            }
            for (final AnnotationParameterValue annotationParamValue : this.annotationParamValues) {
                allParamValues.put(annotationParamValue.getName(), annotationParamValue.getValue());
            }
            annotationParamValuesWithDefaults = new AnnotationParameterValueList();
            for (final Entry<String, Object> ent : allParamValues.entrySet()) {
                annotationParamValuesWithDefaults.add(new AnnotationParameterValue(ent.getKey(), ent.getValue()));
            }
            Collections.sort(annotationParamValuesWithDefaults);
        }
        return annotationParamValuesWithDefaults;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return the name of the annotation class, for {@link #getClassInfo()}. */
    @Override
    protected String getClassName() {
        return name;
    }

    /** @return The {@link ClassInfo} object for the annotation class. */
    @Override
    public ClassInfo getClassInfo() {
        getClassName();
        return super.getClassInfo();
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue a : annotationParamValues) {
                a.setScanResult(scanResult);
            }
        }
    }

    /** Get the names of any classes referenced in the type descriptors of annotation parameters. */
    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
        classNames.add(name);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue annotationParamValue : annotationParamValues) {
                annotationParamValue.getClassNamesFromTypeDescriptors(classNames);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load the {@link Annotation} class corresponding to this {@link AnnotationInfo} object, by calling
     * {@code getClassInfo().loadClass()}, then create a new instance of the annotation, with the annotation
     * parameter values obtained from this {@link AnnotationInfo} object.
     * 
     * @return The new {@link Annotation} instance.
     */
    public Annotation loadClassAndInstantiate() {
        final Class<? extends Annotation> annotationClass = getClassInfo().loadClass(Annotation.class);
        return (Annotation) Proxy.newProxyInstance(annotationClass.getClassLoader(),
                new Class[] { annotationClass }, new AnnotationInvocationHandler(annotationClass, this));
    }

    /** {@link InvocationHandler} for dynamically instantiating an {@link Annotation} object. */
    private static class AnnotationInvocationHandler implements InvocationHandler, Serializable {
        private final Class<? extends Annotation> annotationClass;
        private final Map<String, Object> annotationParameterValuesInstantiated = new HashMap<>();
        private final String toString;

        AnnotationInvocationHandler(final Class<? extends Annotation> annotationClass,
                final AnnotationInfo annotationInfo) {
            this.annotationClass = annotationClass;
            this.toString = annotationInfo.toString();

            // Instantiate the annotation parameter values (this loads and gets references for class literals,
            // enum constants, etc.)
            for (final AnnotationParameterValue apv : annotationInfo.getParameterValues()) {
                final Object instantiatedValue = apv.instantiate(annotationInfo.getClassInfo());
                if (instantiatedValue == null) {
                    // Annotations cannot contain null values
                    throw new IllegalArgumentException("Got null value for annotation parameter " + apv.getName()
                            + " of annotation " + annotationInfo.getName());
                }
                this.annotationParameterValuesInstantiated.put(apv.getName(), instantiatedValue);
            }
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            final String methodName = method.getName();
            final Class<?>[] paramTypes = method.getParameterTypes();

            if (paramTypes.length == 1) {
                if (methodName.equals("equals") && paramTypes[0] == Object.class) {
                    return args[0] != null && args[0] instanceof AnnotationInvocationHandler
                            && ((AnnotationInvocationHandler) args[0]).annotationClass == annotationClass
                            && ((AnnotationInvocationHandler) args[0]).annotationParameterValuesInstantiated
                                    .equals(annotationParameterValuesInstantiated);
                } else {
                    // .equals(Object) is the only method of an enum that can take one parameter
                    throw new IllegalArgumentException();
                }
            } else if (paramTypes.length == 0) {
                // Handle .toString(), .hashCode(), .annotationType()
                if (methodName.equals("toString")) {
                    return toString;
                } else if (methodName.equals("hashCode")) {
                    return toString.hashCode();
                } else if (methodName.equals("annotationType")) {
                    return annotationClass;
                }
            } else {
                // Throw exception for 2 or more params
                throw new IllegalArgumentException();
            }

            // Instantiate the annotation parameter value (this loads and gets references for class literals,
            // enum constants, etc.)
            final Object annotationParameterValue = annotationParameterValuesInstantiated.get(methodName);
            if (annotationParameterValue == null) {
                // Undefined enum constant (enum values cannot be null)
                throw new IncompleteAnnotationException(annotationClass, methodName);
            }

            // Clone any array-typed annotation parameter values, in keeping with the Java Annotation API
            final Class<? extends Object> annotationParameterValueClass = annotationParameterValue.getClass();
            if (annotationParameterValueClass.isArray()) {
                // Handle array types
                final Class<?> arrayType = annotationParameterValueClass;
                if (arrayType == String[].class) {
                    return ((String[]) annotationParameterValue).clone();
                } else if (arrayType == byte[].class) {
                    return ((byte[]) annotationParameterValue).clone();
                } else if (arrayType == char[].class) {
                    return ((char[]) annotationParameterValue).clone();
                } else if (arrayType == double[].class) {
                    return ((double[]) annotationParameterValue).clone();
                } else if (arrayType == float[].class) {
                    return ((float[]) annotationParameterValue).clone();
                } else if (arrayType == int[].class) {
                    return ((int[]) annotationParameterValue).clone();
                } else if (arrayType == long[].class) {
                    return ((long[]) annotationParameterValue).clone();
                } else if (arrayType == short[].class) {
                    return ((short[]) annotationParameterValue).clone();
                } else if (arrayType == boolean[].class) {
                    return ((boolean[]) annotationParameterValue).clone();
                } else {
                    // Handle arrays of nested annotation types
                    final Object[] arr = (Object[]) annotationParameterValue;
                    return arr.clone();
                }
            }
            return annotationParameterValue;
        }
    }

    void convertWrapperArraysToPrimitiveArrays() {
        if (annotationParamValues != null) {
            annotationParamValues.convertWrapperArraysToPrimitiveArrays(getClassInfo());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final AnnotationInfo o) {
        final int diff = getName().compareTo(o.getName());
        if (diff != 0) {
            return diff;
        }
        if (annotationParamValues == null && o.annotationParamValues == null) {
            return 0;
        } else if (annotationParamValues == null) {
            return -1;
        } else if (o.annotationParamValues == null) {
            return 1;
        } else {
            for (int i = 0, max = Math.max(annotationParamValues.size(),
                    o.annotationParamValues.size()); i < max; i++) {
                if (i >= annotationParamValues.size()) {
                    return -1;
                } else if (i >= o.annotationParamValues.size()) {
                    return 1;
                } else {
                    final int diff2 = annotationParamValues.get(i).compareTo(o.annotationParamValues.get(i));
                    if (diff2 != 0) {
                        return diff2;
                    }
                }
            }
        }
        return 0;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof AnnotationInfo)) {
            return false;
        }
        final AnnotationInfo o = (AnnotationInfo) obj;
        return this.compareTo(o) == 0;
    }

    @Override
    public int hashCode() {
        int h = getName().hashCode();
        if (annotationParamValues != null) {
            for (int i = 0; i < annotationParamValues.size(); i++) {
                final AnnotationParameterValue e = annotationParamValues.get(i);
                h = h * 7 + e.getName().hashCode() * 3 + e.getValue().hashCode();
            }
        }
        return h;
    }

    /**
     * Render as a string, into a StringBuilder buffer.
     * 
     * @param buf
     *            The buffer.
     */
    void toString(final StringBuilder buf) {
        buf.append("@" + getName());
        if (annotationParamValues != null && !annotationParamValues.isEmpty()) {
            buf.append('(');
            for (int i = 0; i < annotationParamValues.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final AnnotationParameterValue annotationParamValue = annotationParamValues.get(i);
                if (annotationParamValues.size() > 1 || !"value".equals(annotationParamValue.getName())) {
                    annotationParamValue.toString(buf);
                } else {
                    annotationParamValue.toStringParamValueOnly(buf);
                }
            }
            buf.append(')');
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(buf);
        return buf.toString();
    }
}
