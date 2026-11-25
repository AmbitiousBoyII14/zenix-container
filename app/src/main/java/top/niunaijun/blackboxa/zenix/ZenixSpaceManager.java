package top.niunaijun.blackboxa.zenix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.core.system.user.BUserInfo;

/**
 * Zenix Space manager: spaces (BlackBox users) plus the microG stack that lives
 * inside them.
 *
 * The gap this closes, stated plainly: Bcore's own GmsCore.installGApps() walks
 * a fixed list of Google packages and mirrors the ones ALREADY INSTALLED ON THE
 * HOST (PackageManager.getApplicationInfo on the real device). On a de-Googled
 * phone that list is empty, so its GMS Manager reports "no GMS environment" and
 * the whole feature is dead. microG has to come from a payload the container
 * owns, not from the host.
 *
 * installMicroG() therefore installs, per space, in this order:
 *   1. com.google.android.gms    (microG Services / GmsCore)
 *   2. com.google.android.gsf    (Services Framework Proxy)
 *   3. com.android.vending       (microG Companion / FakeStore)
 * and verifies each one through BlackBoxCore.isInstalled() afterwards. Any
 * missing package is reported with its name -- never a generic "failed".
 *
 * Honest limits, documented where they bite:
 *  - microG needs SIGNATURE SPOOFING against the host framework. A container
 *    cannot grant that. Apps that verify the GMS signing certificate will still
 *    reject microG inside a space. Zenix measures and reports it; it cannot fix
 *    it from here.
 *  - GMS core is ~100 MB of arm64 libraries. It is not committed to this repo;
 *    see tools/fetch-microg.sh.
 *
 * Created for Zenix Container.
 */
public final class ZenixSpaceManager {

    /** microG GmsCore. */
    public static final String PKG_GMS = "com.google.android.gms";
    /** Legacy Google Services Framework proxy. */
    public static final String PKG_GSF = "com.google.android.gsf";
    /** Store identity stub (microG Companion, formerly FakeStore). */
    public static final String PKG_VENDING = "com.android.vending";

    public static final String STACK_NAME = "microG";

    public static final class StackStatus {
        public final int userId;
        public boolean gms;
        public boolean gsf;
        public boolean vending;
        public final List<String> missing = new ArrayList<>();

        StackStatus(int userId) {
            this.userId = userId;
        }

        public boolean complete() {
            return gms && gsf && vending;
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append(gms ? "gms ok" : "gms missing")
                    .append(", ").append(gsf ? "gsf ok" : "gsf missing")
                    .append(", ").append(vending ? "vending ok" : "vending missing");
            return builder.toString();
        }
    }

    private ZenixSpaceManager() {
    }

    // ---------------------------------------------------------------- spaces

    public static List<BUserInfo> spaces() {
        List<BUserInfo> users = BlackBoxCore.get().getUsers();
        if (users == null) {
            return new ArrayList<>();
        }
        return users;
    }

    /**
     * Create the next space. Space ids are the BlackBox user ids; id 0 always
     * exists, so the next free id is max(existing) + 1, capped so a stray id
     * cannot run off into hundreds of directories.
     */
    public static int createSpace(String name) {
        List<BUserInfo> users = spaces();
        int next = 0;
        for (BUserInfo user : users) {
            next = Math.max(next, user.id + 1);
        }
        if (next > 64) {
            ZenixLog.e("SPACE", "refusing to create space id " + next + " (cap 64)");
            return -1;
        }
        ZenixLog.i("SPACE", "creating space id=" + next + " name=\"" + name + "\"");
        BUserInfo created = BlackBoxCore.get().createUser(next);
        if (created == null) {
            ZenixLog.e("SPACE", "createUser(" + next + ") returned null");
            return -1;
        }
        ZenixLog.i("SPACE", "space id=" + created.id + " created, total spaces="
                + spaces().size());
        return created.id;
    }

    public static boolean deleteSpace(int userId) {
        if (userId <= 0) {
            // Space 0 is the host-default container; deleting it would take the
            // app list with it.
            ZenixLog.w("SPACE", "refusing to delete space id=" + userId);
            return false;
        }
        ZenixLog.i("SPACE", "deleting space id=" + userId);
        BlackBoxCore.get().deleteUser(userId);
        boolean gone = true;
        for (BUserInfo user : spaces()) {
            if (user.id == userId) {
                gone = false;
                break;
            }
        }
        ZenixLog.i("SPACE", "space id=" + userId + (gone ? " deleted" : " STILL PRESENT"));
        return gone;
    }

    // ------------------------------------------------------------- microG

    public static StackStatus status(int userId) {
        StackStatus status = new StackStatus(userId);
        status.gms = isInstalled(PKG_GMS, userId);
        status.gsf = isInstalled(PKG_GSF, userId);
        status.vending = isInstalled(PKG_VENDING, userId);
        if (!status.gms) {
            status.missing.add(PKG_GMS);
        }
        if (!status.gsf) {
            status.missing.add(PKG_GSF);
        }
        if (!status.vending) {
            status.missing.add(PKG_VENDING);
        }
        return status;
    }

    public static boolean isInstalled(String packageName, int userId) {
        try {
            return BlackBoxCore.get().isInstalled(packageName, userId);
        } catch (Throwable tr) {
            ZenixLog.fault("QUERY", "isInstalled(" + packageName + ", user=" + userId + ")", tr);
            return false;
        }
    }

    /**
     * Install the whole microG stack into one space.
     *
     * @param fromContext used only to stage payload assets; pass the host context.
     * @return true only when all three packages verify as installed.
     */
    public static boolean installMicroG(Context fromContext, int userId) {
        return installMicroG(fromContext, userId, false);
    }

    /**
     * @param force when true, uninstalls any existing (possibly broken) copies
     *              of the three packages first, then reinstalls all of them.
     *              Without this, an "installed" package is skipped forever --
     *              which makes Reinstall a no-op for a broken stack.
     */
    public static boolean installMicroG(Context fromContext, int userId, boolean force) {
        ZenixLog.i("MICROG", "install start: space=" + userId + " stack=" + STACK_NAME
                + " force=" + force);

        if (force) {
            for (String pkg : new String[]{PKG_GMS, PKG_GSF, PKG_VENDING}) {
                if (isInstalled(pkg, userId)) {
                    ZenixLog.i("MICROG", "force: uninstalling existing " + pkg + " from space "
                            + userId);
                    try {
                        BlackBoxCore.get().uninstallPackageAsUser(pkg, userId);
                    } catch (Throwable tr) {
                        ZenixLog.fault("MICROG", "force-uninstall " + pkg + " threw", tr);
                    }
                }
            }
        }

        ZenixPayload.materialize(fromContext);
        File dir = ZenixPayload.payloadDir(fromContext);
        List<String> missingFiles = ZenixPayload.missingFiles(fromContext);
        if (!missingFiles.isEmpty()) {
            ZenixLog.e("MICROG", "payload incomplete, missing " + missingFiles
                    + " -- see tools/fetch-microg.sh");
            return false;
        }

        List<String> failures = new ArrayList<>();
        for (int index = 0; index < ZenixPayload.FILES.length; index++) {
            String fileName = ZenixPayload.FILES[index];
            String expectedPackage = ZenixPayload.PACKAGES[index];
            if (isInstalled(expectedPackage, userId)) {
                ZenixLog.i("MICROG", expectedPackage + " already installed in space "
                        + userId + ", skipped");
                continue;
            }
            File apk = new File(dir, fileName);
            if (!apk.isFile() || apk.length() == 0) {
                ZenixLog.e("MICROG", fileName + " missing at " + apk.getAbsolutePath());
                failures.add(fileName + " (missing)");
                continue;
            }
            // ABI gate before the copy: Bcore would reject this later anyway, but
            // only after pushing ~100 MB through its parser. Decline by name now.
            String abiDecline = abiDecline(apk, BlackBoxCore.is64Bit());
            if (abiDecline != null) {
                ZenixLog.e("MICROG", fileName + " DECLINED: " + abiDecline);
                failures.add(fileName + " (abi)");
                continue;
            }
            long start = System.currentTimeMillis();
            InstallResult result;
            try {
                result = BlackBoxCore.get().installPackageAsUser(apk, userId);
            } catch (Throwable tr) {
                ZenixLog.fault("MICROG", "install " + fileName + " threw", tr);
                failures.add(fileName + " (threw)");
                continue;
            }
            long took = System.currentTimeMillis() - start;
            if (result == null || !result.success) {
                ZenixLog.e("MICROG", fileName + " FAILED in " + took + "ms :: "
                        + (result == null ? "null result" : result.msg));
                failures.add(fileName + " (" + (result == null ? "null" : result.msg) + ")");
                continue;
            }
            ZenixLog.i("MICROG", fileName + " installed as " + result.packageName
                    + " in " + took + "ms");
            if (result.packageName != null && !expectedPackage.equals(result.packageName)) {
                ZenixLog.w("MICROG", "expected " + expectedPackage + " but APK declares "
                        + result.packageName + " -- payload file is the wrong flavour");
            }
        }
        if (!failures.isEmpty()) {
            ZenixLog.e("MICROG", "install finished with failures: " + failures
                    + " -- continuing to verify what did install");
        }

        StackStatus status = status(userId);
        ZenixLog.i("MICROG", "verify space=" + userId + " :: " + status.summary());
        if (!status.complete()) {
            ZenixLog.e("MICROG", "stack INCOMPLETE, missing " + status.missing);
            return false;
        }
        ZenixLog.i("MICROG", "stack READY in space " + userId
                + " (signature spoofing still required by the host ROM)");
        return true;
    }

    /** Manual path for any APK the user picks; the fallback when auto fails. */
    public static InstallResult installFromUri(Uri uri, int userId) {
        ZenixLog.i("INSTALL", "uri install start: space=" + userId + " uri=" + uri);
        long start = System.currentTimeMillis();
        try {
            InstallResult result = BlackBoxCore.get().installPackageAsUser(uri, userId);
            long took = System.currentTimeMillis() - start;
            if (result != null && result.success) {
                ZenixLog.i("INSTALL", result.packageName + " installed in " + took
                        + "ms, space=" + userId);
            } else {
                ZenixLog.e("INSTALL", "FAILED in " + took + "ms :: "
                        + (result == null ? "null result" : result.msg));
            }
            return result;
        } catch (Throwable tr) {
            ZenixLog.fault("INSTALL", "installPackageAsUser(uri) threw", tr);
            return null;
        }
    }

    public static boolean uninstallStack(int userId) {
        ZenixLog.i("MICROG", "uninstall stack: space=" + userId);
        // Reverse order of install: store stub, proxy, then core.
        for (int index = ZenixPayload.PACKAGES.length - 1; index >= 0; index--) {
            String packageName = ZenixPayload.PACKAGES[index];
            if (!isInstalled(packageName, userId)) {
                continue;
            }
            try {
                BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
                ZenixLog.i("MICROG", packageName + " removed from space " + userId);
            } catch (Throwable tr) {
                ZenixLog.fault("MICROG", "uninstall " + packageName + " threw", tr);
            }
        }
        StackStatus status = status(userId);
        ZenixLog.i("MICROG", "after uninstall space=" + userId + " :: " + status.summary());
        return !status.complete();
    }

    // ----------------------------------------------------------- self test

    /**
     * Static self-test for the "Diagnostics" button: proves the container can
     * lift an APK out of its own assets and read a package identity from it,
     * before the user waits on a 100 MB guest install.
     *
     * @return lines to render in the console; assert failures are logged as ERROR.
     */
    public static List<String> selfTest(Context context) {
        List<String> report = new ArrayList<>();
        ZenixLog.i("TEST", "self test start");

        boolean is64 = BlackBoxCore.is64Bit();
        report.add("container abi: " + (is64 ? "64-bit (arm64 only)" : "32-bit (arm only)"));
        ZenixLog.i("TEST", "abi=" + (is64 ? "64" : "32"));

        report.add("host package: " + BlackBoxCore.getHostPkg());
        report.add("spaces: " + spaces().size());

        List<String> missing = ZenixPayload.missingFiles(context);
        List<String> inAssets = ZenixPayload.assetsAvailable(context);
        if (missing.isEmpty()) {
            report.add("payload: staged (" + ZenixPayload.FILES.length + " apk)");
            ZenixLog.i("TEST", "payload staged, files=" + ZenixPayload.FILES.length);
        } else if (!inAssets.isEmpty()) {
            report.add("payload: in APK (" + inAssets.size() + " file(s)) but NOT staged yet");
            report.add("  -> tap Install microG, staging runs automatically");
            ZenixLog.w("TEST", "payload in assets " + inAssets + " but not staged, missing " + missing);
        } else {
            report.add("payload: MISSING " + missing + " (not in this APK either)");
            report.add("  -> wrong APK: install the 108 MB release build");
            ZenixLog.w("TEST", "payload missing " + missing + " and no assets shipped");
        }
        report.add("payload dir: " + ZenixPayload.payloadDir(context).getAbsolutePath());

        // Proves the host PM can parse a whole APK identity without installing
        // it. This is exactly what the container does before an install, so a
        // failure here predicts a failed guest install.
        String apkPath = stageAnyPayloadApk(context);
        if (apkPath == null) {
            report.add("archive probe: skipped (no payload on disk)");
        } else {
            try {
                PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apkPath, 0);
                if (info == null) {
                    report.add("archive probe: FAILED (unparseable " + new File(apkPath).getName() + ")");
                    ZenixLog.e("TEST", "getPackageArchiveInfo returned null for " + apkPath);
                } else {
                    report.add("archive probe: " + info.packageName + " v" + info.versionName);
                    ZenixLog.i("TEST", "archive probe ok: " + info.packageName
                            + " v" + info.versionName + " code=" + info.versionCode);
                }
            } catch (Throwable tr) {
                report.add("archive probe: FAILED " + tr.getClass().getSimpleName());
                ZenixLog.fault("TEST", "archive probe threw", tr);
            }
        }

        // Real installed-package inventory, so the console shows what the
        // container actually knows rather than what we hope it knows.
        int appCount = 0;
        try {
            List<ApplicationInfo> installed =
                    BlackBoxCore.get().getInstalledApplications(0, BlackBoxCore.getHostUserId());
            appCount = installed == null ? 0 : installed.size();
        } catch (Throwable tr) {
            ZenixLog.fault("TEST", "getInstalledApplications threw", tr);
        }
        report.add("guest apps in space 0: " + appCount);
        ZenixLog.i("TEST", "space 0 guest apps=" + appCount);

        Set<String> uniqueMissing = new LinkedHashSet<>(missing);
        if (!uniqueMissing.isEmpty() && !inAssets.isEmpty()) {
            report.add("verdict: UNTESTED -- payload is in the APK, tap Install microG to stage it");
        } else if (!uniqueMissing.isEmpty()) {
            report.add("verdict: UNTESTED -- this APK shipped without the payload");
        } else {
            report.add("verdict: container IO OK");
        }
        ZenixLog.i("TEST", "self test end, " + report.size() + " checks");
        return report;
    }

    private static String stageAnyPayloadApk(Context context) {
        for (String name : ZenixPayload.FILES) {
            File file = new File(ZenixPayload.payloadDir(context), name);
            if (file.isFile() && file.length() > 0) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Mirrors Bcore's AbiUtils rule (lib/arm64-v8a vs lib/armeabi*), without
     * caching or copying: an APK carrying only 64-bit objects cannot run in the
     * 32-bit box and vice versa.
     *
     * @return null when installable, otherwise the reason, for the log.
     */
    static String abiDecline(File apk, boolean containerIs64) {
        boolean has64 = false;
        boolean has32 = false;
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(apk);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("lib/")) {
                    continue;
                }
                if (name.startsWith("lib/arm64-v8a/")) {
                    has64 = true;
                } else if (name.startsWith("lib/armeabi")) {
                    has32 = true;
                }
                if (has64 && has32) {
                    return null;
                }
            }
        } catch (Throwable tr) {
            ZenixLog.fault("MICROG", "abi scan failed for " + apk.getName(), tr);
            return null; // let Bcore decide rather than block on our own error
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
        if (!has64 && !has32) {
            return null; // no native code at all: runs anywhere
        }
        if (containerIs64 && !has64) {
            return "APK ships armeabi only, box is arm64";
        }
        if (!containerIs64 && !has32) {
            return "APK ships arm64 only, this is the 32-bit box";
        }
        return null;
    }

    /** Kept for callers that want the package label, never for logic. */
    public static String labelOf(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }
}
