package com.xyz.fig.shape;

import java.awt.Graphics2D;

public class Triangle extends ShapeImpl {
    private final float edgeLen;
    private final float rotation;

    public Triangle(final float x, final float y, final float edgeLen, final float rotation) {
        super(x, y);
        this.edgeLen = edgeLen;
        this.rotation = rotation;
    }

    public float getEdgeLen() {
        return edgeLen;
    }

    public float getRotation() {
        return rotation;
    }

    @Override
    public void draw(final Graphics2D f) {
        throw new RuntimeException("Not implemented");
    }
}
