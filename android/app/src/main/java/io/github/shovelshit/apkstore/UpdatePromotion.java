package io.github.shovelshit.apkstore;

import java.io.File;
import java.io.IOException;

final class UpdatePromotion {
    static void promote(File part, File completed) throws IOException {
        if (!part.getCanonicalFile().getParentFile().equals(completed.getCanonicalFile().getParentFile())
                || !part.isFile() || completed.isDirectory() || !part.renameTo(completed))
            throw new IOException("无法原子保存已校验安装包");
    }
}
