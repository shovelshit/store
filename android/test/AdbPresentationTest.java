import io.github.shovelshit.apkstore.AdbPresentation;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.util.ArrayList;
import java.util.List;

public final class AdbPresentationTest {
    public static void main(String[] args) throws Exception {
        List<AdbOperations.Application> all = new ArrayList<>();
        for (int i = 0; i < 180; i++) all.add(new AdbOperations.Application("com.example.app" + i));
        check(AdbPresentation.filterApps(all, " ").size() == 180, "broad search must retain all apps");
        check(AdbPresentation.filterApps(all, "APP179").get(0).packageName.equals("com.example.app179"), "case-insensitive filter must reach last app");

        String dump = "Package [com.example.app] (123):\n    requested permissions:\n"
                + "      android.permission.INTERNET\n"
                + "      android.permission.CAMERA\n"
                + "      android.permission.BLUETOOTH\n"
                + "    User 0: installed=true\n      runtime permissions:\n"
                + "        android.permission.CAMERA: granted=false\n";
        AdbOperations.PermissionSnapshot snapshot = new AdbOperations(command -> new io.github.shovelshit.apkstore.adb.AdbClient.ShellResult(dump, 0))
                .readPermissions("com.example.app");
        List<AdbOperations.Permission> ordered = AdbPresentation.orderedDeclared(snapshot.permissions);
        check(ordered.size() == 3, "omit nondeclared appendix");
        check(ordered.get(0).name.equals("android.permission.CAMERA"), "relevant permission must be first");
        check(AdbPresentation.permissionTitle(ordered.get(0)).equals("相机"), "known title");
        check(AdbPresentation.permissionTitle(ordered.get(1)).equals("网络访问权限"), "unknown primary title should be Chinese category");
        check(AdbPresentation.permissionTitle(ordered.get(2)).equals("设备连接权限"), "unknown category");
        check(AdbPresentation.permissionDetail(ordered.get(1)).contains("android.permission.INTERNET"), "raw permission must remain secondary");
        check(AdbPresentation.permissionDetail(ordered.get(1)).contains("不支持通过 ADB 修改"), "unsupported reason");

        AdbPresentation.InstallProgress progress = new AdbPresentation.InstallProgress();
        progress.start();
        check(progress.percent() == 0 && progress.detail().contains("上传"), "start progress in install pane");
        progress.uploaded(25, 100);
        check(progress.percent() == 25 && progress.summary().equals("上传 25%"), "upload progress");
        progress.uploaded(100, 100);
        check(progress.detail().contains("设备安装"), "install after upload");
        progress.succeeded("Success");
        check(progress.detail().contains("Success") && progress.summary().equals("安装成功"), "result available locally");
        progress.failed("remote offline");
        check(progress.detail().contains("remote offline") && progress.summary().equals("安装失败"), "failure available locally");
        System.out.println("AdbPresentationTest PASS");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
