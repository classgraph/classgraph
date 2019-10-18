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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A union type, used for typesafe serialization/deserialization to/from JSON. Only one field is ever set. */
class ObjectTypedValueWrapper extends ScanResultObject {
    // Parameter value is split into different fields by type, so that serialization and deserialization
    // works properly (can't properly serialize a field of Object type, since the concrete type is not
    /** Enum value. */
    // stored in JSON).
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
            // Allocate array as either a generic Object[] array, if the element type could not be determined,
            // or as an array of specific element type, if the element type was determined. 
            final Object annotationValueObjectArray = eltClass == null ? new Object[objectArrayValue.length]
                    : Array.newInstance(eltClass, objectArrayValue.length);
            // Fill the array instance.
            for (int i = 0; i < objectArrayValue.length; i++) {
                if (objectArrayValue[i] != null) {
                    // Get the element value (may also cause the element to be instantiated)
                    final Object eltValue = objectArrayValue[i].instantiateOrGet(annotationClassInfo, paramName);
                    // Store the possibly-instantiated value in the array
                    Array.set(annotationValueObjectArray, i, eltValue);
                }
            }
            return annotationValueObjectArray;
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
     *         a String.
     */
    private Object getArrayValueClassOrName(final ClassInfo annotationClassInfo, final String paramName,
            final boolean getClass) {
        // Find the method in the annotation class with the same name as the annotation parameter.
        final MethodInfoList annotationMethodList = annotationClassInfo == null
                || annotationClassInfo.methodInfo == null ? null : annotationClassInfo.methodInfo.get(paramName);
        if (annotationClassInfo != null && annotationMethodList != null && !annotationMethodList.isEmpty()) {
            if (annotationMethodList.size() > 1) {
                // There should only be one method with a given name in an annotation
                throw new IllegalArgumentException("Duplicated annotation parameter method " + paramName + "()"
                        + " in annotation class " + annotationClassInfo.getName());
            }
            // Get the result type of the method with the same name as the annotation parameter 
            final TypeSignature annotationMethodResultTypeSig = annotationMethodList.get(0)
                    .getTypeSignatureOrTypeDescriptor().getResultType();
            // The result type has to be an array type 
            if (!(annotationMethodResultTypeSig instanceof ArrayTypeSignature)) {
                throw new IllegalArgumentException("Annotation parameter " + paramName + " in annotation class "
                        + annotationClassInfo.getName()
                        + " holds an array, but does not have an array type signature");
            }
            final ArrayTypeSignature arrayTypeSig = (ArrayTypeSignature) annotationMethodResultTypeSig;
            if (arrayTypeSig.getNumDimensions() != 1) {
                throw new IllegalArgumentException("Annotations only support 1-dimensional arrays");
            }
            final TypeSignature elementTypeSig = arrayTypeSig.getElementTypeSignature();
            if (elementTypeSig instanceof ClassRefTypeSignature) {
                // Look up the name of the element type, for non-primitive arrays 
                final ClassRefTypeSignature classRefTypeSignature = (ClassRefTypeSignature) elementTypeSig;
                return getClass ? classRefTypeSignature.loadClass()
                        : classRefTypeSignature.getFullyQualifiedClassName();
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
                    return elt.integerValue != null ? (getClass ? Integer.class : "int")
                            : elt.longValue != null ? (getClass ? Long.class : "long")
                                    : elt.shortValue != null ? (getClass ? Short.class : "short")
                                            : elt.characterValue != null ? (getClass ? Character.class : "char")
                                                    : elt.byteValue != null ? (getClass ? Byte.class : "byte")
                                                            : elt.booleanValue != null
                                                                    ? (getClass ? Boolean.class : "boolean")
                                                                    : elt.doubleValue != null
                                                                            ? (getClass ? Double.class : "double")
                                                                            : elt.floatValue != null
                                                                                    ? (getClass ? Float.class
                                                                                            : "float")
                                                                                    : (getClass ? elt.getClass()
                                                                                            : elt.getClass()
                                                                                                    .getName());
                }
            }
        }
        // Could not determine the element type -- just use Object
        return getClass ? Object.class : "java.lang.Object";
    }

    /**
     * Replace Object[] arrays containing boxed types with primitive arrays.
     *
     * @param annotationClassInfo
     *            annotation class info
     * @param paramName
     *            the param name
     */
    void convertWrapperArraysToPrimitiveArrays(final ClassInfo annotationClassInfo, final String paramName) {
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
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    intArrayValue[j] = objectArrayValue[j].integerValue;
                }
                objectArrayValue = null;
                break;
            case "long":
                longArrayValue = new long[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    longArrayValue[j] = objectArrayValue[j].longValue;
                }
                objectArrayValue = null;
                break;
            case "short":
                shortArrayValue = new short[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    shortArrayValue[j] = objectArrayValue[j].shortValue;
                }
                objectArrayValue = null;
                break;
            case "char":
                charArrayValue = new char[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    charArrayValue[j] = objectArrayValue[j].characterValue;
                }
                objectArrayValue = null;
                break;
            case "float":
                floatArrayValue = new float[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    floatArrayValue[j] = objectArrayValue[j].floatValue;
                }
                objectArrayValue = null;
                break;
            case "double":
                doubleArrayValue = new double[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    doubleArrayValue[j] = objectArrayValue[j].doubleValue;
                }
                objectArrayValue = null;
                break;
            case "boolean":
                booleanArrayValue = new boolean[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
                    booleanArrayValue[j] = objectArrayValue[j].booleanValue;
                }
                objectArrayValue = null;
                break;
            case "byte":
                byteArrayValue = new byte[objectArrayValue.length];
                for (int j = 0; j < objectArrayValue.length; j++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside whitelist>"
                                        : annotationClassInfo.getName()));
                    }
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
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        if (annotationEnumValue != null) {
            annotationEnumValue.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        } else if (annotationClassRef != null) {
            final ClassInfo classInfo = annotationClassRef.getClassInfo();
            if (classInfo != null) {
                refdClassInfo.add(classInfo);
            }
        } else if (annotationInfo != null) {
            annotationInfo.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        } else if (objectArrayValue != null) {
            for (final ObjectTypedValueWrapper item : objectArrayValue) {
                item.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
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
                && Arrays.equals(floatArrayValue, o.floatArrayValue)
                && Arrays.equals(byteArrayValue, o.byteArrayValue)
                && Arrays.deepEquals(objectArrayValue, o.objectArrayValue);
    }
}