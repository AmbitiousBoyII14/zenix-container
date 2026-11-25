package top.niunaijun.blackboxa.zenix;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The microG payload, shipped as APKs inside the host APK's assets.
 *
 * Why assets: Bcore's installByStorage(Uri) path copies through the content
 * resolver, which cannot open our own asset streams, and a Uri from a file
 * provider is one more thing that can be missing at runtime. Copying the
 * payload into the host's private files dir once, then installing by absolute
 * path, is the boring path that works on every installed configuration.
 *
 * Files are copied atomically (write .part, then rename) and skipped when the
 * size already matches, so a second space creation does no I/O.
 *
 * Created for Zenix Container.
 */
public final class ZenixPayload {

    public static final String DIR = "zenix_payload";

    /** Order matters: GMS core first, then the legacy GSF proxy, store stub last. */
    public static final String[] FILES = {"gmscore.apk", "gsfproxy.apk", "fakestore.apk"};

    /** Package each payload file must declare; verified after install, not assumed. */
    public static final String[] PACKAGES = {
            "com.google.android.gms",   // microG Services (GmsCore)
            "com.google.android.gsf",   // Services Framework Proxy
            "com.android.vending"       // microG Companion / FakeStore
    };

    private ZenixPayload() {
    }

    public static File payloadDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    /** Present-but-not-yet-installed state, used by the self-test. */
    public static List<String> missingFiles(Context context) {
        List<String> missing = new ArrayList<>();
        File dir = payloadDir(context);
        for (String name : FILES) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() <= 0) {
                missing.add(name);
            }
        }
        return missing;
    }

    public static boolean isReady(Context context) {
        return missingFiles(context).isEmpty();
    }

    /**
     * What the build actually shipped in assets, regardless of whether it has
     * been staged to filesDir yet. Distinguishes "wrong APK installed" (no
     * assets) from "right APK, payload not staged yet" (assets present, files
     * missing) -- the two cases need opposite fixes.
     */
    public static List<String> assetsAvailable(Context context) {
        List<String> available = new ArrayList<>();
        String[] assets = listAssets(context);
        if (assets == null) {
            return available;
        }
        for (String name : FILES) {
            if (contains(assets, name)) {
                available.add(name);
            }
        }
        return available;
    }

    /**
     * Copy whatever payload the build shipped into the private dir.
     *
     * @return number of files (re)copied; -1 when the build shipped no assets.
     */
    /**
     * Serialize concurrent staging (launch background thread + a user who
     * taps Install microG mid-copy): the copy is not atomic, so a second
     * writer interleaving bytes would corrupt the staged APK.
     */
    private static final Object STAGE_LOCK = new Object();

    public static int materialize(Context context) {
        synchronized (STAGE_LOCK) {
            File dir = payloadDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                ZenixLog.e("PAYLOAD", "cannot create payload dir " + dir.getAbsolutePath());
                return -1;
            }

            String[] assets = listAssets(context);
            if (assets == null || assets.length == 0) {
                ZenixLog.w("PAYLOAD", "build shipped no assets/" + DIR
                        + " -- payload must be installed from file");
                return -1;
            }

            int copied = 0;
            List<String> notShipped = new ArrayList<>();
            for (String name : FILES) {
                if (!contains(assets, name)) {
                    // Placeholder files (README) can live in the assets dir without
                    // being mistaken for payload; missing payload is reported once.
                    notShipped.add(name);
                    continue;
                }
                File target = new File(dir, name);
                long assetSize = assetSize(context, DIR + "/" + name);
                if (target.isFile() && assetSize > 0 && target.length() == assetSize) {
                    ZenixLog.v("PAYLOAD", name + " present, " + assetSize + " B, skipped");
                    continue;
                }
                if (copyAsset(context, DIR + "/" + name, target)) {
                    copied++;
                    ZenixLog.i("PAYLOAD", name + " staged, " + target.length() + " B");
                }
            }
            if (!notShipped.isEmpty()) {
                ZenixLog.e("PAYLOAD", "build did not ship " + notShipped
                        + " -- run tools/fetch-microg.sh before building");
            }
            ZenixLog.i("PAYLOAD", "staged " + copied + " file(s) in " + dir.getAbsolutePath());
            return copied;
        }
    }

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) {
            if (name.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String[] listAssets(Context context) {
        try {
            return context.getAssets().list(DIR);
        } catch (IOException io) {
            ZenixLog.fault("PAYLOAD", "assets.list(" + DIR + ") failed", io);
            return null;
        }
    }

    private static long assetSize(Context context, String path) {
        // InputStream.available() is unreliable for assets (it reports remaining
        // bytes, and for compressed entries it can be wrong). Prefer the
        // AssetFileDescriptor length, which is the real file size.
        try {
            android.content.res.AssetFileDescriptor fd =
                    context.getAssets().openFd(path);
            try {
                long length = fd.getLength();
                if (length >= 0) {
                    return length;
                }
            } finally {
                fd.close();
            }
        } catch (IOException ignored) {
            // fall through to stream probe
        }
        try {
            InputStream stream = context.getAssets().open(path);
            try {
                return stream.available();
            } finally {
                stream.close();
            }
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static boolean copyAsset(Context context, String assetPath, File target) {
        File part = new File(target.getAbsolutePath() + ".part");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            out = new FileOutputStream(part);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            out = null;
            //noinspection ResultOfMethodCallIgnored
            if (target.exists() && !target.delete()) {
                ZenixLog.w("PAYLOAD", "could not replace stale " + target.getName());
            }
            if (!part.renameTo(target)) {
                ZenixLog.e("PAYLOAD", "rename failed for " + target.getName());
                return false;
            }
            return true;
        } catch (IOException io) {
            ZenixLog.fault("PAYLOAD", "copy " + assetPath + " failed", io);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (part.exists() && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
