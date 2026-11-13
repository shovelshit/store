package io.github.shovelshit.apkstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

final class DownloadVerification {
    interface ArchiveInspector {
        boolean inspect(InputStream archive) throws IOException;
    }

    private DownloadVerification() {}

    static boolean isApk(InputStream stream, ArchiveInspector inspector) throws IOException {
        byte[] header = new byte[4];
        int offset = 0;
        while (offset < header.length) {
            int count = stream.read(header, offset, header.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        if (header[0] != 'P' || header[1] != 'K'
                || header[2] != 3 || header[3] != 4) return false;
        return inspector.inspect(new SequenceInputStream(new ByteArrayInputStream(header), stream));
    }
}
