import io.github.shovelshit.apkstore.AdbController;
import io.github.shovelshit.apkstore.adb.AdbClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class AdbControllerTest {
    public static void main(String[] args) throws Exception {
        List<Runnable> work = new ArrayList<>(), callbacks = new ArrayList<>();
        Fake session = new Fake();
        AdbController controller = new AdbController(work::add, callbacks::add, () -> session);
        List<String> events = new ArrayList<>();
        controller.connect("127.0.0.1", 5555, new AdbController.Callback<String>() {
            public void success(String result) { events.add(result); }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        work.remove(0).run();
        callbacks.remove(0).run();
        if (!events.contains("127.0.0.1:5555")) throw new AssertionError(events);
        controller.submit(s -> { events.add("executed stale"); return "stale"; }, new AdbController.Callback<String>() {
            public void success(String value) { events.add(value); }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        controller.disconnect();
        work.remove(0).run();
        for (Runnable callback : callbacks) callback.run();
        if (events.contains("executed stale") || events.contains("stale") || !session.closed) throw new AssertionError(events);
        Fake newSession = new Fake();
        AdbController second = new AdbController(work::add, callbacks::add, () -> newSession);
        second.connect("10.0.2.2", 5583, new AdbController.Callback<String>() {
            public void success(String value) { events.add(value); }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        work.remove(0).run();
        second.disconnect();
        for (Runnable callback : callbacks) callback.run();
        if (events.contains("10.0.2.2:5583") || !newSession.closed) throw new AssertionError("late connect changed target");
        Fake thirdSession = new Fake();
        AdbController third = new AdbController(work::add, callbacks::add, () -> thirdSession);
        third.connect("127.0.0.1", 5555, new AdbController.Callback<String>() {
            public void success(String value) { }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        work.remove(0).run();
        callbacks.clear();
        final AdbController.ProgressPoster[] delayed = new AdbController.ProgressPoster[1];
        third.submitWithProgress((s, poster) -> { delayed[0] = poster; return "done"; }, new AdbController.Callback<String>() {
            public void success(String value) { }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        work.remove(0).run();
        callbacks.clear();
        third.disconnect();
        third.connect("127.0.0.2", 5556, new AdbController.Callback<String>() {
            public void success(String value) { }
            public void failure(Exception error) { throw new AssertionError(error); }
        });
        work.remove(0).run();
        callbacks.clear();
        delayed[0].post(() -> events.add("old progress on new target"));
        for (Runnable callback : callbacks) callback.run();
        if (events.contains("old progress on new target")) throw new AssertionError(events);
        third.close();
        second.close(); controller.close();
        System.out.println("AdbControllerTest PASS");
    }
    private static final class Fake implements AdbController.Session {
        boolean closed;
        public void connect(String host, int port, AdbClient.AuthListener listener) { }
        public AdbClient.ShellResult shell(String command, int timeout) { return new AdbClient.ShellResult("", 0); }
        public void push(InputStream source, long length, String path, AdbClient.ProgressListener progress) { }
        public void close() { closed = true; }
    }
}
