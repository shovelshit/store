package io.github.shovelshit.apkstore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";
    static Uri uri(android.content.Context context, UpdatePolicy.Release release) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates")
                .appendPath(release.sha256+".apk").build();
    }
    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme())
                || !getContext().getPackageName().concat(".updates").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1) throw new FileNotFoundException("无效安装包路径");
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-f0-9]{64}\\.apk")) throw new FileNotFoundException("无效安装包名称");
        String saved = getContext().getSharedPreferences("update_state",0).getString("ready",null);
        try {
            JSONObject data = new JSONObject(saved);
            if (!name.equals(data.getString("sha256")+".apk")) throw new FileNotFoundException("安装包未完成");
        } catch (Exception e) { throw new FileNotFoundException("安装包未完成"); }
        File file = new File(new File(getContext().getFilesDir(),"updates"),name);
        if (!file.isFile()) throw new FileNotFoundException("安装包不存在");
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { try { resolve(uri); return MIME; } catch (FileNotFoundException e) { return null; } }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i]=file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) values[i]=file.length();
            }
            result.addRow(values); return result;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只允许读取");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
