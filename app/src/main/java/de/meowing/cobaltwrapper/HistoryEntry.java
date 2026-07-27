package de.meowing.cobaltwrapper;

public class HistoryEntry {
    public String url;
    public String time;
    public long downloadId;
    public String thumbPath;

    public HistoryEntry(String url, String time, long downloadId, String thumbPath) {
        this.url = url;
        this.time = time;
        this.downloadId = downloadId;
        this.thumbPath = thumbPath;
    }
}
