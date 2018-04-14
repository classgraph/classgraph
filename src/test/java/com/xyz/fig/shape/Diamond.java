package com.xyz.fig.shape;

import java.awt.Graphics2D;

public class Diamond extends ShapeImpl {
    private final float w;
    private final float h;

    public Diamond(final float x, final float y, final float w, final float h) {
        super(x, y);
        this.w = w;
        this.h = h;
    }

    public float getW() {
        return w;
    }

    public float getH() {
        return h;
    }

    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
