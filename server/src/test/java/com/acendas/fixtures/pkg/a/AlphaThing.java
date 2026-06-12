package com.acendas.fixtures.pkg.a;

import com.acendas.fixtures.pkg.b.BetaThing;

public class AlphaThing {
    private BetaThing beta = new BetaThing();

    public String describeBeta() {
        return beta.describe();
    }
}
