package com.xyz.fig.shape;

import java.awt.Graphics2D;

public class Square extends ShapeImpl {
    private final float size;

    public Square(final float x, final float y, final float size) {
        super(x, y);
        this.size = size;
    }

    public float getSize() {
        return size;
    }

    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
