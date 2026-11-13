package io.github.shovelshit.apkstore;

public final class UpdateGateTest {
    public static void main(String[] args) {
        UpdateGate gate = new UpdateGate();
        if (!gate.begin()) throw new AssertionError("first job rejected");
        if (gate.begin()) throw new AssertionError("duplicate job accepted");
        gate.end();
        if (!gate.begin()) throw new AssertionError("retry rejected");
    }
}
