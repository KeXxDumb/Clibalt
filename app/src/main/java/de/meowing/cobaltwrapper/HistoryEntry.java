package de.meowing.cobaltwrapper;

public class HistoryEntry {
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public String url;
    public String time;
    public long downloadId;
    public String thumbPath;
    public String status;
    public String fileUri;
    public String mimeType;

    public HistoryEntry(String url, String time, long downloadId, String thumbPath,
                         String status, String fileUri, String mimeType) {
        this.url = url;
        this.time = time;
        this.downloadId = downloadId;
        this.thumbPath = thumbPath;
        this.status = status;
        this.fileUri = fileUri;
        this.mimeType = mimeType;
    }
}
