package weblogic.servlet.jsp;

/**
 * Stand-in for the WebLogic {@code JspClassLoader}. It is not known whether the real class reports its classpath
 * the same way the other WebLogic classloaders do, so this stand-in reports nothing at all, which is the case that
 * has to be handled without failing the scan.
 */
public class JspClassLoader extends ClassLoader {
    /** Constructor. */
    public JspClassLoader() {
        super(/* parent = */ null);
    }
}
