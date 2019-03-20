package io.github.classgraph.test.whitelisted;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * HasFieldWithTypeCls.
 */
public class HasFieldWithTypeCls {
    /**
     * The Class HasFieldWithTypeCls1.
     */
    public static class HasFieldWithTypeCls1 {

        /** The field. */
        Cls field;
    }

    /**
     * The Class HasFieldWithTypeCls2.
     */
    public static class HasFieldWithTypeCls2 {

        /** The field. */
        Cls[] field;
    }

    /**
     * The Class HasFieldWithTypeCls3.
     */
    public static class HasFieldWithTypeCls3 {

        /** The field. */
        Cls[][][] field;
    }

    /**
     * The Class HasFieldWithTypeCls4.
     */
    public static class HasFieldWithTypeCls4 {

        /** The field. */
        ArrayList<Cls> field;
    }

    /**
     * The Class HasFieldWithTypeCls5.
     */
    public static class HasFieldWithTypeCls5 {

        /** The field. */
        HashMap<String, Cls> field;
    }

    /**
     * The Class HasFieldWithTypeCls6.
     */
    public static class HasFieldWithTypeCls6 {

        /** The field. */
        ArrayList<Cls>[] field;
    }

    /**
     * The Class HasFieldWithTypeCls7.
     */
    public static class HasFieldWithTypeCls7 {

        /** The field. */
        ArrayList<? extends Cls> field;
    }
}
