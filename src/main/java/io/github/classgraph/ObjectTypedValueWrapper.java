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

import java.lang.reflect.Array;
import java.util.Set;

/** A union type, used for typesafe serialization/deserialization to/from JSON. Only one field is ever set. */
class ObjectTypedValueWrapper extends ScanResultObject {
    // Parameter value is split into different fields by type, so that serialization and deserialization
    // works properly (can't properly serialize a field of Object type, since the concrete type is not
    // stored in JSON).
    private AnnotationEnumValue enumValue;
    private AnnotationClassRef classRef;
    private AnnotationInfo annotationInfo;
    private String stringValue;
    private Integer integerValue;
    private Long longValue;
    private Short shortValue;
    private Boolean booleanValue;
    private Character characterValue;
    private Float floatValue;
    private Double doubleValue;
    private Byte byteValue;
    private String[] stringArrayValue;
    private int[] intArrayValue;
    private long[] longArrayValue;
    private short[] shortArrayValue;
    private boolean[] booleanArrayValue;
    private char[] charArrayValue;
    private float[] floatArrayValue;
    private double[] doubleArrayValue;
    private byte[] byteArrayValue;
    private ObjectTypedValueWrapper[] objectArrayValue;

    // -------------------------------------------------------------------------------------------------------------

    /** Default constructor for deserialization. */
    public ObjectTypedValueWrapper() {
    }

    public ObjectTypedValueWrapper(final Object annotationParamValue) {
        if (annotationParamValue != null) {
            final Class<? extends Object> annotationParameterValueClass = annotationParamValue.getClass();
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
                enumValue = (AnnotationEnumValue) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationClassRef) {
                classRef = (AnnotationClassRef) annotationParamValue;
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
     * @param annotationClassInfo
     *            if non-null, instantiate this object as a parameter value of this annotation class.
     * @param paramName
     *            if non-null, instantiate this object as a value of this named parameter.
     * @return The value wrapped by this wrapper class.
     */
    Object instantiateOrGet(final ClassInfo annotationClassInfo, final String paramName) {
        final boolean instantiate = annotationClassInfo != null;
        if (enumValue != null) {
            return instantiate ? enumValue.loadClassAndReturnEnumValue() : enumValue;
        } else if (classRef != null) {
            return instantiate ? classRef.loadClass() : classRef;
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
            Class<?> eltClass = null;
            if (instantiate) {
                // Find the method in the annotation class with the same name as the annotation parameter.
                final MethodInfoList annotationMethodList = annotationClassInfo.methodInfo == null ? null
                        : annotationClassInfo.methodInfo.get(paramName);
                if (annotationMethodList != null && annotationMethodList.size() > 1) {
                    // There should only be one method with a given name in an annotation
                    throw new IllegalArgumentException("Duplicated annotation parameter method " + paramName
                            + "() in annotation class " + annotationClassInfo.getName());
                } else if (annotationMethodList != null && annotationMethodList.size() == 1) {
                    // Get the result type of the method with the same name as the annotation parameter 
                    final TypeSignature annotationMethodResultTypeSig = annotationMethodList.get(0)
                            .getTypeSignatureOrTypeDescriptor().getResultType();
                    // The result type has to be an array type 
                    if (!(annotationMethodResultTypeSig instanceof ArrayTypeSignature)) {
                        throw new IllegalArgumentException("Annotation parameter " + paramName
                                + " in annotation class " + annotationClassInfo.getName()
                                + " holds an array, but does not have an array type signature");
                    }
                    final ArrayTypeSignature arrayTypeSig = (ArrayTypeSignature) annotationMethodResultTypeSig;
                    if (arrayTypeSig.getNumDimensions() != 1) {
                        throw new IllegalArgumentException("Annotations only support 1-dimensional arrays");
                    }
                    final TypeSignature elementTypeSig = arrayTypeSig.getElementTypeSignature();
                    if (elementTypeSig instanceof ClassRefTypeSignature) {
                        // Look up the name of the element type, for non-primitive arrays 
                        eltClass = ((ClassRefTypeSignature) elementTypeSig).loadClass();
                    } else if (elementTypeSig instanceof BaseTypeSignature) {
                        // Look up the name of the primitive class, for primitive arrays
                        eltClass = ((BaseTypeSignature) elementTypeSig).getType();
                    }
                } else {
                    // Could not find a method with this name -- this is an external class.
                    // Find first non-null object in array, and use its type as the type of the array.
                    for (int i = 0; i < objectArrayValue.length; i++) {
                        final ObjectTypedValueWrapper elt = objectArrayValue[i];
                        if (elt != null) {
                            eltClass = elt.integerValue != null ? Integer.class
                                    : elt.longValue != null ? Long.class
                                            : elt.shortValue != null ? Short.class
                                                    : elt.characterValue != null ? Character.class
                                                            : elt.byteValue != null ? Byte.class
                                                                    : elt.booleanValue != null ? Boolean.class
                                                                            : elt.doubleValue != null ? Double.class
                                                                                    : elt.floatValue != null
                                                                                            ? Float.class
                                                                                            : null;
                        }
                    }
                }
            }
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
     * @return The value wrapped by this wrapper class.
     */
    public Object get() {
        return instantiateOrGet(null, null);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Replace Object[] arrays containing boxed types with primitive arrays. */
    void convertWrapperArraysToPrimitiveArrays(final ClassInfo annotationClassInfo, final String paramName) {
        if (annotationInfo != null) {
            // Recursively convert primitive arrays in nested annotations
            annotationInfo.convertWrapperArraysToPrimitiveArrays();
        } else if (objectArrayValue != null) {
            for (int i = 0; i < objectArrayValue.length; i++) {
                final ObjectTypedValueWrapper elt = objectArrayValue[i];
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
            String targetElementTypeName = "";
            final MethodInfoList annotationMethodList = annotationClassInfo.methodInfo == null ? null
                    : annotationClassInfo.methodInfo.get(paramName);
            if (annotationMethodList != null && annotationMethodList.size() > 1) {
                // There should only be one method with a given name in an annotation
                throw new IllegalArgumentException("Duplicated annotation parameter " + paramName
                        + " in annotation class " + annotationClassInfo.getName());
            } else if (annotationMethodList != null && annotationMethodList.size() == 1) {
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
                    targetElementTypeName = ((ClassRefTypeSignature) elementTypeSig).getFullyQualifiedClassName();
                } else if (elementTypeSig instanceof BaseTypeSignature) {
                    // Look up the name of the primitive class, for primitive arrays
                    targetElementTypeName = ((BaseTypeSignature) elementTypeSig).getTypeStr();
                }
            } else {
                // Could not find a method with this name -- this is an external class.
                // Find first non-null object in array, and use its type as the type of the array.
                for (int i = 0; i < objectArrayValue.length; i++) {
                    final ObjectTypedValueWrapper elt = objectArrayValue[i];
                    if (elt != null) {
                        targetElementTypeName = elt.integerValue != null ? "int"
                                : elt.longValue != null ? "long"
                                        : elt.shortValue != null ? "short"
                                                : elt.characterValue != null ? "char"
                                                        : elt.byteValue != null ? "byte"
                                                                : elt.booleanValue != null ? "boolean"
                                                                        : elt.doubleValue != null ? "double"
                                                                                : elt.floatValue != null ? "float"
                                                                                        : "";
                    }
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
                    final ObjectTypedValueWrapper elt = objectArrayValue[j];
                    if (elt == null) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + targetElementTypeName + " in parameter " + paramName + " of annotation class "
                                + annotationClassInfo.getName());
                    }
                    intArrayValue[j] = objectArrayValue[j].integerValue.intValue();
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
                                + annotationClassInfo.getName());
                    }
                    longArrayValue[j] = objectArrayValue[j].longValue.longValue();
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
                                + annotationClassInfo.getName());
                    }
                    shortArrayValue[j] = objectArrayValue[j].shortValue.shortValue();
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
                                + annotationClassInfo.getName());
                    }
                    charArrayValue[j] = objectArrayValue[j].characterValue.charValue();
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
                                + annotationClassInfo.getName());
                    }
                    floatArrayValue[j] = objectArrayValue[j].floatValue.floatValue();
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
                                + annotationClassInfo.getName());
                    }
                    doubleArrayValue[j] = objectArrayValue[j].doubleValue.doubleValue();
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
                                + annotationClassInfo.getName());
                    }
                    booleanArrayValue[j] = objectArrayValue[j].booleanValue.booleanValue();
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
                                + annotationClassInfo.getName());
                    }
                    byteArrayValue[j] = objectArrayValue[j].byteValue.byteValue();
                }
                objectArrayValue = null;
                break;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (enumValue != null) {
            enumValue.setScanResult(scanResult);
        } else if (classRef != null) {
            classRef.setScanResult(scanResult);
        } else if (annotationInfo != null) {
            annotationInfo.setScanResult(scanResult);
        } else if (objectArrayValue != null) {
            for (int i = 0; i < objectArrayValue.length; i++) {
                if (objectArrayValue[i] != null) {
                    objectArrayValue[i].setScanResult(scanResult);
                }
            }
        }
    }

    /** Get the names of any classes referenced in the annotation parameters. */
    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> referencedClassNames) {
        if (enumValue != null) {
            enumValue.getClassNamesFromTypeDescriptors(referencedClassNames);
        } else if (classRef != null) {
            referencedClassNames.add(classRef.getClassName());
        } else if (annotationInfo != null) {
            annotationInfo.getClassNamesFromTypeDescriptors(referencedClassNames);
        } else if (objectArrayValue != null) {
            for (final ObjectTypedValueWrapper item : objectArrayValue) {
                item.getClassNamesFromTypeDescriptors(referencedClassNames);
            }
        }
    }
}