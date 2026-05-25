package com.quest.lobby;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class FileLogger {

    private static final String TAG = "FileLogger";
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB per file
    private static final int MAX_BACKUPS = 5; // keep log.txt.1 .. log.txt.5

    private static Thread logThread;
    private static volatile boolean running = false;
    private static Process logProcess;

    public static synchronized void start(Context ctx) {
        if (running) return;

        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            Log.e(TAG, "External files dir not available");
            return;
        }
        final File logFile = new File(dir, "log.txt");

        if (logFile.exists() && logFile.length() > MAX_SIZE_BYTES) {
            rotateLogs(dir, logFile);
        }

        running = true;
        logThread = new Thread(() -> {
            FileWriter writer = null;
            BufferedReader reader = null;
            try {
                writer = new FileWriter(logFile, true);
                writer.write("\n===== Logger started =====\n");
                writer.flush();

                logProcess = Runtime.getRuntime().exec(
                    new String[] { "logcat", "-v", "time", "-s", "Lobby:V" }
                );
                reader = new BufferedReader(new InputStreamReader(logProcess.getInputStream()));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();

                    if (logFile.length() > MAX_SIZE_BYTES) {
                        writer.close();
                        rotateLogs(dir, logFile);
                        writer = new FileWriter(logFile, true);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Logger thread error: " + e.getMessage());
            } finally {
                try { if (reader != null) reader.close(); } catch (Exception ignored) {}
                try { if (writer != null) writer.close(); } catch (Exception ignored) {}
                if (logProcess != null) logProcess.destroy();
            }
        }, "FileLogger");
        logThread.setDaemon(true);
        logThread.start();

        Log.d(TAG, "FileLogger started, writing to: " + logFile.getAbsolutePath());
    }

    private static void rotateLogs(File dir, File logFile) {
        // Delete oldest if exists
        File oldest = new File(dir, "log.txt." + MAX_BACKUPS);
        if (oldest.exists()) oldest.delete();

        // Shift: log.txt.(N-1) -> log.txt.N, ..., log.txt.1 -> log.txt.2
        for (int i = MAX_BACKUPS - 1; i >= 1; i--) {
            File src = new File(dir, "log.txt." + i);
            File dst = new File(dir, "log.txt." + (i + 1));
            if (src.exists()) src.renameTo(dst);
        }

        // Current log.txt -> log.txt.1
        File first = new File(dir, "log.txt.1");
        logFile.renameTo(first);
    }

    public static synchronized void stop() {
        running = false;
        if (logProcess != null) {
            logProcess.destroy();
            logProcess = null;
        }
        if (logThread != null) {
            logThread.interrupt();
            logThread = null;
        }
    }
}
