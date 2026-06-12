package com.acendas.fixtures.cfg;

public class BranchLoop {
    public int classify(int n) {
        int result;
        if (n > 0) {
            result = 1;
        } else {
            result = -1;
        }
        for (int i = 0; i < n; i++) {
            result += i;
        }
        return result;
    }
}
