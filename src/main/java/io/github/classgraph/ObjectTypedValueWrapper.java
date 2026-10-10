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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nonapi.io.github.classgraph.utils.LogNode;

/** A union type, used for typesafe serialization/deserialization to/from JSON. Only one field is ever set. */
class ObjectTypedValueWrapper extends ScanResultObject {
    // Parameter value is split into different fields by type, so that serialization and deserialization
    // works properly (can't properly serialize a field of Object type, since the concrete type is not
    // stored in JSON).
    // TODO: remove this class once JSON serialization is removed

    /** Enum value. */
    private AnnotationEnumValue annotationEnumValue;

    /** Class ref. */
    private AnnotationClassRef annotationClassRef;

    /** AnnotationInfo. */
    private AnnotationInfo annotationInfo;

    /** String value. */
    private String stringValue;

    /** Integer value. */
    private Integer integerValue;

    /** Long value. */
    private Long longValue;

    /** Short value. */
    private Short shortValue;

    /** Boolean value. */
    private Boolean booleanValue;

    /** Character value. */
    private Character characterValue;

    /** Float value. */
    private Float floatValue;

    /** Double value. */
    private Double doubleValue;

    /** Byte value. */
    private Byte byteValue;

    /** String array value. */
    private String[] stringArrayValue;

    /** Int array value. */
    private int[] intArrayValue;

    /** Long array value. */
    private long[] longArrayValue;

    /** Short array value. */
    private short[] shortArrayValue;

    /** Boolean array value. */
    private boolean[] booleanArrayValue;

    /** Char array value. */
    private char[] charArrayValue;

    /** Float array value. */
    private float[] floatArrayValue;

    /** Double array value. */
    private double[] doubleArrayValue;

    /** Byte array value. */
    private byte[] byteArrayValue;

    /** Object array value. */
    private ObjectTypedValueWrapper[] objectArrayValue;

    // -------------------------------------------------------------------------------------------------------------

    /** Default constructor for deserialization. */
    public ObjectTypedValueWrapper() {
        super();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param annotationParamValue
     *            annotation parameter value
     */
    public ObjectTypedValueWrapper(final Object annotationParamValue) {
        super();
        if (annotationParamValue != null) {
            final Class<?> annotationParameterValueClass = annotationParamValue.getClass();
            if (annotationParameterValueClass.isArray()) {
                // Support for 1D primitive and string arrays is needed for annotation parameter values
                if (annotationParameterValueClass == String[].class) {
                    stringArrayValue = (String[]) annotationParamValue;
                } else if (annotationParameterValueClass == int[].class) {
                    intArrayValue = (int[]) annotationParamValue;
                } else if (annotationParameterValueClass == long[].class) {
                    longArrayValue = (long[]) annotationParamValue;
                } else if (annotationParameterValueClass == short[].class) {
                    shortArrayValue = (short[]) annotationParamValue;
                } else if (annotationParameterValueClass == boolean[].class) {
                    booleanArrayValue = (boolean[]) annotationParamValue;
                } else if (annotationParameterValueClass == char[].class) {
                    charArrayValue = (char[]) annotationParamValue;
                } else if (annotationParameterValueClass == float[].class) {
                    floatArrayValue = (float[]) annotationParamValue;
                } else if (annotationParameterValueClass == double[].class) {
                    doubleArrayValue = (double[]) annotationParamValue;
                } else if (annotationParameterValueClass == byte[].class) {
                    byteArrayValue = (byte[]) annotationParamValue;
                } else {
                    // Object array type -- wrap each individual element
                    final int n = Array.getLength(annotationParamValue);
                    objectArrayValue = new ObjectTypedValueWrapper[n];
                    for (int i = 0; i < n; i++) {
                        objectArrayValue[i] = new ObjectTypedValueWrapper(Array.get(annotationParamValue, i));
                    }
                }
            } else if (annotationParamValue instanceof AnnotationEnumValue) {
                annotationEnumValue = (AnnotationEnumValue) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationClassRef) {
                annotationClassRef = (AnnotationClassRef) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationInfo) {
                annotationInfo = (AnnotationInfo) annotationParamValue;
            } else if (annotationParamValue instanceof String) {
                stringValue = (String) annotationParamValue;
            } else if (annotationParamValue instanceof Integer) {
                integerValue = (Integer) annotationParamValue;
            } else if (annotationParamValue instanceof Long) {
                longValue = (Long) annotationParamValue;
            } else if (annotationParamValue instanceof Short) {
                shortValue = (Short) annotationParamValue;
            } else if (annotationParamValue instanceof Boolean) {
                booleanValue = (Boolean) annotationParamValue;
            } else if (annotationParamValue instanceof Character) {
                characterValue = (Character) annotationParamValue;
            } else if (annotationParamValue instanceof Float) {
                floatValue = (Float) annotationParamValue;
            } else if (annotationParamValue instanceof Double) {
                doubleValue = (Double) annotationParamValue;
            } else if (annotationParamValue instanceof Byte) {
                byteValue = (Byte) annotationParamValue;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported annotation parameter value type: " + annotationParameterValueClass.getName());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Instantiate or get the wrapped value.
     *
     * @param annotationClassInfo
     *            if non-null, instantiate this object as a parameter value of this annotation class.
     * @param paramName
     *            if non-null, instantiate this object as a value of this named parameter.
     * @return The value wrapped by this wrapper class.
     */
    Object instantiateOrGet(final ClassInfo annotationClassInfo, final String paramName) {
        final boolean instantiate = annotationClassInfo != null;
        if (annotationEnumValue != null) {
            return instantiate ? annotationEnumValue.loadClassAndReturnEnumValue() : annotationEnumValue;
        } else if (annotationClassRef != null) {
            return instantiate ? annotationClassRef.loadClass() : annotationClassRef;
        } else if (annotationInfo != null) {
            return instantiate ? annotationInfo.loadClassAndInstantiate() : annotationInfo;
        } else if (stringValue != null) {
            return stringValue;
        } else if (integerValue != null) {
            return integerValue;
        } else if (longValue != null) {
            return longValue;
        } else if (shortValue != null) {
            return shortValue;
        } else if (booleanValue != null) {
            return booleanValue;
        } else if (characterValue != null) {
            return characterValue;
        } else if (floatValue != null) {
            return floatValue;
        } else if (doubleValue != null) {
            return doubleValue;
        } else if (byteValue != null) {
            return byteValue;
        } else if (stringArrayValue != null) {
            return stringArrayValue;
        } else if (intArrayValue != null) {
            return intArrayValue;
        } else if (longArrayValue != null) {
            return longArrayValue;
        } else if (shortArrayValue != null) {
            return shortArrayValue;
        } else if (booleanArrayValue != null) {
            return booleanArrayValue;
        } else if (charArrayValue != null) {
            return charArrayValue;
        } else if (floatArrayValue != null) {
            return floatArrayValue;
        } else if (doubleArrayValue != null) {
            return doubleArrayValue;
        } else if (byteArrayValue != null) {
            return byteArrayValue;
        } else if (objectArrayValue != null) {
            // Get the element type of the array
            final Class<?> eltClass = instantiate
                    ? (Class<?>) getArrayValueClassOrName(annotationClassInfo, paramName, /* getClass = */ true)
                    : null;
            // Get the element values (may also cause the elements to be instantiated)
            final Object[] eltValues = new Object[objectArrayValue.length];
            for (int i = 0; i < objectArrayValue.length; i++) {
                if (objectArrayValue[i] != null) {
                    eltValues[i] = objectArrayValue[i].instantiateOrGet(annotationClassInfo, paramName);
                }
            }
            if (eltClass != null) {
                // Copy the values into an array of the element type, if the element type was determined
                final Object annotationValueArray = Array.newInstance(eltClass, eltValues.length);
                try {
                    for (int i = 0; i < eltValues.length; i++) {
                        if (eltValues[i] != null) {
                            Array.set(annotationValueArray, i, eltValues[i]);
                        }
                    }
                    return annotationValueArray;
                } catch (final IllegalArgumentException e) {
                    // An element cannot be stored in an array of the element type, for example because the
                    // annotation class was recompiled with another element type after the annotated class was
                    // compiled. Like the JDK, which reports a type mismatch only when this value is read, return
                    // the Object[] array, so that the other parameter values can still be read.
                }
            }
            return eltValues;
        } else {
            return null;
        }
    }

    /**
     * Get the value wrapped by this wrapper class.
     *
     * @return The value wrapped by this wrapper class.
     */
    public Object get() {
        return instantiateOrGet(null, null);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the element type of an array element.
     *
     * @param annotationClassInfo
     *            annotation class
     * @param paramName
     *            the parameter name
     * @param getClass
     *            If true, return a {@code Class<?>} reference, otherwise return the class name.
     * @return the array value type as a {@code Class<?>} reference if getClass is true, otherwise the class name as
     *         a String, or null if the annotation class does not declare a one-dimensional array type for this
     *         parameter, so that the array cannot be converted.
     */
    private Object getArrayValueClassOrName(final ClassInfo annotationClassInfo, final String paramName,
            final boolean getClass) {
        // Find the method in the annotation class with the same name as the annotation parameter.
        final MethodInfoList annotationMethodList = annotationClassInfo == null
                || annotationClassInfo.methodInfo == null ? null : annotationClassInfo.methodInfo.get(paramName);
        if (annotationClassInfo != null && annotationMethodList != null && !annotationMethodList.isEmpty()) {
            // The annotation class may have been recompiled after the annotated class was compiled, so that the
            // parameter no longer has a one-dimensional array type, or the annotation class may not be valid
            // (javac cannot write two methods with the same name in an annotation class). The JDK reports such a
            // value as a type mismatch only when it is read, so leave the value unconverted.
            if (annotationMethodList.size() > 1) {
                return null;
            }
            // Get the result type of the method with the same name as the annotation parameter
            final TypeSignature annotationMethodResultTypeSig = annotationMethodList.get(0)
                    .getTypeSignatureOrTypeDescriptor().getResultType();
            if (!(annotationMethodResultTypeSig instanceof ArrayTypeSignature)
                    || ((ArrayTypeSignature) annotationMethodResultTypeSig).getNumDimensions() != 1) {
                return null;
            }
            final ArrayTypeSignature arrayTypeSig = (ArrayTypeSignature) annotationMethodResultTypeSig;
            final TypeSignature elementTypeSig = arrayTypeSig.getElementTypeSignature();
            if (elementTypeSig instanceof ClassRefTypeSignature) {
                // Look up the name of the element type, for non-primitive arrays
                final ClassRefTypeSignature classRefTypeSignature = (ClassRefTypeSignature) elementTypeSig;
                return getClass ? classRefTypeSignature.loadClass() : classRefTypeSignature.getClassName();
            } else if (elementTypeSig instanceof BaseTypeSignature) {
                // Look up the name of the primitive class, for primitive arrays
                final BaseTypeSignature baseTypeSignature = (BaseTypeSignature) elementTypeSig;
                return getClass ? baseTypeSignature.getType() : baseTypeSignature.getTypeStr();
            }
        } else {
            // Could not find a method with this name -- this is an external class.
            // Find first non-null object in array, and use its type as the element type of the array.
            for (final ObjectTypedValueWrapper elt : objectArrayValue) {
                if (elt != null) {
                    // Primitive typed arrays will be turned into arrays of boxed types
                    if (elt.stringValue != null) {
                        return getClass ? String.class : "java.lang.String";
                    } else if (elt.integerValue != null) {
                        return getClass ? Integer.class : "int";
                    } else if (elt.longValue != null) {
                        return getClass ? Long.class : "long";
                    } else if (elt.shortValue != null) {
                        return getClass ? Short.class : "short";
                    } else if (elt.characterValue != null) {
                        return getClass ? Character.class : "char";
                    } else if (elt.byteValue != null) {
                        return getClass ? Byte.class : "byte";
                    } else if (elt.booleanValue != null) {
                        return getClass ? Boolean.class : "boolean";
                    } else if (elt.doubleValue != null) {
                        return getClass ? Double.class : "double";
                    } else if (elt.floatValue != null) {
                        return getClass ? Float.class : "float";
                    } else {
                        // The element type could not be determined (the element is an enum value, a class
                        // reference or a nested annotation) -- fall through and use Object as the element type.
                        // N.B. don't use the type of the wrapper object itself here, since the array is filled
                        // with the values that the wrappers wrap, not with the wrappers.
                        break;
                    }
                }
            }
        }
        // Could not determine the element type -- just use Object
        return getClass ? Object.class : "java.lang.Object";
    }

    /**
     * Replace Object[] arrays containing boxed types with primitive arrays. If the annotation class does not
     * declare a one-dimensional array type for the parameter, or an element does not have the declared element
     * type, the Object[] array is kept.
     *
     * <p>
     * Two threads can read the same annotation parameter value for the first time at once, and both then convert
     * it. The conversion writes several fields one after another, so it is synchronized: the second thread waits
     * until the first has finished, and then finds the value already converted. A thread that has called this
     * method also sees the converted value, since nothing changes it afterwards.
     *
     * @param annotationClassInfo
     *            annotation class info
     * @param paramName
     *            the param name
     */
    synchronized void convertWrapperArraysToPrimitiveArrays(final ClassInfo annotationClassInfo,
            final String paramName) {
        if (annotationInfo != null) {
            // Recursively convert primitive arrays in nested annotations
            annotationInfo.convertWrapperArraysToPrimitiveArrays();
        } else if (objectArrayValue != null) {
            for (final ObjectTypedValueWrapper elt : objectArrayValue) {
                if (elt.annotationInfo != null) {
                    // Recurse
                    elt.annotationInfo.convertWrapperArraysToPrimitiveArrays();
                }
            }

            if (objectArrayValue.getClass().getComponentType().isArray()) {
                // More than one array dimension -- not possible for annotation parameter values => skip
                return;
            }

            // Find the method in the annotation class with the same name as the annotation parameter.
            final String targetElementTypeName = (String) getArrayValueClassOrName(annotationClassInfo, paramName,
                    /* getClass = */ false);
            if (targetElementTypeName == null) {
                return;
            }
            for (final ObjectTypedValueWrapper elt : objectArrayValue) {
                if (!elt.holdsValueOfType(targetElementTypeName)) {
                    // The element does not have the element type, for example because the annotation class was
                    // recompiled with another element type after the annotated class was compiled. Like the JDK,
                    // which reports a type mismatch only when this value is read, keep the Object[] array.
                    return;
                }
            }

            // Get array element type for 1D non-primitive arrays, and convert to a primitive array
            switch (targetElementTypeName) {
            case "java.lang.String":
                // Convert Object[] array containing String objects to String[] array
                stringArrayValue = new String[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    stringArrayValue[j] = objectArrayValue[j].stringValue;
                }
                objectArrayValue = null;
                break;
            case "int":
                intArrayValue = new int[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    intArrayValue[j] = objectArrayValue[j].integerValue;
                }
                objectArrayValue = null;
                break;
            case "long":
                longArrayValue = new long[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    longArrayValue[j] = objectArrayValue[j].longValue;
                }
                objectArrayValue = null;
                break;
            case "short":
                shortArrayValue = new short[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    shortArrayValue[j] = objectArrayValue[j].shortValue;
                }
                objectArrayValue = null;
                break;
            case "char":
                charArrayValue = new char[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    charArrayValue[j] = objectArrayValue[j].characterValue;
                }
                objectArrayValue = null;
                break;
            case "float":
                floatArrayValue = new float[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    floatArrayValue[j] = objectArrayValue[j].floatValue;
                }
                objectArrayValue = null;
                break;
            case "double":
                doubleArrayValue = new double[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    doubleArrayValue[j] = objectArrayValue[j].doubleValue;
                }
                objectArrayValue = null;
                break;
            case "boolean":
                booleanArrayValue = new boolean[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    booleanArrayValue[j] = objectArrayValue[j].booleanValue;
                }
                objectArrayValue = null;
                break;
            case "byte":
                byteArrayValue = new byte[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    byteArrayValue[j] = objectArrayValue[j].byteValue;
                }
                objectArrayValue = null;
                break;
            default:
                // Leave objectArrayValue as-is
                break;
            }
        }
    }

    /**
     * Check whether this wrapper holds a single value that can be stored in an array of the named element type.
     *
     * @param elementTypeName
     *            the element type name, as returned by {@code getArrayValueClassOrName}
     * @return true if this wrapper holds a value of the named type, or if the named type is not a primitive type
     *         or String (an array of such a type is not converted).
     */
    private boolean holdsValueOfType(final String elementTypeName) {
        switch (elementTypeName) {
        case "java.lang.String":
            return stringValue != null;
        case "int":
            return integerValue != null;
        case "long":
            return longValue != null;
        case "short":
            return shortValue != null;
        case "char":
            return characterValue != null;
        case "float":
            return floatValue != null;
        case "double":
            return doubleValue != null;
        case "boolean":
            return booleanValue != null;
        case "byte":
            return byteValue != null;
        default:
            return true;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationEnumValue != null) {
            annotationEnumValue.setScanResult(scanResult);
        } else if (annotationClassRef != null) {
            annotationClassRef.setScanResult(scanResult);
        } else if (annotationInfo != null) {
            annotationInfo.setScanResult(scanResult);
        } else if (objectArrayValue != null) {
            for (final ObjectTypedValueWrapper anObjectArrayValue : objectArrayValue) {
                if (anObjectArrayValue != null) {
                    anObjectArrayValue.setScanResult(scanResult);
                }
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in annotation parameters.
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
        if (annotationEnumValue != null) {
            annotationEnumValue.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } else if (annotationClassRef != null) {
            final ClassInfo classInfo = annotationClassRef.getClassInfo();
            if (classInfo != null) {
                refdClassInfo.add(classInfo);
            }
        } else if (annotationInfo != null) {
            annotationInfo.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } else if (objectArrayValue != null) {
            for (final ObjectTypedValueWrapper item : objectArrayValue) {
                item.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(annotationEnumValue, annotationClassRef, annotationInfo, stringValue, integerValue,
                longValue, shortValue, booleanValue, characterValue, floatValue, doubleValue, byteValue,
                Arrays.hashCode(stringArrayValue), Arrays.hashCode(intArrayValue), Arrays.hashCode(longArrayValue),
                Arrays.hashCode(shortArrayValue), Arrays.hashCode(booleanArrayValue),
                Arrays.hashCode(charArrayValue), Arrays.hashCode(floatArrayValue),
                Arrays.hashCode(doubleArrayValue), Arrays.hashCode(byteArrayValue),
                Arrays.hashCode(objectArrayValue));
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object other) {
        if (other == this) {
            return true;
        } else if (!(other instanceof ObjectTypedValueWrapper)) {
            return false;
        }
        final ObjectTypedValueWrapper o = (ObjectTypedValueWrapper) other;
        return Objects.equals(annotationEnumValue, o.annotationEnumValue)
                && Objects.equals(annotationClassRef, o.annotationClassRef)
                && Objects.equals(annotationInfo, o.annotationInfo) && Objects.equals(stringValue, o.stringValue)
                && Objects.equals(integerValue, o.integerValue) && Objects.equals(longValue, o.longValue)
                && Objects.equals(shortValue, o.shortValue) && Objects.equals(booleanValue, o.booleanValue)
                && Objects.equals(characterValue, o.characterValue) && Objects.equals(floatValue, o.floatValue)
                && Objects.equals(doubleValue, o.doubleValue) && Objects.equals(byteValue, o.byteValue)
                && Arrays.equals(stringArrayValue, o.stringArrayValue)
                && Arrays.equals(intArrayValue, o.intArrayValue) && Arrays.equals(longArrayValue, o.longArrayValue)
                && Arrays.equals(shortArrayValue, o.shortArrayValue)
                && Arrays.equals(booleanArrayValue, o.booleanArrayValue)
                && Arrays.equals(charArrayValue, o.charArrayValue)
                && Arrays.equals(floatArrayValue, o.floatArrayValue)
                && Arrays.equals(doubleArrayValue, o.doubleArrayValue)
                && Arrays.equals(byteArrayValue, o.byteArrayValue)
                && Arrays.deepEquals(objectArrayValue, o.objectArrayValue);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        if (annotationEnumValue != null) {
            annotationEnumValue.toString(useSimpleNames, buf);
        } else if (annotationClassRef != null) {
            annotationClassRef.toString(useSimpleNames, buf);
        } else if (annotationInfo != null) {
            annotationInfo.toString(useSimpleNames, buf);
        } else if (stringValue != null) {
            buf.append(stringValue);
        } else if (integerValue != null) {
            buf.append(integerValue);
        } else if (longValue != null) {
            buf.append(longValue);
        } else if (shortValue != null) {
            buf.append(shortValue);
        } else if (booleanValue != null) {
            buf.append(booleanValue);
        } else if (characterValue != null) {
            buf.append(characterValue);
        } else if (floatValue != null) {
            buf.append(floatValue);
        } else if (doubleValue != null) {
            buf.append(doubleValue);
        } else if (byteValue != null) {
            buf.append(byteValue);
        } else if (stringArrayValue != null) {
            buf.append(Arrays.toString(stringArrayValue));
        } else if (intArrayValue != null) {
            buf.append(Arrays.toString(intArrayValue));
        } else if (longArrayValue != null) {
            buf.append(Arrays.toString(longArrayValue));
        } else if (shortArrayValue != null) {
            buf.append(Arrays.toString(shortArrayValue));
        } else if (booleanArrayValue != null) {
            buf.append(Arrays.toString(booleanArrayValue));
        } else if (charArrayValue != null) {
            buf.append(Arrays.toString(charArrayValue));
        } else if (floatArrayValue != null) {
            buf.append(Arrays.toString(floatArrayValue));
        } else if (doubleArrayValue != null) {
            buf.append(Arrays.toString(doubleArrayValue));
        } else if (byteArrayValue != null) {
            buf.append(Arrays.toString(byteArrayValue));
        } else if (objectArrayValue != null) {
            // TODO this doesn't handle nested arrays, but this toString() method is only used for debugging
            buf.append(Arrays.toString(objectArrayValue));
        }
    }
}