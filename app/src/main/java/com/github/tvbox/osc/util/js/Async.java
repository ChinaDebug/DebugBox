package com.github.tvbox.osc.util.js;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

import java.util.concurrent.CompletableFuture;

public class Async {

    private final CompletableFuture<Object> future;

    public static CompletableFuture<Object> run(JSObject object, String name, Object[] args) {
        return new Async().call(object, name, args);
    }

    private Async() {
        this.future = new CompletableFuture<>();
    }

    private CompletableFuture<Object> call(JSObject object, String name, Object[] args) {
        try {
            JSFunction function = object.getJSFunction(name);
            if (function == null) {
                future.complete(null);
                return future;
            }
            Object result = function.call(args);
            if (result instanceof JSObject) {
                then(result);
            } else {
                future.complete(result);
            }
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    private void then(Object result) {
        JSObject promise = (JSObject) result;
        JSFunction thenFn = promise.getJSFunction("then");
        if (thenFn != null) {
            thenFn.call(callback);
        } else {
            future.complete(result);
        }
    }

    private final JSCallFunction callback = new JSCallFunction() {
        @Override
        public Object call(Object... args) {
            future.complete(args.length > 0 ? args[0] : null);
            return null;
        }
    };
}
