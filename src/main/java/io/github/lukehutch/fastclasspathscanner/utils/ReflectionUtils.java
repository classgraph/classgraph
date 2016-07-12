package io.github.lukehutch.fastclasspathscanner.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ReflectionUtils {
    /** Get the named field in the given object or any of its superclasses. */
    public static Object getFieldVal(Object obj, String fieldName)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    return field.get(classOrSuperclass);
                } catch (NoSuchFieldException e) {
                    // Try parent
                }
            }
        }
        return null;
    }

    /** Invoke the named method in the given object or its superclasses. */
    public static Object invokeMethod(Object obj, String methodName, Object... args)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Method method = classOrSuperclass.getDeclaredMethod(methodName);
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                    return method.invoke(obj, args);
                } catch (NoSuchMethodException e) {
                    // Try parent
                }
            }
        }
        return null;
    }

    /** Invoke the named static method. */
    public static Object invokeStaticMethod(Class<?> cls, String methodName, Object... args)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName);
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return method.invoke(null, args);
            } catch (NoSuchMethodException e) {
                // Try parent
            }
        }
        return null;
    }
}
