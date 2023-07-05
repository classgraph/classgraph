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
package nonapi.io.github.classgraph.classpath;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A class to find the unique ordered classpath elements. */
class CallStackReader {
    ReflectionUtils reflectionUtils;

    /**
     * Constructor.
     */
    public CallStackReader(final ReflectionUtils reflectionUtils) {
        this.reflectionUtils = reflectionUtils;
    }

    /**
     * Get the call stack via the StackWalker API (JRE 9+).
     *
     * @return the call stack, or null if it could not be obtained.
     */
    private static Class<?>[] getCallStackViaStackWalker() {
        try {
            //    // Implement the following via reflection, for JDK7 compatibility:
            //    List<Class<?>> stackFrameClasses = new ArrayList<>();
            //    StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)
            //            .forEach(sf -> stackFrameClasses.add(sf.getDeclaringClass()));

            final Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            final List<Class<?>> stackFrameClasses = new ArrayList<>();
            final Class<?> stackWalkerOptionClass = Class.forName("java.lang.StackWalker$Option");
            final Object retainClassReference = Class.forName("java.lang.Enum")
                    .getMethod("valueOf", Class.class, String.class)
                    .invoke(null, stackWalkerOptionClass, "RETAIN_CLASS_REFERENCE");
            final Class<?> stackWalkerClass = Class.forName("java.lang.StackWalker");
            final Object stackWalkerInstance = stackWalkerClass.getMethod("getInstance", stackWalkerOptionClass)
                    .invoke(null, retainClassReference);
            final Method stackFrameGetDeclaringClassMethod = Class.forName("java.lang.StackWalker$StackFrame")
                    .getMethod("getDeclaringClass");
            stackWalkerClass.getMethod("forEach", consumerClass).invoke(stackWalkerInstance, //
                    // InvocationHandler proxy for Consumer<StackFrame>
                    Proxy.newProxyInstance(consumerClass.getClassLoader(), new Class<?>[] { consumerClass },
                            new InvocationHandler() {
                                @Override
                                public Object invoke(final Object proxy, final Method method, final Object[] args)
                                        throws Throwable {
                                    // Consumer<StackFrame> has only one method: void accept(StackFrame)
                                    final Class<?> declaringClass = (Class<?>) stackFrameGetDeclaringClassMethod
                                            .invoke(args[0]);
                                    stackFrameClasses.add(declaringClass);
                                    return null;
                                }
                            }));
            return stackFrameClasses.toArray(new Class<?>[0]);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the call stack via the SecurityManager.getClassContext() native method.
     *
     * @param log
     *            the log
     * @return the call stack.
     */
    private static Class<?>[] getCallStackViaSecurityManager(final LogNode log) {
        try {
            // Call method via reflection, since SecurityManager is deprecated in JDK 17.
            final Class<?> securityManagerClass = Class.forName("java.lang.SecurityManager");
            Object securityManager = null;
            for (final Constructor<?> constructor : securityManagerClass.getDeclaredConstructors()) {
                if (constructor.getParameterTypes().length == 0) {
                    securityManager = constructor.newInstance();
                    break;
                }
            }
            if (securityManager != null) {
                final Method getClassContext = securityManager.getClass().getDeclaredMethod("getClassContext");
                getClassContext.setAccessible(true);
                return (Class<?>[]) getClassContext.invoke(securityManager);
            } else {
                return null;
            }
        } catch (final Throwable t) {
            // Creating a SecurityManager can fail if the current SecurityManager does not allow
            // RuntimePermission("createSecurityManager")
            if (log != null) {
                log.log("Exception while trying to obtain call stack via SecurityManager", t);
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class context.
     *
     * @param log
     *            the log
     * @return The classes in the call stack.
     */
    Class<?>[] getClassContext(final LogNode log) {
        Class<?>[] callStack = null;

        // For JRE 9+, use StackWalker to get call stack.
        if (VersionFinder.JAVA_MAJOR_VERSION == 9 //
                || VersionFinder.JAVA_MAJOR_VERSION == 10 //
                || (VersionFinder.JAVA_MAJOR_VERSION == 11 //
                        && VersionFinder.JAVA_MINOR_VERSION == 0
                        && (VersionFinder.JAVA_SUB_VERSION < 4
                                || (VersionFinder.JAVA_SUB_VERSION == 4 && VersionFinder.JAVA_IS_EA_VERSION)))
                || (VersionFinder.JAVA_MAJOR_VERSION == 12 && VersionFinder.JAVA_MINOR_VERSION == 0
                        && (VersionFinder.JAVA_SUB_VERSION < 2
                                || (VersionFinder.JAVA_SUB_VERSION == 2 && VersionFinder.JAVA_IS_EA_VERSION)))) {
            // Don't trigger the StackWalker bug that crashed the JVM, which was fixed in JDK 13,
            // and backported to 12.0.2 and 11.0.4 (probably introduced in JDK 9, when StackWalker
            // was introduced):
            // https://github.com/classgraph/classgraph/issues/341
            // https://bugs.openjdk.java.net/browse/JDK-8210457
            // -- fall through
        } else {
            // Get the stack via StackWalker.
            // Invoke with doPrivileged -- see:
            // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013974.html
            try {
                callStack = reflectionUtils.doPrivileged(new Callable<Class<?>[]>() {
                    @Override
                    public Class<?>[] call() throws Exception {
                        return getCallStackViaStackWalker();
                    }
                });
            } catch (final Throwable e) {
                // Fall through
            }
        }

        // For JRE 7 and 8, use SecurityManager to get call stack (don't use this method on JDK 9+,
        // because it will result in a reflective illegal access warning, see #663)
        if (VersionFinder.JAVA_MAJOR_VERSION < 9 && (callStack == null || callStack.length == 0)) {
            try {
                callStack = reflectionUtils.doPrivileged(new Callable<Class<?>[]>() {
                    @Override
                    public Class<?>[] call() throws Exception {
                        return getCallStackViaSecurityManager(log);
                    }
                });
            } catch (final Throwable e) {
                // Fall through
            }
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        if (callStack == null || callStack.length == 0) {
            StackTraceElement[] stackTrace = null;
            try {
                stackTrace = Thread.currentThread().getStackTrace();
            } catch (final SecurityException e) {
                // Fall through
            }
            if (stackTrace == null || stackTrace.length == 0) {
                try {
                    // Try getting stacktrace by throwing an exception 
                    throw new Exception();
                } catch (final Exception e) {
                    stackTrace = e.getStackTrace();
                }
            }
            final List<Class<?>> stackClassesList = new ArrayList<>();
            for (final StackTraceElement elt : stackTrace) {
                try {
                    stackClassesList.add(Class.forName(elt.getClassName()));
                } catch (final ClassNotFoundException | LinkageError ignored) {
                    // Ignored
                }
            }
            if (!stackClassesList.isEmpty()) {
                callStack = stackClassesList.toArray(new Class<?>[0]);
            }
        }

        // Last-ditch effort -- include just this class in the call stack
        if (callStack == null || callStack.length == 0) {
            callStack = new Class<?>[] { CallStackReader.class };
        }

        return callStack;
    }
}
