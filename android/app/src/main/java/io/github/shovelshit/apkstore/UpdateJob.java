package io.github.shovelshit.apkstore;

import java.io.File;

final class UpdateJob {
    interface Directory { File create() throws Exception; }
    interface Work { void transfer(File part) throws Exception; }
    static Exception run(Directory directory, String hash, Work work) {
        File part = null;
        try {
            part = new File(directory.create(),hash+".part");
            work.transfer(part);
            return null;
        } catch (Exception error) { return error; }
        finally { if (part != null) part.delete(); }
    }
}
