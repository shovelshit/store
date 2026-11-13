package io.github.shovelshit.apkstore;

final class UpdateGate {
    private boolean busy;
    synchronized boolean begin() { if (busy) return false; busy = true; return true; }
    synchronized void end() { busy = false; }
}
