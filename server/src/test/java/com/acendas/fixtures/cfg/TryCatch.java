package com.acendas.fixtures.cfg;

public class TryCatch {
    public int safeDivide(int a, int b) {
        int result;
        try {
            result = a / b;
        } catch (ArithmeticException e) {
            result = -1;
        }
        return result;
    }
}
