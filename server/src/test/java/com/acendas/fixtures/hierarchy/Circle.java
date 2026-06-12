package com.acendas.fixtures.hierarchy;

public class Circle extends Shape implements Movable {
    private int x;
    private int y;

    @Override
    public void move(int dx, int dy) {
        x += dx;
        y += dy;
    }
}
