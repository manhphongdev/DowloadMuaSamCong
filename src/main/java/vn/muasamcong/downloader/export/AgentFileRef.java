package vn.muasamcong.downloader.export;

import java.nio.file.Path;

public record AgentFileRef(String fileId, String fileName, Path subDir) {

    public AgentFileRef(String fileId, String fileName) {
        this(fileId, fileName, null);
    }
}
