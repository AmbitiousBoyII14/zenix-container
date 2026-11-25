package top.niunaijun.blackboxa.zenix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zenix container telemetry.
 *
 * One ring buffer in memory (for the live console), one bounded append-only file
 * (so a crash still leaves evidence the next session can read). Every line is
 * "measured, not aspirational": gates write counts, ids and package names that
 * the code actually saw.
 *
 * Constraints honoured:
 *  - never touches the guest process, so the game thread pays nothing;
 *  - file writes are throttled (2 s) and the file is capped (512 KiB, truncated
 *    in place when exceeded) so a long session cannot fill the disk;
 *  - UI dispatch is coalesced on the main thread, one post per burst.
 *
 * Created for Zenix Container.
 */
public final class ZenixLog {

    public static final String TAG = "Zenix";

    public static final int LEVEL_VERBOSE = 0;
    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private static final int MAX_LINES = 600;
    private static final long FLUSH_INTERVAL_MS = 2000L;
    private static final long FILE_MAX_BYTES = 512L * 1024L;

    private static final Object LOCK = new Object();
    private static final ArrayList<Entry> LINES = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat UI_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File sFile;
    private static String sBuildStamp = "unknown";
    private static long sLastFlush;
    private static long sSeq;
    private static boolean sUiPostPending;

    private ZenixLog() {
    }

    /**
     * One telemetry line. Immutable except for the formatted fields, which are
     * built once on the writer thread so the UI adapter never parses anything.
     */
    public static final class Entry {
        public final long seq;
        public final long wallMs;
        public final long sinceBootMs;
        public final int level;
        public final String gate;
        public final String message;
        public final String uiLine;
        public final String fileLine;

        Entry(long seq, long wallMs, long sinceBootMs, int level, String gate, String message) {
            this.seq = seq;
            this.wallMs = wallMs;
            this.sinceBootMs = sinceBootMs;
            this.level = level;
            this.gate = gate;
            this.message = message;
            this.uiLine = UI_FMT.format(new Date(wallMs))
                    + "  " + levelChar(level) + "/" + gate
                    + "  " + message;
            this.fileLine = FILE_FMT.format(new Date(wallMs))
                    + " " + levelChar(level) + "/" + gate
                    + " +" + sinceBootMs + "ms  " + message;
        }

        public String toDumpLine() {
            return fileLine;
        }

        private static String levelChar(int level) {
            switch (level) {
                case LEVEL_WARN:
                    return "W";
                case LEVEL_ERROR:
                    return "E";
                case LEVEL_INFO:
                    return "I";
                default:
                    return "V";
            }
        }
    }

    public interface Listener {
        /** Always delivered on the main thread. */
        void onLog(Entry entry);
    }

    /**
     * Identify the build in every session banner. A log that cannot say which
     * build produced it is a log you read twice.
     */
    public static void setBuildStamp(String stamp) {
        if (stamp != null && stamp.length() > 0) {
            sBuildStamp = stamp;
        }
    }

    /** Bind the file sink. Without this the log is memory-only. */
    public static void attachFile(File file) {
        synchronized (LOCK) {
            sFile = file;
            File parent = file == null ? null : file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            writeBootBannerLocked();
        }
    }

    public static File getFile() {
        synchronized (LOCK) {
            return sFile;
        }
    }

    private static void writeBootBannerLocked() {
        // Separator per host process start: the console only ever holds lines
        // from this run, the file keeps every run in order.
        sSeq = 0;
        sLastFlush = 0;
        appendToFileLocked("---- Zenix session start | build " + sBuildStamp
                + " | pid " + android.os.Process.myPid() + " ----\n");
    }

    public static void v(String gate, String message) {
        write(LEVEL_VERBOSE, gate, message);
    }

    public static void i(String gate, String message) {
        write(LEVEL_INFO, gate, message);
    }

    public static void w(String gate, String message) {
        write(LEVEL_WARN, gate, message);
    }

    public static void e(String gate, String message) {
        write(LEVEL_ERROR, gate, message);
    }

    /** Log a failure with the stack trace attached, one line per cause hop. */
    public static void fault(String gate, String message, Throwable tr) {
        if (tr == null) {
            e(gate, message);
            return;
        }
        e(gate, message + " :: " + tr.getClass().getSimpleName() + ": " + tr.getMessage());
        Throwable cause = tr.getCause();
        int hops = 0;
        while (cause != null && hops < 3) {
            e(gate, "  caused by " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            cause = cause.getCause();
            hops++;
        }
    }

    private static void write(int level, String gate, String message) {
        final Entry entry = new Entry(++sSeq, System.currentTimeMillis(), SystemClock.uptimeMillis(),
                level, gate == null ? "CORE" : gate, message == null ? "" : message);
        boolean flushNow;
        synchronized (LOCK) {
            LINES.add(entry);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
            long now = SystemClock.uptimeMillis();
            // Warnings and errors always reach the file; verbose chatter is throttled.
            flushNow = level >= LEVEL_WARN || now - sLastFlush >= FLUSH_INTERVAL_MS;
            if (flushNow) {
                sLastFlush = now;
            }
        }
        // logcat gets every line: `adb logcat -s Zenix`. The file sink is the
        // throttled one, because storage is the scarce resource, not logcat.
        int priority = level >= LEVEL_WARN ? Log.WARN : (level == LEVEL_INFO ? Log.INFO : Log.DEBUG);
        Log.println(priority, TAG, entry.fileLine);
        if (flushNow) {
            synchronized (LOCK) {
                appendToFileLocked(entry.fileLine + "\n");
            }
        }
        scheduleUiDispatch();
    }

    private static void appendToFileLocked(String text) {
        if (sFile == null) {
            return;
        }
        try {
            if (sFile.length() > FILE_MAX_BYTES) {
                // Cheap truncate: keep the newest half instead of growing forever.
                //noinspection ResultOfMethodCallIgnored
                sFile.delete();
            }
            FileWriter writer = new FileWriter(sFile, true);
            try {
                writer.write(text);
            } finally {
                writer.close();
            }
        } catch (IOException io) {
            // Logging must never throw into a caller's gate.
            Log.w(TAG, "file sink failed: " + io.getMessage());
        }
    }

    private static void scheduleUiDispatch() {
        synchronized (LOCK) {
            if (sUiPostPending) {
                return;
            }
            sUiPostPending = true;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Entry last;
                synchronized (LOCK) {
                    sUiPostPending = false;
                    last = LINES.isEmpty() ? null : LINES.get(LINES.size() - 1);
                }
                if (last == null) {
                    return;
                }
                for (Listener listener : LISTENERS) {
                    try {
                        listener.onLog(last);
                    } catch (Throwable tr) {
                        Log.w(TAG, "listener threw: " + tr);
                    }
                }
            }
        });
    }

    public static void register(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void unregister(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Snapshot for a console that is opening mid-session. */
    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static int lineCount() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    public static int count(int minLevel) {
        synchronized (LOCK) {
            int n = 0;
            for (Entry entry : LINES) {
                if (entry.level >= minLevel) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Full text of the in-memory buffer, oldest first. Used by "copy". */
    public static String dump() {
        StringBuilder builder = new StringBuilder(4096);
        synchronized (LOCK) {
            for (Entry entry : LINES) {
                builder.append(entry.fileLine).append('\n');
            }
        }
        return builder.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            sSeq = 0;
        }
        i("CONSOLE", "buffer cleared by user; file sink retained");
    }
}
