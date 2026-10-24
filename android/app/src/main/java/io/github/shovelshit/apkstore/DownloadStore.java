package io.github.shovelshit.apkstore;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 记录本应用发起过的下载任务（DownloadManager id + 文件名） */
final class DownloadStore {
    private static final String PREFS = "apk_downloads";
    private static final String KEY = "items";

    private DownloadStore() {
    }

    static synchronized void add(Context context, long id, String name) {
        JSONArray array = read(context);
        try {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("name", name);
            item.put("time", System.currentTimeMillis());
            array.put(item);
        } catch (JSONException ignored) {
            // 记录失败不影响下载本身
        }
        write(context, array);
    }

    static synchronized JSONArray read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    static synchronized void remove(Context context, long id) {
        JSONArray array = read(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < array.length(); i += 1) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            if (item.optLong("id") != id) next.put(item);
        }
        write(context, next);
    }

    private static void write(Context context, JSONArray array) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, array.toString())
                .apply();
    }
}
