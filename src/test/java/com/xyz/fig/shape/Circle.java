package com.xyz.fig.shape;

import java.awt.Graphics2D;

public class Circle extends ShapeImpl {
    private final float r;

    public Circle(final float x, final float y, final float r) {
        super(x, y);
        this.r = r;
    }

    public float getR() {
        return r;
    }

    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
