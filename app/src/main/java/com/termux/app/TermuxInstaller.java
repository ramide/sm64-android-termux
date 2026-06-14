package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                // Re-apply fixes in case they were from an older version
                fixPrefixPaths();
                setupAptConfig();
                setupDpkgConfig();
                fixGpgKeys();
                fixApkPermissions();
                copyExecHookLibrary(activity);
                setupMenuScripts(activity);
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Fix hardcoded /data/data/com.termux paths to use this fork's package path
                    fixPrefixPaths();

                    // Setup apt config to use this fork's package path
                    setupAptConfig();

                    // Setup dpkg config to use this fork's package path
                    setupDpkgConfig();

                    // Fix GPG key symlinks pointing to old com.termux paths
                fixGpgKeys();
                fixCertificates();

                    // Create CA certificate symlink for HTTPS support
                    fixCertificates();

                    // Make APK files read-only (Android 16 blocks writable dex files)
                    fixApkPermissions();

                    // Copy LD_PRELOAD hook library from APK native libs to $PREFIX/lib
                    copyExecHookLibrary(activity);

                    // Setup SM64 builder menu and build scripts
                    setupMenuScripts(activity);

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /** Replace all /data/data/com.termux paths with the correct package path. */
    private static void fixPrefixPaths() {
        String oldPrefix = "/data/data/com.termux";
        String newPrefix = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME;
        if (oldPrefix.equals(newPrefix)) return;

        fixPrefixPathsInDir(new File(TERMUX_PREFIX_DIR_PATH + "/bin"), oldPrefix, newPrefix);
        fixPrefixPathsInDir(new File(TERMUX_PREFIX_DIR_PATH + "/libexec"), oldPrefix, newPrefix);
        fixPrefixPathsInDir(new File(TERMUX_PREFIX_DIR_PATH + "/etc"), oldPrefix, newPrefix);
        fixPrefixPathsInDir(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), oldPrefix, newPrefix);
    }

    /** Scan directory recursively and replace oldPrefix with newPrefix in text files. */
    private static void fixPrefixPathsInDir(File dir, String oldPrefix, String newPrefix) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                fixPrefixPathsInDir(file, oldPrefix, newPrefix);
            } else if (file.isFile() && file.canRead()) {
                fixPrefixPathsInFile(file, oldPrefix, newPrefix);
            }
        }
    }

    /** Replace all occurrences of oldPrefix in a text file. Skips ELF binaries. */
    private static void fixPrefixPathsInFile(File file, String oldPrefix, String newPrefix) {
        try {
            // Skip ELF binaries — replacing strings in them would corrupt the binary
            byte[] magic = new byte[4];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int n = fis.read(magic);
                if (n >= 4 && magic[0] == 0x7f && magic[1] == 'E' &&
                    magic[2] == 'L' && magic[3] == 'F') return;
                if (file.getName().endsWith(".so")) return;
            }

            byte[] contentBytes = new byte[(int) Math.min(file.length(), 40960)];
            int bytesRead;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                bytesRead = fis.read(contentBytes);
            }
            if (bytesRead <= 0) return;

            String content = new String(contentBytes, 0, bytesRead, "UTF-8");
            if (!content.contains(oldPrefix)) return;
            String newContent = content.replace(oldPrefix, newPrefix);
            byte[] newBytes = newContent.getBytes("UTF-8");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write(newBytes);
            }
            Logger.logInfo(LOG_TAG, "Fixed paths in " + file.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to fix paths in " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** Create apt config override so apt uses this fork's paths instead of compiled-in defaults. */
    private static void setupAptConfig() {
        String aptConfDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/apt.conf.d";
        File confDir = new File(aptConfDir);
        if (!confDir.isDirectory() && !confDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create apt config dir");
            return;
        }
        File configFile = new File(aptConfDir, "00sm64builder.conf");
        if (!configFile.exists()) {
            String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            String content = "Dir \"" + prefix + "\";\n"
                + "Dir::State \"" + prefix + "/var/lib/apt\";\n"
                + "Dir::Cache \"" + prefix + "/var/cache/apt\";\n"
                + "Dir::Etc \"" + prefix + "/etc/apt\";\n"
                + "Dir::Etc::TrustedParts \"trusted.gpg.d\";\n"
                + "Dir::Temp \"" + prefix + "/tmp\";\n"
                + "Dir::Log \"" + prefix + "/var/log/apt\";\n"
                + "Dir::Bin::Methods \"" + prefix + "/lib/apt/methods\";\n"
                + "Dir::Bin::apt-key \"" + prefix + "/bin/apt-key\";\n"
                + "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n"
                + "gpgv::Bin \"" + prefix + "/bin/gpgv\";\n"
                + "Acquire::https::CaInfo \"" + prefix + "/etc/tls/cert.pem\";\n"
                + "Acquire::AllowInsecureRepositories \"true\";\n"
                + "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n"
                + "APT::Get::AllowUnauthenticated \"true\";\n"
                + "DPkg::Options:: \"--admindir=" + prefix + "/var/lib/dpkg\";\n"
                + "DPkg::Path \"" + prefix + "/bin:/system/bin\";\n";
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                fos.write(content.getBytes("UTF-8"));
                Logger.logInfo(LOG_TAG, "Created apt config override at " + configFile.getAbsolutePath());
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to create apt config override: " + e.getMessage());
            }
        }

        // Reset sources.list to official repo with trusted=yes
        File sourcesList = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list");
        String sourcesContent = "deb [trusted=yes] https://packages.termux.dev/apt/termux-main/ stable main\n";
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(sourcesList)) {
            fos.write(sourcesContent.getBytes("UTF-8"));
            Logger.logInfo(LOG_TAG, "Reset sources.list");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to reset sources.list: " + e.getMessage());
        }

        // Create apt cache directories
        new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/cache/apt/archives/partial").mkdirs();
        new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/apt/lists/partial").mkdirs();
    }

    /** Create dpkg config override so dpkg uses this fork's package path instead of compiled-in defaults. */
    private static void setupDpkgConfig() {
        String dpkgCfgDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/dpkg/dpkg.cfg.d";
        File cfgDir = new File(dpkgCfgDir);
        if (!cfgDir.isDirectory() && !cfgDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create dpkg config dir");
            return;
        }
        File configFile = new File(dpkgCfgDir, "00prefix.conf");
        if (configFile.exists()) return;

        String admindir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/dpkg";
        String content = "admindir " + admindir + "\n";
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
            fos.write(content.getBytes("UTF-8"));
            Logger.logInfo(LOG_TAG, "Created dpkg config override at " + configFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create dpkg config override: " + e.getMessage());
        }
    }

    /** Fix GPG key symlinks pointing to old /data/data/com.termux paths. */
    private static void fixGpgKeys() {
        File gpgDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/trusted.gpg.d");
        if (!gpgDir.isDirectory()) return;
        File keyringDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/termux-keyring");
        if (!keyringDir.isDirectory()) return;

        String oldPrefix = "/data/data/com.termux";
        String newPrefix = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME;

        for (File f : gpgDir.listFiles()) {
            try {
                if (!java.nio.file.Files.isSymbolicLink(f.toPath())) continue;
                String target = java.nio.file.Files.readSymbolicLink(f.toPath()).toString();
                if (!target.startsWith(oldPrefix)) continue;
                String keyName = target.substring(target.lastIndexOf('/') + 1);
                File realKey = new File(keyringDir, keyName);
                if (!realKey.exists()) continue;
                f.delete();
                Error error = FileUtils.copyFile("gpg key", realKey.getAbsolutePath(),
                    f.getAbsolutePath(), false);
                if (error == null) {
                    Logger.logInfo(LOG_TAG, "Fixed GPG key: " + f.getName());
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to fix GPG key " + f.getName() + ": " + e.getMessage());
            }
        }
    }

    /** Create CA certificate symlink for HTTPS support. */
    private static void fixCertificates() {
        File sslCerts = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/ssl/certs");
        sslCerts.mkdirs();
        File certBundle = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
        if (!certBundle.exists()) return;
        File caSymlink = new File(sslCerts, "ca-certificates.crt");
        if (!caSymlink.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(caSymlink.toPath(), certBundle.toPath());
                Logger.logInfo(LOG_TAG, "Created CA cert symlink");
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to create CA symlink: " + e.getMessage());
            }
        }
    }

    /** Make APK files read-only (Android 16 blocks loading writable dex files). */
    private static void fixApkPermissions() {
        File libexecDir = new File(TERMUX_PREFIX_DIR_PATH + "/libexec");
        if (!libexecDir.isDirectory()) return;
        File[] apkFiles = libexecDir.listFiles((java.io.FileFilter) f ->
            f.isFile() && f.getName().endsWith(".apk"));
        if (apkFiles == null) return;
        for (File apk : apkFiles) {
            if (apk.setWritable(false, false) && apk.setReadable(true, false)) {
                Logger.logInfo(LOG_TAG, "Made APK read-only: " + apk.getName());
            }
        }
    }

    /**
     * Copy the LD_PRELOAD exec hook library from the APK's native lib directory
     * to $PREFIX/lib/, so it can be loaded via LD_PRELOAD.
     *
     * The hook intercepts execve() calls from within the shell and redirects them
     * through /system/bin/linker64, bypassing Android 16's restriction on
     * executing binaries from app data directories.
     */
    private static void copyExecHookLibrary(Context context) {
        String targetPath = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libtermux-exec-hook.so";
        File targetFile = new File(targetPath);
        if (targetFile.exists()) return;

        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File sourceFile = new File(nativeLibDir, "libtermux-exec-hook.so");
        if (!sourceFile.exists()) {
            Logger.logError(LOG_TAG, "Exec hook library not found at " + sourceFile.getPath());
            return;
        }

        Error error = FileUtils.copyFile("exec hook library", sourceFile.getAbsolutePath(), targetPath, false);
        if (error == null) {
            targetFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Copied exec hook library to " + targetPath);
        } else {
            Logger.logError(LOG_TAG, "Failed to copy exec hook library: " + error.toString());
        }
    }

    private static void setupMenuScripts(Context context) {
        String binDir = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin";
        String homeDir = TermuxConstants.TERMUX_HOME_DIR_PATH;

        // Ensure ~/.termux directory exists
        File termuxDir = new File(homeDir, ".termux");
        if (!termuxDir.isDirectory()) {
            termuxDir.mkdirs();
        }

        // Write SM64 builder menu script
        String menuScript = "#!/data/data/com.sm64builder/files/usr/bin/bash\n"
            + "# SM64 Builder Menu\n"
            + "PREFIX=/data/data/com.sm64builder/files/usr\n"
            + "HOME=$PREFIX/home\n"
            + "PATH=$PREFIX/bin:/system/bin\n"
            + "LD_LIBRARY_PATH=$PREFIX/lib\n"
            + "LD_PRELOAD=$PREFIX/lib/libtermux-exec-hook.so\n"
            + "export PREFIX HOME PATH LD_LIBRARY_PATH LD_PRELOAD\n"
            + "mkdir -p ~/.termux 2>/dev/null\n"
            + "ln -sf $PREFIX/etc/motd.sh ~/.termux/motd.sh 2>/dev/null\n"
            + "show_menu() {\n"
            + "echo -e \""
            + "\\\\e[1;33m====== SM64 Builder ======\\\\e[0m\\n"
            + "\\\\e[32m1)\\\\e[0m \\\\e[31mSM64EX 60fps Internal\\\\e[0m\\n"
            + "\\\\e[32m0)\\\\e[0m \\\\e[31mExit\\\\e[0m\\n"
            + "\\\\e[34mChoose: \\\\e[0m \"\n"
            + "    read a\n"
            + "    case $a in\n"
            + "        1) build_sm64_int ;;\n"
            + "        0) exit 0 ;;\n"
            + "        *) echo \"Wrong option.\"; show_menu ;;\n"
            + "    esac\n"
            + "}\n"
            + "build_sm64_int() {\n"
            + "    echo \"=== SM64 INT Build ===\"\n"
            + "    cd $HOME\n"
            + "    rm -rf sm64-build 2>/dev/null\n"
            + "    mkdir -p sm64-build\n"
            + "    cd sm64-build\n"
            + "    echo \"Downloading source...\"\n"
            + "    wget -q -O sm64.zip \"https://github.com/izzy2fancy/sm64-izzys-port-android/archive/refs/heads/ex/nightly.zip\"\n"
            + "    python3 -c \"import zipfile; zipfile.ZipFile('sm64.zip').extractall('.')\"\n"
            + "    cd sm64-izzys-port-android-ex-nightly\n"
            + "    cp ../baserom.us.z64 . 2>/dev/null || cp /storage/emulated/0/Download/baserom.us.z64 . 2>/dev/null\n"
            + "    echo \"Extracting assets...\"\n"
            + "    python3 extract_assets.py us 2>/dev/null\n"
            + "    echo \"Building...\"\n"
            + "    make -f MakefileINT 2>&1 | tee build.log\n"
            + "    APK=\$(find build -name \"*.apk\" 2>/dev/null | head -1)\n"
            + "    if [ -n \"\$APK\" ]; then\n"
            + "        cp \"\$APK\" /storage/emulated/0/\n"
            + "        echo \"APK copied to /storage/emulated/0/\"\n"
            + "    else\n"
            + "        echo \"Build failed. Check build.log\"\n"
            + "    fi\n"
            + "}\n"
            + "show_menu\n";

        File scriptFile = new File(binDir, "sm64_menu.sh");
        try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
            fos.write(menuScript.getBytes("UTF-8"));
            scriptFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Created SM64 menu script at " + scriptFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create SM64 menu script: " + e.getMessage());
        }
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
