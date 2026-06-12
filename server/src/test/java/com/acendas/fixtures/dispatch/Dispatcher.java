package com.acendas.fixtures.dispatch;

public class Dispatcher {
    public void dispatch(ClickHandler handler) {
        handler.handle();
    }
}
