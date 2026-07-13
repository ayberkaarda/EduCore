package com.example.project3.config;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobTracker {
    // İşlem ID'sine göre logları hafızada tutan yapı
    private final Map<Long, List<String>> logs = new ConcurrentHashMap<>();

    public void addLog(Long jobId, String status, String message) {
        String escapedMessage = message.replace("\"", "\\\"");
        String jsonLog = String.format("{\"status\":\"%s\", \"message\":\"%s\"}", status, escapedMessage);
        logs.computeIfAbsent(jobId, k -> new ArrayList<>()).add(jsonLog);
    }

    public String getLogsAsJson(Long jobId) {
        List<String> jobLogs = logs.getOrDefault(jobId, new ArrayList<>());
        return "[" + String.join(",", jobLogs) + "]";
    }

    public void clear(Long jobId) {
        logs.remove(jobId);
    }
}