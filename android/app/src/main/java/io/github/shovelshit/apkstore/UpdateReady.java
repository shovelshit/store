package io.github.shovelshit.apkstore;

final class UpdateReady {
    private volatile UpdatePolicy.Release release;
    private volatile UpdatePolicy.Release latest;
    void restore(UpdatePolicy.Release saved, UpdatePolicy.Installed installed) {
        try { release = UpdatePolicy.decide(saved,installed) == UpdatePolicy.Decision.AVAILABLE ? saved : null; }
        catch (IllegalArgumentException ignored) { release = null; }
    }
    void checked(UpdatePolicy.Release checked) { latest = checked; }
    void clear() { release = null; }
    UpdatePolicy.Release release() { return release; }
    UpdatePolicy.Release latest() { return latest; }
}
