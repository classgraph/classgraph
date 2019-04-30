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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A class to find the unique ordered classpath elements. */
class CallStackReader {

    /**
     * Constructor.
     */
    private CallStackReader() {
        // Cannot be constructed
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
                    Proxy.newProxyInstance(consumerClass.getClassLoader(), new Class[] { consumerClass },
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
     * Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
     * 
     * https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
     *
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
     */
    private static final class CallerResolver extends SecurityManager {
        /* (non-Javadoc)
         * @see java.lang.SecurityManager#getClassContext()
         */
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    /**
     * Get the call stack via the SecurityManager API.
     *
     * @param log
     *            the log
     * @return the call stack.
     */
    private static Class<?>[] getCallStackViaSecurityManager(final LogNode log) {
        try {
            return new CallerResolver().getClassContext();
        } catch (final SecurityException e) {
            // Creating a SecurityManager can fail if the current SecurityManager does not allow
            // RuntimePermission("createSecurityManager")
            if (log != null) {
                log.log("Exception while trying to obtain call stack via SecurityManager", e);
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
    static Class<?>[] getClassContext(final LogNode log) {
        // For JRE 9+, use StackWalker to get call stack.
        // N.B. need to work around StackWalker bug fixed in JDK 13, and backported to 12.0.2 and 11.0.4
        // (probably introduced in JDK 9, when StackWalker was introduced):
        // https://github.com/classgraph/classgraph/issues/341
        // https://bugs.openjdk.java.net/browse/JDK-8210457
        Class<?>[] stackClasses = null;
        if ((VersionFinder.JAVA_MAJOR_VERSION == 11
                && (VersionFinder.JAVA_MINOR_VERSION >= 1 || VersionFinder.JAVA_SUB_VERSION >= 4)
                && !VersionFinder.JAVA_IS_EA_VERSION)
                || (VersionFinder.JAVA_MAJOR_VERSION == 12
                        && (VersionFinder.JAVA_MINOR_VERSION >= 1 || VersionFinder.JAVA_SUB_VERSION >= 2)
                        && !VersionFinder.JAVA_IS_EA_VERSION)
                || (VersionFinder.JAVA_MAJOR_VERSION == 13 && !VersionFinder.JAVA_IS_EA_VERSION)
                || VersionFinder.JAVA_MAJOR_VERSION > 13) {
            // Invoke with doPrivileged -- see:
            // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013974.html
            stackClasses = AccessController.doPrivileged(new PrivilegedAction<Class<?>[]>() {
                @Override
                public Class<?>[] run() {
                    return getCallStackViaStackWalker();
                }
            });
        }

        // For JRE 7 and 8, use SecurityManager to get call stack
        if (stackClasses == null || stackClasses.length == 0) {
            stackClasses = AccessController.doPrivileged(new PrivilegedAction<Class<?>[]>() {
                @Override
                public Class<?>[] run() {
                    return getCallStackViaSecurityManager(log);
                }
            });
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        if (stackClasses == null || stackClasses.length == 0) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            if (stackTrace == null || stackTrace.length == 0) {
                try {
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
                stackClasses = stackClassesList.toArray(new Class<?>[0]);
            } else {
                // Last-ditch effort -- include just this class in the call stack
                stackClasses = new Class<?>[] { CallStackReader.class };
            }
        }
        return stackClasses;
    }
}
