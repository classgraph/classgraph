package com.xyz.fig.shape;

import java.awt.Graphics2D;

public abstract class ShapeImpl implements Shape {
    private final float x;
    private final float y;

    public ShapeImpl(final float x, final float y) {
        this.x = x;
        this.y = y;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    @Override
    public abstract void draw(Graphics2D f);
}
