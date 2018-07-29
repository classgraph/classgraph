package io.github.classgraph.test.whitelisted;

import java.util.ArrayList;
import java.util.HashMap;

public class HasFieldWithTypeCls {
    public static class HasFieldWithTypeCls1 {
        Cls field;
    }

    public static class HasFieldWithTypeCls2 {
        Cls[] field;
    }

    public static class HasFieldWithTypeCls3 {
        Cls[][][] field;
    }

    public static class HasFieldWithTypeCls4 {
        ArrayList<Cls> field;
    }

    public static class HasFieldWithTypeCls5 {
        HashMap<String, Cls> field;
    }

    public static class HasFieldWithTypeCls6 {
        ArrayList<Cls>[] field;
    }

    public static class HasFieldWithTypeCls7 {
        ArrayList<? extends Cls> field;
    }
}
