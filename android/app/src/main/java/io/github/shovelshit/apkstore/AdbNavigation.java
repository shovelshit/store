package io.github.shovelshit.apkstore;

/** State shared by the ADB header, tabs, and application detail navigation. */
public final class AdbNavigation {
    public enum Tab { DEVICE, APPS, INSTALL }
    public enum Back { RETURN_TO_LIST, EXIT, CONFIRM_EXIT }

    private Tab tab = Tab.DEVICE;
    private String query = "", selectedFile, permissionPackage;
    private int listPosition;
    private boolean connected;

    public Tab tab() { return tab; }
    public String query() { return query; }
    public int listPosition() { return listPosition; }
    public String permissionPackage() { return permissionPackage; }
    public String selectedFile() { return selectedFile; }
    public boolean connected() { return connected; }
    public void setConnected(boolean value) { connected = value; }
    public void selectFile(String value) { selectedFile = value; }
    public void showApplications(String value, int position) {
        tab = Tab.APPS;
        query = value;
        listPosition = position;
        permissionPackage = null;
    }
    public void selectTab(Tab value) { tab = value; }
    public void openPermissions(String value) { tab = Tab.APPS; permissionPackage = value; }
    public Back back(boolean busy) {
        if (busy) return Back.CONFIRM_EXIT;
        if (tab == Tab.APPS && permissionPackage != null) {
            permissionPackage = null;
            return Back.RETURN_TO_LIST;
        }
        return Back.EXIT;
    }
}
