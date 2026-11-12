import io.github.shovelshit.apkstore.AdbNavigation;

public final class AdbNavigationTest {
    public static void main(String[] args) {
        AdbNavigation navigation = new AdbNavigation();
        navigation.showApplications("fixture", 7);
        navigation.openPermissions("io.github.fixture");
        check(navigation.back(false) == AdbNavigation.Back.RETURN_TO_LIST, "detail back");
        check(navigation.tab() == AdbNavigation.Tab.APPS, "apps tab");
        check("fixture".equals(navigation.query()) && navigation.listPosition() == 7, "list state");
        check(navigation.back(false) == AdbNavigation.Back.EXIT, "root back");
        check(navigation.back(true) == AdbNavigation.Back.CONFIRM_EXIT, "busy root back");

        navigation.selectFile("selected.apk");
        navigation.setConnected(true);
        navigation.selectTab(AdbNavigation.Tab.DEVICE);
        navigation.selectTab(AdbNavigation.Tab.INSTALL);
        check("selected.apk".equals(navigation.selectedFile()) && navigation.connected(), "tabs retain source and connection");
        navigation.openPermissions("io.github.fixture");
        navigation.selectTab(AdbNavigation.Tab.DEVICE);
        check(navigation.back(false) == AdbNavigation.Back.EXIT, "hidden detail does not intercept back");
        System.out.println("AdbNavigationTest PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
