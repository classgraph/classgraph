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
 * Copyright (c) 2026 Luke Hutchison
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

import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.annotation.Inherited;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** Holds metadata about a specific annotation instance on a class, method, method parameter or field. */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo>, HasName {
    /** The name. */
    private String name;

    /** The annotation param values. */
    private AnnotationParameterValueList annotationParamValues;

    /**
     * Set to true once any {@code Object[]} arrays of boxed values or strings in {@link #annotationParamValues}
     * have been lazily converted to arrays of their element type.
     */
    private transient volatile boolean annotationParamValuesHasBeenConvertedToPrimitive;

    /** The annotation parameter values including defaults, or null if not yet built. */
    private transient volatile AnnotationParameterValueList annotationParamValuesWithDefaults;

    /** Default constructor for deserialization. */
    AnnotationInfo() {
        super();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param name
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    AnnotationInfo(final String name, final AnnotationParameterValueList annotationParamValues) {
        super();
        this.name = name;
        this.annotationParamValues = annotationParamValues;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name.
     *
     * @return The name of the annotation class.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Checks if the annotation is inherited.
     *
     * @return true if this annotation is meta-annotated with {@link Inherited}. If the annotation class was not
     *         scanned, this is not known, so false is returned.
     */
    public boolean isInherited() {
        // The annotation class is not scanned for a type annotation declared outside the accepted packages, unlike
        // a declaration annotation, which always gets at least a placeholder ClassInfo
        final ClassInfo classInfo = getClassInfo();
        return classInfo != null && classInfo.isInherited;
    }

    /**
     * Get the default parameter values.
     *
     * @return the list of default parameter values for this annotation, or the empty list if none. Default
     *         parameter values are declared by the annotation class, so if the annotation class was not scanned,
     *         the empty list is returned.
     */
    public AnnotationParameterValueList getDefaultParameterValues() {
        final ClassInfo classInfo = getClassInfo();
        return classInfo == null ? AnnotationParameterValueList.EMPTY_LIST
                : classInfo.getAnnotationDefaultParameterValues();
    }

    /**
     * Get the parameter values.
     *
     * @param includeDefaultValues
     *            if true, include default values for any annotation parameter value that is missing.
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition (if requested), or the empty list if none.
     */
    public AnnotationParameterValueList getParameterValues(final boolean includeDefaultValues) {
        if (scanResult == null) {
            // Scanning has not finished, so the annotation class may not have been read yet -- return the values
            // without defaults, and without converting arrays (happens when AnnotationInfo is logged during
            // scanning)
            return annotationParamValues == null ? AnnotationParameterValueList.EMPTY_LIST : annotationParamValues;
        }
        final ClassInfo classInfo = getClassInfo();
        convertDeclaredParamValues(classInfo);
        if (!includeDefaultValues || classInfo == null) {
            // Don't include defaults, or there are no defaults to include, since they are declared by the
            // annotation class, which was not scanned
            return annotationParamValues == null ? AnnotationParameterValueList.EMPTY_LIST : annotationParamValues;
        }
        // Read the field only once, since another thread may set it between two reads
        AnnotationParameterValueList paramValuesWithDefaults = annotationParamValuesWithDefaults;
        if (paramValuesWithDefaults == null) {
            // Check if one or both of the defaults and the values in this annotation instance are null (empty)
            final AnnotationParameterValueList defaultParamValues = classInfo
                    .getConvertedAnnotationDefaultParamValues();
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

            // Put annotation values in the same order as the annotation methods (there is one method for each
            // annotation constant)
            if (classInfo.methodInfo == null) {
                // Should not happen (when classfile is read, methods are always read, whether or not
                // scanSpec.enableMethodInfo is true)
                throw new IllegalArgumentException("Could not find methods for annotation " + classInfo.getName());
            }
            // Build the list before storing it, so that another thread never sees it partly built
            paramValuesWithDefaults = new AnnotationParameterValueList();
            for (final MethodInfo mi : classInfo.methodInfo) {
                final String paramName = mi.getName();
                switch (paramName) {
                // None of these method names should be present in the @interface class itself, it should only
                // contain methods for the annotation constants (but skip them anyway to be safe). These methods
                // should only exist in concrete instances of the annotation.
                case "<init>":
                case "<clinit>":
                case "hashCode":
                case "equals":
                case "toString":
                case "annotationType":
                    // Skip
                    break;
                default:
                    // Annotation constant
                    final Object paramValue = allParamValues.get(paramName);
                    // Annotation values cannot be null (or absent, from either defaults or annotation instance)
                    if (paramValue != null) {
                        paramValuesWithDefaults.add(new AnnotationParameterValue(paramName, paramValue));
                    }
                    break;
                }
            }
            annotationParamValuesWithDefaults = paramValuesWithDefaults;
        }
        return paramValuesWithDefaults;
    }

    /**
     * Get the parameter values.
     *
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition, or the empty list if none.
     */
    public AnnotationParameterValueList getParameterValues() {
        return getParameterValues(true);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the annotation class, for {@link #getClassInfo()}.
     *
     * @return the class name
     */
    @Override
    protected String getClassName() {
        return name;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue a : annotationParamValues) {
                a.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final LogNode log) {
        super.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue annotationParamValue : annotationParamValues) {
                annotationParamValue.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return the {@link ClassInfo} object for the annotation class. */
    @Override
    public ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    /**
     * Load the {@link Annotation} class corresponding to this {@link AnnotationInfo} object by calling
     * {@code getClassInfo().loadClass()}, then create a new dynamic proxy instance of the annotation with the
     * parameter values from this {@link AnnotationInfo} object (and default values from the annotation class for
     * any unspecified parameters).
     *
     * <p>
     * The returned proxy instance can be cast to the annotation type, and {@code instanceof} checks against the
     * annotation class will succeed.
     *
     * @return The new {@link Annotation} proxy instance.
     */
    public Annotation loadClassAndInstantiate() {
        final Class<? extends Annotation> annotationClass = getClassInfo().loadClass(Annotation.class);
        return (Annotation) Proxy.newProxyInstance(annotationClass.getClassLoader(),
                new Class<?>[] { annotationClass }, new AnnotationInvocationHandler(annotationClass, this));
    }

    /** {@link InvocationHandler} for dynamically instantiating an {@link Annotation} object. */
    private static class AnnotationInvocationHandler implements InvocationHandler {

        /** The annotation class. */
        private final Class<? extends Annotation> annotationClass;

        /** The {@link AnnotationInfo} object for this annotation. */
        private final AnnotationInfo annotationInfo;

        /** The annotation parameter values instantiated. */
        private final Map<String, Object> annotationParameterValuesInstantiated = new HashMap<>();

        /**
         * Constructor.
         *
         * @param annotationClass
         *            the annotation class
         * @param annotationInfo
         *            the annotation info
         */
        AnnotationInvocationHandler(final Class<? extends Annotation> annotationClass,
                final AnnotationInfo annotationInfo) {
            this.annotationClass = annotationClass;
            this.annotationInfo = annotationInfo;

            // Instantiate the annotation parameter values (this loads and gets references for class literals,
            // enum constants, etc.)
            for (final AnnotationParameterValue apv : annotationInfo.getParameterValues()) {
                final Object instantiatedValue = apv.instantiate(annotationInfo.getClassInfo());
                if (instantiatedValue == null) {
                    // Annotations cannot contain null values
                    throw new IllegalArgumentException("Got null value for annotation parameter " + apv.getName()
                            + " of annotation " + annotationInfo.name);
                }
                this.annotationParameterValuesInstantiated.put(apv.getName(), instantiatedValue);
            }
        }

        /* (non-Javadoc)
         * @see java.lang.reflect.InvocationHandler#invoke(java.lang.Object, java.lang.reflect.Method,
         * java.lang.Object[])
         */
        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            final String methodName = method.getName();
            final Class<?>[] paramTypes = method.getParameterTypes();
            if ((args == null ? 0 : args.length) != paramTypes.length) {
                throw new IllegalArgumentException(
                        "Wrong number of arguments for " + annotationClass.getName() + "." + methodName + ": got "
                                + (args == null ? 0 : args.length) + ", expected " + paramTypes.length);
            }
            if (args != null && paramTypes.length == 1) {
                if ("equals".equals(methodName) && paramTypes[0] == Object.class) {
                    // equals() needs to function the same as the JDK implementation
                    // (see src/share/classes/sun/reflect/annotation/AnnotationInvocationHandler.java in the JDK)
                    // N.B. compare against the proxy, not against this InvocationHandler, which is never
                    // the object that equals() was called with
                    if (proxy == args[0]) {
                        return true;
                    } else if (!annotationClass.isInstance(args[0])) {
                        return false;
                    }
                    final ReflectionUtils reflectionUtils = ScanResult
                            .getReflectionUtils(annotationInfo.scanResult);
                    for (final Entry<String, Object> ent : annotationParameterValuesInstantiated.entrySet()) {
                        final String paramName = ent.getKey();
                        final Object paramVal = ent.getValue();
                        final Object otherParamVal = reflectionUtils.invokeMethod(/* throwException = */ false,
                                args[0], paramName);
                        // Annotation values should never be null, but just to be safe
                        if (paramVal == null || otherParamVal == null) {
                            if (paramVal != otherParamVal) {
                                return false;
                            }
                        } else if (!paramVal.equals(otherParamVal)) {
                            return false;
                        }
                    }
                    return true;
                } else {
                    // .equals(Object) is the only method of an enum that can take one parameter
                    throw new IllegalArgumentException();
                }
            } else if (paramTypes.length == 0) {
                // Handle .toString(), .hashCode(), .annotationType()
                switch (methodName) {
                case "toString":
                    return annotationInfo.toString();
                case "hashCode": {
                    // hashCode() needs to function the same as the JDK implementation
                    // (see src/share/classes/sun/reflect/annotation/AnnotationInvocationHandler.java in the JDK)
                    int result = 0;
                    for (final Entry<String, Object> ent : annotationParameterValuesInstantiated.entrySet()) {
                        final String paramName = ent.getKey();
                        final Object paramVal = ent.getValue();
                        int paramValHashCode;
                        if (paramVal == null) {
                            // Annotation values should never be null, but just to be safe
                            paramValHashCode = 0;
                        } else {
                            final Class<?> type = paramVal.getClass();
                            if (!type.isArray()) {
                                paramValHashCode = paramVal.hashCode();
                            } else if (type == byte[].class) {
                                paramValHashCode = Arrays.hashCode((byte[]) paramVal);
                            } else if (type == char[].class) {
                                paramValHashCode = Arrays.hashCode((char[]) paramVal);
                            } else if (type == double[].class) {
                                paramValHashCode = Arrays.hashCode((double[]) paramVal);
                            } else if (type == float[].class) {
                                paramValHashCode = Arrays.hashCode((float[]) paramVal);
                            } else if (type == int[].class) {
                                paramValHashCode = Arrays.hashCode((int[]) paramVal);
                            } else if (type == long[].class) {
                                paramValHashCode = Arrays.hashCode((long[]) paramVal);
                            } else if (type == short[].class) {
                                paramValHashCode = Arrays.hashCode((short[]) paramVal);
                            } else if (type == boolean[].class) {
                                paramValHashCode = Arrays.hashCode((boolean[]) paramVal);
                            } else {
                                paramValHashCode = Arrays.hashCode((Object[]) paramVal);
                            }
                        }
                        result += (127 * paramName.hashCode()) ^ paramValHashCode;
                    }
                    return result;
                }
                case "annotationType":
                    return annotationClass;
                default:
                    // Fall through (other method names are used for returning annotation parameter values)
                    break;
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
            final Class<?> annotationParameterValueClass = annotationParameterValue.getClass();
            if (annotationParameterValueClass.isArray()) {
                // Handle array types
                if (annotationParameterValueClass == String[].class) {
                    return ((String[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == byte[].class) {
                    return ((byte[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == char[].class) {
                    return ((char[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == double[].class) {
                    return ((double[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == float[].class) {
                    return ((float[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == int[].class) {
                    return ((int[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == long[].class) {
                    return ((long[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == short[].class) {
                    return ((short[]) annotationParameterValue).clone();
                } else if (annotationParameterValueClass == boolean[].class) {
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

    /**
     * Get the {@link ClassInfo} object for the annotation class without throwing if the {@link ScanResult} has been
     * closed.
     *
     * @return the annotation class, or null if the {@link ScanResult} has not been set or the annotation class was
     *         not encountered during scanning.
     */
    private ClassInfo getClassInfoEvenIfClosed() {
        final ScanResult sr = scanResult;
        return sr == null ? null : sr.classNameToClassInfo.get(name);
    }

    /**
     * Lazily convert any {@code Object[]} arrays of boxed values or strings in the parameter values to arrays of
     * their element type, if the {@link ScanResult} has been set.
     *
     * @param classInfo
     *            the annotation class, or null if it was not encountered during scanning.
     */
    private void convertDeclaredParamValues(final ClassInfo classInfo) {
        if (scanResult != null && annotationParamValues != null
                && !annotationParamValuesHasBeenConvertedToPrimitive) {
            annotationParamValues.convertWrapperArraysToPrimitiveArrays(classInfo);
            annotationParamValuesHasBeenConvertedToPrimitive = true;
        }
    }

    /**
     * Convert wrapper arrays to primitive arrays.
     */
    void convertWrapperArraysToPrimitiveArrays() {
        if (annotationParamValues != null) {
            annotationParamValues.convertWrapperArraysToPrimitiveArrays(getClassInfoEvenIfClosed());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final AnnotationInfo o) {
        final int diff = this.name.compareTo(o.name);
        if (diff != 0) {
            return diff;
        }
        convertDeclaredParamValues(getClassInfoEvenIfClosed());
        o.convertDeclaredParamValues(o.getClassInfoEvenIfClosed());
        if (annotationParamValues == null && o.annotationParamValues == null) {
            return 0;
        } else if (annotationParamValues == null) {
            return -1;
        } else if (o.annotationParamValues == null) {
            return 1;
        } else {
            for (int i = 0,
                    max = Math.max(annotationParamValues.size(), o.annotationParamValues.size()); i < max; i++) {
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

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof AnnotationInfo)) {
            return false;
        }
        final AnnotationInfo other = (AnnotationInfo) obj;
        return this.compareTo(other) == 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        convertDeclaredParamValues(getClassInfoEvenIfClosed());
        int h = name.hashCode();
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue e : annotationParamValues) {
                // Hash an array-valued parameter by its elements, matching equals(Object), and in the same way
                // whether or not it has been converted from an Object[] array to a primitive array
                h = h * 7 + e.getName().hashCode() * 3 + Arrays.deepHashCode(new Object[] { e.getValue() });
            }
        }
        return h;
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        buf.append('@').append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
        final AnnotationParameterValueList paramVals = getParameterValues();
        if (!paramVals.isEmpty()) {
            buf.append('(');
            for (int i = 0; i < paramVals.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final AnnotationParameterValue paramVal = paramVals.get(i);
                if (paramVals.size() > 1 || !"value".equals(paramVal.getName())) {
                    paramVal.toString(useSimpleNames, buf);
                } else {
                    paramVal.toStringParamValueOnly(useSimpleNames, buf);
                }
            }
            buf.append(')');
        }
    }
}
