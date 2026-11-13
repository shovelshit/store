package io.github.shovelshit.apkstore;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

final class UpdateCompletion {
    private final WeakReference<Consumer<UpdatePolicy.Release>> callback;
    private volatile boolean active = true;
    UpdateCompletion(Consumer<UpdatePolicy.Release> callback) { this.callback = new WeakReference<>(callback); }
    void clear() { active = false; callback.clear(); }
    void deliver(UpdatePolicy.Release release) {
        Consumer<UpdatePolicy.Release> current = active ? callback.get() : null;
        if (current != null) current.accept(release);
    }
}
