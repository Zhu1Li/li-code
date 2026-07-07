package com.licode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class FileStateCache {

    public record CacheEntry(String content, long mtime) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public void record(String path, String content, long mtime) {
        cache.put(path, new CacheEntry(content, mtime));
    }

    public void update(String path, String newContent) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        cache.put(path, new CacheEntry(newContent, mtime));
    }

    public String validate(String path) {
        CacheEntry entry = cache.get(path);
        if (entry == null) {
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (IOException e) {
            return null;
        }
        if (currentMtime > entry.mtime()) {
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }

    public CacheEntry get(String path) {
        return cache.get(path);
    }

    public void clear() {
        cache.clear();
    }
}
