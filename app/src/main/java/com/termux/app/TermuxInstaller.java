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
        StringBuilder sb = new StringBuilder();
        sb.append("#!/data/data/com.sm64builder/files/usr/bin/bash\n");
        sb.append("# SM64 Builder Menu\n");
        sb.append("PREFIX=/data/data/com.sm64builder/files/usr\n");
        sb.append("PATH=$PREFIX/bin:/system/bin\n");
        sb.append("LD_LIBRARY_PATH=$PREFIX/lib\n");
        sb.append("LD_PRELOAD=$PREFIX/lib/libtermux-exec-hook.so\n");
        sb.append("TMPDIR=$PREFIX/tmp\n");
        sb.append("export PREFIX PATH LD_LIBRARY_PATH LD_PRELOAD TMPDIR\n");
        sb.append("mkdir -p ~/.termux 2>/dev/null\n");
        sb.append("ln -sf $PREFIX/etc/motd.sh ~/.termux/motd.sh 2>/dev/null\n");
        sb.append("\n");
        sb.append("# Exit immediately if not interactive (prevents make recursion)\n");
        sb.append("[ -t 0 ] || exit 0\n");
        sb.append("\n");
        sb.append("run_build() {\n");
        sb.append("    local script=\"$1\"\n");
        sb.append("    \n");
        sb.append("    # Step 1: Ensure dependencies are installed\n");
        sb.append("    bash $PREFIX/bin/setup-build-deps.sh || {\n");
        sb.append("        echo \"Dependency setup failed. Check network and try again.\"\n");
        sb.append("        return 1\n");
        sb.append("    }\n");
        sb.append("    \n");
        sb.append("    # Step 2: Run the build script (with correct HOME for baserom lookup)\n");
        sb.append("    if [ -x \"$PREFIX/bin/$script\" ]; then\n");
        sb.append("        HOME=$PREFIX/home \"$PREFIX/bin/$script\"\n");
        sb.append("        local rc=$?\n");
        sb.append("    else\n");
        sb.append("        echo \"Build script $script not found in $PREFIX/bin/\"\n");
        sb.append("        return 1\n");
        sb.append("    fi\n");
        sb.append("    \n");
        sb.append("    # Step 3: Copy signed APK to home\n");
        sb.append("    local apk=$(find $PREFIX/home/sm64-izzys-port-android-ex-nightly/build -name \"*.apk\" 2>/dev/null | head -1)\n");
        sb.append("    if [ -n \"$apk\" ]; then\n");
        sb.append("        cp \"$apk\" \"$PREFIX/home/sm64.apk\" 2>/dev/null\n");
        sb.append("        echo \"\"\n");
        sb.append("        echo \"========================================\"\n");
        sb.append("        echo \"  BUILD COMPLETE\"\n");
        sb.append("        echo \"  APK: $PREFIX/home/sm64.apk\"\n");
        sb.append("        echo \"\"\n");
        sb.append("        echo \"  Pull to PC:\"\n");
        sb.append("        echo \"  adb exec-out run-as com.sm64builder sh -c 'cat \\$PREFIX/home/sm64.apk' > sm64.apk\"\n");
        sb.append("        echo \"========================================\"\n");
        sb.append("    else\n");
        sb.append("        echo \"\"\n");
        sb.append("        echo \"========================================\"\n");
        sb.append("        echo \"  BUILD FAILED (exit code $rc)\"  \n");
        sb.append("        echo \"  Scroll up to see error details.\"\n");
        sb.append("        echo \"========================================\"\n");
        sb.append("    fi\n");
        sb.append("    \n");
        sb.append("    echo \"\"\n");
        sb.append("    echo \"Press Enter to return to menu\"\n");
        sb.append("    read dummy\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("while true; do\n");
        sb.append("    echo \"====== SM64 Builder ======\"\n");
        sb.append("    echo \"1) SM64EX COOP\"\n");
        sb.append("    echo \"2) SM64EX COOP RENDER96\"\n");
        sb.append("    echo \"3) SM64EX OMM\"\n");
        sb.append("    echo \"4) SM64EX ALO\"\n");
        sb.append("    echo \"5) SM64EX 60fps External\"\n");
        sb.append("    echo \"6) SM64EX 60fps Internal\"\n");
        sb.append("    echo \"7) SM64EX EXT No Touch\"\n");
        sb.append("    echo \"8) SM64EX INT No Touch\"\n");
        sb.append("    echo \"9) SM64EX Porcino\"\n");
        sb.append("    echo \"10) Star Road\"\n");
        sb.append("    echo \"0) Exit\"\n");
        sb.append("    echo -n \"Choose: \"\n");
        sb.append("    read a\n");
        sb.append("    case $a in\n");
        sb.append("        1) run_build build-sm64ex-coop.sh ;;\n");
        sb.append("        2) run_build build-sm64ex-coop-render96.sh ;;\n");
        sb.append("        3) run_build build-sm64ex-omm.sh ;;\n");
        sb.append("        4) run_build build-sm64ex-alo.sh ;;\n");
        sb.append("        5) run_build build-sm64ex-EXT.sh ;;\n");
        sb.append("        6) run_build build-sm64ex-INT.sh ;;\n");
        sb.append("        7) run_build build-sm64ex-EXTnoTouch.sh ;;\n");
        sb.append("        8) run_build build-sm64ex-INTnoTouch.sh ;;\n");
        sb.append("        9) run_build build-sm64ex-porcino.sh ;;\n");
        sb.append("        10) run_build build-starroad.sh ;;\n");
        sb.append("        0) exit 0 ;;\n");
        sb.append("        *) echo \"Wrong option.\" ;;\n");
        sb.append("    esac\n");
        sb.append("done\n");

        File menuFile = new File(binDir, "sm64_menu.sh");
        try (FileOutputStream fos = new FileOutputStream(menuFile)) {
            fos.write(sb.toString().getBytes("UTF-8"));
            menuFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Created SM64 menu script at " + menuFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create SM64 menu script: " + e.getMessage());
        }

        // ── Write termux-install-deb.py helper ──
        StringBuilder pySb = new StringBuilder();
        pySb.append("#!/data/data/com.sm64builder/files/usr/bin/python3\n");
        pySb.append("# Extract Termux .deb to $PREFIX with path remapping\n");
        pySb.append("import tarfile, os, subprocess, sys\n");
        pySb.append("\n");
        pySb.append("PREFIX = '/data/data/com.sm64builder/files/usr'\n");
        pySb.append("PRE = './data/data/com.termux/files/usr/'\n");
        pySb.append("OFFSET = len(PRE)\n");
        pySb.append("\n");
        pySb.append("deb = sys.argv[1]\n");
        pySb.append("if not os.path.exists(deb):\n");
        pySb.append("    sys.exit(f'File not found: {deb}')\n");
        pySb.append("\n");
        pySb.append("# Use dpkg-deb to extract tar stream (ar not available in bootstrap)\n");
        pySb.append("proc = subprocess.Popen(['dpkg-deb', '--fsys-tarfile', deb], stdout=subprocess.PIPE)\n");
        pySb.append("with tarfile.open(fileobj=proc.stdout, mode='r|*') as tar:\n");
        pySb.append("        for member in tar:\n");
        pySb.append("            name = member.name\n");
        pySb.append("            if name in ('./', '.'):\n");
        pySb.append("                continue\n");
        pySb.append("            if not name.startswith(PRE):\n");
        pySb.append("                continue\n");
        pySb.append("            dest = PREFIX + '/' + name[OFFSET:]\n");
        pySb.append("            if member.isdir():\n");
        pySb.append("                os.makedirs(dest, exist_ok=True)\n");
        pySb.append("            elif member.issym():\n");
        pySb.append("                if os.path.lexists(dest):\n");
        pySb.append("                    os.remove(dest)\n");
        pySb.append("                os.symlink(member.linkname, dest)\n");
        pySb.append("            else:\n");
        pySb.append("                rf = tar.extractfile(member)\n");
        pySb.append("                if rf:\n");
        pySb.append("                    os.makedirs(os.path.dirname(dest), exist_ok=True)\n");
        pySb.append("                    with open(dest, 'wb') as f:\n");
        pySb.append("                        f.write(rf.read())\n");
        pySb.append("                    os.chmod(dest, member.mode & 0o7777)\n");
        pySb.append("proc.wait()\n");

        File pyHelperFile = new File(binDir, "termux-install-deb.py");
        try (FileOutputStream fos = new FileOutputStream(pyHelperFile)) {
            fos.write(pySb.toString().getBytes("UTF-8"));
            pyHelperFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Created deb install helper at " + pyHelperFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create deb install helper: " + e.getMessage());
        }

        // ── Write setup-build-deps.sh ──
        StringBuilder depsSb = new StringBuilder();
        depsSb.append("#!/data/data/com.sm64builder/files/usr/bin/bash\n");
        depsSb.append("# Idempotent build dependency installer\n");
        depsSb.append("# Called once by sm64_menu.sh before the first build\n");
        depsSb.append("\n");
        depsSb.append("PREFIX=/data/data/com.sm64builder/files/usr\n");
        depsSb.append("PATH=$PREFIX/bin:/system/bin\n");
        depsSb.append("LD_LIBRARY_PATH=$PREFIX/lib\n");
        depsSb.append("LD_PRELOAD=$PREFIX/lib/libtermux-exec-hook.so\n");
        depsSb.append("APT_CONFIG=$PREFIX/etc/apt/apt.conf.d/00sm64builder.conf\n");
        depsSb.append("TMPDIR=$PREFIX/tmp\n");
        depsSb.append("JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk\n");
        depsSb.append("SENTINEL=$PREFIX/tmp/.build_deps_installed\n");
        depsSb.append("export PREFIX PATH LD_LIBRARY_PATH LD_PRELOAD APT_CONFIG TMPDIR\n");
        depsSb.append("\n");
        depsSb.append("# ── Check if already installed ──\n");
        depsSb.append("if [ -f \"$SENTINEL\" ]; then\n");
        depsSb.append("    if python3 --version >/dev/null 2>&1 && java -version >/dev/null 2>&1 && apksigner --version >/dev/null 2>&1; then\n");
        depsSb.append("        echo \"Build dependencies already installed. Skip.\"\n");
        depsSb.append("        exit 0\n");
        depsSb.append("    fi\n");
        depsSb.append("    echo \"Sentinel found but build deps broken, reinstalling...\"\n");
        depsSb.append("fi\n");
        depsSb.append("\n");
        depsSb.append("echo \"=== Setting up build dependencies ===\"\n");
        depsSb.append("\n");
        depsSb.append("# ── Helper: download + extract a Termux deb ──\n");
        depsSb.append("install_deb() {\n");
        depsSb.append("    local pkg=\"$1\"\n");
        depsSb.append("    echo \"  Downloading $pkg...\"\n");
        depsSb.append("    apt-get download \"$pkg\" 2>/dev/null || {\n");
        depsSb.append("        echo \"  FAILED: apt-get download $pkg\"; return 1; }\n");
        depsSb.append("    local deb=$(ls \"${pkg}_\"*.deb 2>/dev/null | head -1)\n");
        depsSb.append("    [ -z \"$deb\" ] && { echo \"  No deb for $pkg\"; return 1; }\n");
        depsSb.append("    python3 $PREFIX/bin/termux-install-deb.py \"$deb\" || {\n");
        depsSb.append("        echo \"  FAILED: extract $pkg\"; return 1; }\n");
        depsSb.append("    rm -f \"$deb\"\n");
        depsSb.append("    return 0\n");
        depsSb.append("}\n");
        depsSb.append("\n");
        depsSb.append("# ── 1. Update apt cache ──\n");
        depsSb.append("echo \"[1/6] Updating apt cache...\"\n");
        depsSb.append("apt-get update 2>/dev/null || echo \"  Warning: apt update failed, continuing with cached data\"\n");
        depsSb.append("\n");
        depsSb.append("# ── 2. Install python (needed by termux-install-deb.py) ──\n");
        depsSb.append("echo \"[2/6] Installing python...\"\n");
        depsSb.append("if ! python3 --version >/dev/null 2>&1; then\n");
        depsSb.append("    savedir=$(pwd)\n");
        depsSb.append("    echo \"  Downloading python...\"\n");
        depsSb.append("    apt-get download python 2>/dev/null || { echo \"  FAILED: apt-get download python\"; exit 1; }\n");
        depsSb.append("    deb=$(ls python_*.deb 2>/dev/null | head -1)\n");
        depsSb.append("    [ -z \"$deb\" ] && { echo \"  No python deb found\"; exit 1; }\n");
        depsSb.append("    echo \"  Extracting python...\"\n");
        depsSb.append("    mkdir -p $TMPDIR/py_install\n");
        depsSb.append("    # Extract via fsys-tarfile (LD_PRELOAD remaps com.termux -> com.sm64builder during I/O)\n");
        depsSb.append("    dpkg-deb --fsys-tarfile \"$savedir/$deb\" | tar -xf - -C $TMPDIR/py_install 2>/dev/null || true\n");
        depsSb.append("    # Copy files to PREFIX (files are at com.sm64builder path due to LD_PRELOAD remapping)\n");
        depsSb.append("    srcdir=\"$TMPDIR/py_install/data/data/com.sm64builder/files/usr\"\n");
        depsSb.append("    if [ -d \"$srcdir\" ]; then\n");
        depsSb.append("        cp -a \"$srcdir/bin\" \"$srcdir/lib\" \"$srcdir/share\" $PREFIX/ 2>/dev/null || true\n");
        depsSb.append("    fi\n");
        depsSb.append("    # Toybox tar skips dangling symlinks; recreate them manually\n");
        depsSb.append("    pybin=$(ls $PREFIX/bin/python3.* 2>/dev/null | grep -E 'python3\\.[0-9]+$' | head -1)\n");
        depsSb.append("    if [ -n \"$pybin\" ] && [ -x \"$pybin\" ]; then\n");
        depsSb.append("        [ -L $PREFIX/bin/python3 ] || ln -sf \"$(basename \"$pybin\")\" $PREFIX/bin/python3 2>/dev/null || true\n");
        depsSb.append("        [ -L $PREFIX/bin/python ]  || ln -sf python3 $PREFIX/bin/python 2>/dev/null || true\n");
        depsSb.append("    fi\n");
        depsSb.append("    rm -rf $TMPDIR/py_install \"$savedir/$deb\"\n");
        depsSb.append("    echo \"  python installed\"\n");
        depsSb.append("    python3 --version || { echo \"  ERROR: python3 after install\"; exit 1; }\n");
        depsSb.append("fi\n");
        depsSb.append("\n");
        depsSb.append("# ── 3. Install Java + apksigner + deps ──\n");
        depsSb.append("echo \"[3/6] Installing Java 17 and apksigner...\"\n");

        depsSb.append("for pkg in libandroid-shmem libandroid-spawn libandroid-execinfo libandroid-sysv-semaphore; do\n");
        depsSb.append("    install_deb \"$pkg\" || echo \"  Warning: $pkg failed\"\n");
        depsSb.append("done\n");
        depsSb.append("install_deb openjdk-17 || exit 1\n");
        depsSb.append("install_deb apksigner || exit 1\n");
        depsSb.append("# Fix apksigner shebang (Termux deb installs with com.termux paths)\n");
        depsSb.append("sed -i 's|/data/data/com.termux|/data/data/com.sm64builder|g' $PREFIX/bin/apksigner 2>/dev/null\n");
        depsSb.append("install_deb make || echo \"  Warning: make failed\"\n");
        depsSb.append("install_deb getconf || echo \"  Warning: getconf failed\"\n");
        depsSb.append("install_deb which || echo \"  Warning: which failed\"\n");
        depsSb.append("# Install clang via apt-get (handles complex transitive deps)\n");
        depsSb.append("apt-get install -y clang libc++ 2>/dev/null || echo \"  Warning: clang/libc++ install failed\"\n");
        depsSb.append("# Force-extract ndk-sysroot (apt-get may register without extracting files)\n");
        depsSb.append("apt-get download ndk-sysroot 2>/dev/null\n");
        depsSb.append("ndk_deb=$(ls ndk-sysroot_*.deb 2>/dev/null | head -1)\n");
        depsSb.append("[ -n \"$ndk_deb\" ] && python3 $PREFIX/bin/termux-install-deb.py \"$ndk_deb\" 2>/dev/null; rm -f \"$ndk_deb\"\n");
        depsSb.append("\n");
        depsSb.append("# ── 4. Create java wrapper ──\n");
        depsSb.append("echo \"[4/6] Creating java wrapper...\"\n");
        depsSb.append("cat > $PREFIX/bin/java << 'JAVAEOF'\n");
        depsSb.append("#!/data/data/com.sm64builder/files/usr/bin/bash\n");
        depsSb.append("JAVA_HOME=/data/data/com.sm64builder/files/usr/lib/jvm/java-17-openjdk\n");
        depsSb.append("JAVA_TOOL_OPTIONS=\"-Djava.io.tmpdir=/data/data/com.sm64builder/files/usr/tmp\"\n");
        depsSb.append("LD_LIBRARY_PATH=$JAVA_HOME/lib/server:$JAVA_HOME/lib:$LD_LIBRARY_PATH\n");
        depsSb.append("export JAVA_HOME JAVA_TOOL_OPTIONS LD_LIBRARY_PATH\n");
        depsSb.append("exec $JAVA_HOME/bin/java \"$@\"\n");
        depsSb.append("JAVAEOF\n");
        depsSb.append("chmod +x $PREFIX/bin/java\n");
        depsSb.append("\n");
        depsSb.append("# ── 5. Download SDL2 headers ──\n");
        depsSb.append("echo \"[5/6] Downloading SDL2 and GLES2 headers...\"\n");
        depsSb.append("SDLDIR=$TMPDIR/sdl_headers\n");
        depsSb.append("mkdir -p \"$SDLDIR\"\n");
        depsSb.append("# Download all 91 SDL2 headers via Python (complete list, curl handles HTTPS)\n");
        depsSb.append("python3 -c \"\n");
        depsSb.append("import os\n");
        depsSb.append("headers = '''SDL.h SDL_assert.h SDL_atomic.h SDL_audio.h SDL_bits.h SDL_blendmode.h\n");
        depsSb.append("SDL_clipboard.h SDL_config.h SDL_config_android.h SDL_config_emscripten.h\n");
        depsSb.append("SDL_config_iphoneos.h SDL_config_macosx.h SDL_config_minimal.h SDL_config_ngage.h\n");
        depsSb.append("SDL_config_os2.h SDL_config_pandora.h SDL_config_windows.h SDL_config_wingdk.h\n");
        depsSb.append("SDL_config_winrt.h SDL_config_xbox.h SDL_copying.h SDL_cpuinfo.h SDL_egl.h\n");
        depsSb.append("SDL_endian.h SDL_error.h SDL_events.h SDL_filesystem.h SDL_gamecontroller.h\n");
        depsSb.append("SDL_gesture.h SDL_guid.h SDL_haptic.h SDL_hidapi.h SDL_hints.h SDL_joystick.h\n");
        depsSb.append("SDL_keyboard.h SDL_keycode.h SDL_loadso.h SDL_locale.h SDL_log.h SDL_main.h\n");
        depsSb.append("SDL_messagebox.h SDL_metal.h SDL_misc.h SDL_mouse.h SDL_mutex.h SDL_name.h\n");
        depsSb.append("SDL_opengl.h SDL_opengl_glext.h SDL_opengles.h SDL_opengles2.h\n");
        depsSb.append("SDL_opengles2_gl2.h SDL_opengles2_gl2ext.h SDL_opengles2_gl2platform.h\n");
        depsSb.append("SDL_opengles2_khrplatform.h SDL_pixels.h SDL_platform.h SDL_power.h SDL_quit.h\n");
        depsSb.append("SDL_rect.h SDL_render.h SDL_revision.h SDL_rwops.h SDL_scancode.h SDL_sensor.h\n");
        depsSb.append("SDL_shape.h SDL_stdinc.h SDL_surface.h SDL_system.h SDL_syswm.h\n");
        depsSb.append("SDL_test.h SDL_test_assert.h SDL_test_common.h SDL_test_compare.h\n");
        depsSb.append("SDL_test_crc32.h SDL_test_font.h SDL_test_fuzzer.h SDL_test_harness.h\n");
        depsSb.append("SDL_test_images.h SDL_test_log.h SDL_test_md5.h SDL_test_memory.h\n");
        depsSb.append("SDL_test_random.h SDL_thread.h SDL_timer.h SDL_touch.h SDL_types.h\n");
        depsSb.append("SDL_version.h SDL_video.h SDL_vulkan.h begin_code.h close_code.h'''\n");
        depsSb.append("base = 'https://raw.githubusercontent.com/libsdl-org/SDL/release-2.30.9/include'\n");
        depsSb.append("dest = '$SDLDIR'\n");
        depsSb.append("for h in headers.split():\n");
        depsSb.append("    os.system(f'curl -sfL \\\"{base}/{h}\\\" -o \\\"{dest}/{h}\\\" 2>/dev/null')\n");
        depsSb.append("    if not os.path.exists(f'{dest}/{h}'):\n");
        depsSb.append("        print(f'  WARN: {h}')\n");
        depsSb.append("\"\n");
        depsSb.append("echo \"  SDL2 headers: $(ls \"$SDLDIR\" | wc -l) files\"\n");
        depsSb.append("\n");
        depsSb.append("# GLES2 headers (from Khronos registry)\n");
        depsSb.append("mkdir -p $PREFIX/include/GLES2 $PREFIX/include/KHR\n");
        depsSb.append("GL_BASE=\"https://www.khronos.org/registry/OpenGL/api\"\n");
        depsSb.append("KHR_BASE=\"https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/api\"\n");
        depsSb.append("curl -sfL \"$GL_BASE/GLES2/gl2platform.h\" -o \"$PREFIX/include/GLES2/gl2platform.h\" 2>/dev/null\n");
        depsSb.append("curl -sfL \"$GL_BASE/GLES2/gl2.h\" -o \"$PREFIX/include/GLES2/gl2.h\" 2>/dev/null\n");
        depsSb.append("curl -sfL \"$GL_BASE/GLES2/gl2ext.h\" -o \"$PREFIX/include/GLES2/gl2ext.h\" 2>/dev/null\n");
        depsSb.append("curl -sfL \"$KHR_BASE/KHR/khrplatform.h\" -o \"$PREFIX/include/KHR/khrplatform.h\" 2>/dev/null\n");
        depsSb.append("echo \"  GLES2 headers installed\"\n");
        depsSb.append("\n");
        depsSb.append("# ── 6. Create as wrapper (clang integrated assembler) ──\n");
        depsSb.append("echo \"[6/6] Creating as wrapper...\"\n");
        depsSb.append("cat > $PREFIX/bin/as << 'ASWRAP'\n");
        depsSb.append("#!/data/data/com.sm64builder/files/usr/bin/bash\n");
        depsSb.append("# as wrapper - clang integrated assembler\n");
        depsSb.append("CLANG_FLAGS=()\n");
        depsSb.append("while [ $# -gt 0 ]; do\n");
        depsSb.append("  case \"$1\" in\n");
        depsSb.append("    --defsym)    shift; CLANG_FLAGS+=(\"-Wa,-defsym,$1\") ;;\n");
        depsSb.append("    --defsym=*)  CLANG_FLAGS+=(\"-Wa,-defsym,${1#*=}\") ;;\n");
        depsSb.append("    -MD)         CLANG_FLAGS+=(\"-MD\"); shift ;;\n");
        depsSb.append("    -o)          shift; CLANG_FLAGS+=(\"-o\" \"$1\") ;;\n");
        depsSb.append("    -I)          shift; CLANG_FLAGS+=(\"-I\" \"$1\") ;;\n");
        depsSb.append("    -I*)         CLANG_FLAGS+=(\"$1\") ;;\n");
        depsSb.append("    *.s|*.S)     CLANG_FLAGS+=(\"$1\") ;;\n");
        depsSb.append("    *)           ;;\n");
        depsSb.append("  esac\n");
        depsSb.append("  shift\n");
        depsSb.append("done\n");
        depsSb.append("exec clang -c -x assembler \"${CLANG_FLAGS[@]}\"\n");
        depsSb.append("ASWRAP\n");
        depsSb.append("chmod +x $PREFIX/bin/as\n");
        depsSb.append("# Create g++/gcc symlinks (some Makefiles use these)\n");
        depsSb.append("ln -sf $PREFIX/bin/clang++ $PREFIX/bin/g++ 2>/dev/null || true\n");
        depsSb.append("ln -sf $PREFIX/bin/clang $PREFIX/bin/gcc 2>/dev/null || true\n");
        depsSb.append("# Unset CLANG_RESOURCE_DIR (Termux sets it; let clang autodetect)\n");
        depsSb.append("unset CLANG_RESOURCE_DIR 2>/dev/null || true\n");
        depsSb.append("# Create zip wrapper (make uses 'zip -r' for APK packaging)\n");
        depsSb.append("cat > $PREFIX/bin/zip << 'ZIPEOF'\n");
        depsSb.append("#!/data/data/com.sm64builder/files/usr/bin/python3\n");
        depsSb.append("import zipfile, sys, os\n");
        depsSb.append("start = 2 if len(sys.argv) > 2 and sys.argv[1] == '-r' else 1\n");
        depsSb.append("out = sys.argv[start]\n");
        depsSb.append("with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:\n");
        depsSb.append("    for item in sys.argv[start+1:]:\n");
        depsSb.append("        if os.path.isfile(item):\n");
        depsSb.append("            z.write(item)\n");
        depsSb.append("        elif os.path.isdir(item):\n");
        depsSb.append("            for root, dirs, files in os.walk(item):\n");
        depsSb.append("                for f in files:\n");
        depsSb.append("                    z.write(os.path.join(root, f))\n");
        depsSb.append("ZIPEOF\n");
        depsSb.append("chmod +x $PREFIX/bin/zip\n");
        depsSb.append("\n");
        depsSb.append("# ── Done ──\n");
        depsSb.append("touch \"$SENTINEL\"\n");
        depsSb.append("echo \"=== Build dependencies ready ===\"\n");

        File depsFile = new File(binDir, "setup-build-deps.sh");
        try (FileOutputStream fos = new FileOutputStream(depsFile)) {
            fos.write(depsSb.toString().getBytes("UTF-8"));
            depsFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Created dep installer at " + depsFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create dep installer: " + e.getMessage());
        }

        // ── Patch bash.bashrc for interactive menu ──
        String bashrcPath = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/bash.bashrc";
        File bashrcFile = new File(bashrcPath);
        if (bashrcFile.isFile()) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(bashrcFile);
                byte[] data = new byte[(int) bashrcFile.length()];
                fis.read(data);
                fis.close();
                String content = new String(data, "UTF-8");
                if (!content.contains("sm64_menu.sh")) {
                    String menuLine = "\n# Launch SM64 builder menu on interactive terminals\n"
                        + "[ -t 0 ] && " + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sm64_menu.sh\n";
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(bashrcFile, true);
                    fos.write(menuLine.getBytes("UTF-8"));
                    fos.close();
                    Logger.logInfo(LOG_TAG, "Patched bash.bashrc for interactive menu");
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to patch bash.bashrc: " + e.getMessage());
            }
        }

        // ── Patch build scripts ──
        // Applied to all build-sm64ex-*.sh scripts found in $PREFIX/bin/
        String[] scriptPatterns = {"build-sm64ex-INT.sh", "build-sm64ex-EXT.sh",
            "build-sm64ex-coop.sh", "build-sm64ex-coop-render96.sh", "build-sm64ex-omm.sh",
            "build-sm64ex-alo.sh", "build-sm64ex-EXTnoTouch.sh", "build-sm64ex-INTnoTouch.sh",
            "build-sm64ex-porcino.sh", "build-starroad.sh"};

        for (String scriptName : scriptPatterns) {
            String scriptPath = binDir + "/" + scriptName;
            File scriptFile = new File(scriptPath);
            if (!scriptFile.isFile()) continue;

            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(scriptFile);
                byte[] data = new byte[(int) scriptFile.length()];
                fis.read(data);
                fis.close();
                String content = new String(data, "UTF-8");
                boolean changed = false;

                // Patch 1: storage check block (lines ~3-7)
                String oldStorage = "if ! ls /storage/emulated/0 >/dev/null 2>&1\nthen\n    yes | pkg install termux-am\n\tyes | termux-setup-storage\t\nfi";
                if (content.contains(oldStorage)) {
                    content = content.replace(oldStorage, "true");
                    changed = true;
                }

                // Patch 2: skip failing pkg install
                String oldPkgInstall = "yes | pkg install git wget mesa-dev make python getconf zip apksigner clang binutils libglvnd-dev aapt which netcat-openbsd";
                String newPkgInstall = "# deps installed by setup-build-deps.sh";
                if (content.contains(oldPkgInstall)) {
                    content = content.replace(oldPkgInstall, newPkgInstall);
                    changed = true;
                }

                // Patch 3: git clone -> wget + python3
                String oldGit = "git clone --recursive https://github.com/izzy2fancy/sm64-izzys-port-android.git";
                String newWget =
                    "curl -sfL \"https://github.com/izzy2fancy/sm64-izzys-port-android/archive/refs/heads/ex/nightly.zip\" -o sm64.zip 2>/dev/null && "
                    + "rm -rf sm64-izzys-port-android && "
                    + "python3 -c \"import zipfile,os; zipfile.ZipFile('sm64.zip').extractall('.'); "
                    + "os.rename('sm64-izzys-port-android-ex-nightly', 'sm64-izzys-port-android')\"";
                if (content.contains(oldGit)) {
                    content = content.replace(oldGit, newWget);
                    changed = true;
                }

                // Patch 4: after source download, patch both Makefiles
                String afterWget = "os.rename('sm64-izzys-port-android-ex-nightly', 'sm64-izzys-port-android')\"";
                String makefilePatch = "\n# Patch Makefiles\n"
                    + "sed -i 's|\\./extract_assets\\.py|python3 extract_assets.py|g' sm64-izzys-port-android/MakefileINT 2>/dev/null\n"
                    + "sed -i 's|\\./extract_assets\\.py|python3 extract_assets.py|g' sm64-izzys-port-android/Makefile 2>/dev/null\n";
                if (content.contains(afterWget) && !content.contains("sed -i 's|\\./extract_assets\\.py|python3")) {
                    int idx = content.indexOf(afterWget) + afterWget.length();
                    content = content.substring(0, idx) + makefilePatch + content.substring(idx);
                    changed = true;
                }

                // Patch 5: skip pkg upgrade (dpkg configure broken on termux-exec)
                String oldPkgUpgrade = "yes | pkg upgrade -y";
                if (content.contains(oldPkgUpgrade)) {
                    content = content.replace(oldPkgUpgrade, "# pkg upgrade skipped");
                    changed = true;
                }

                // Patch 7: copy APK to HOME instead of /storage/emulated/0 (Permission denied)
                String oldApkCopy = "cp build/us_pc/sm64.us.f3dex2e.apk /storage/emulated/0";
                String newApkCopy = "cp build/us_pc/sm64.us.f3dex2e.apk $HOME/";
                if (content.contains(oldApkCopy)) {
                    content = content.replace(oldApkCopy, newApkCopy);
                    changed = true;
                }

                // Patch 8: force fresh download (SDL headers predownload creates the directory)
                String oldIfDir = "if [ -d \"sm64-izzys-port-android\" ]";
                if (content.contains(oldIfDir)) {
                    content = content.replace(oldIfDir, "if false");
                    changed = true;
                }

                // Patch 9: git not available; skip git config (in else branch)
                String oldGitConfig = "git config core.fileMode false";
                if (content.contains(oldGitConfig)) {
                    content = content.replace(oldGitConfig, "# git config skipped (git not installed)");
                    changed = true;
                }

                // Patch 9: getSDL.sh has /bin/bash shebang (doesn't exist); SDL headers predownloaded
                String oldGetSDL = "./getSDL.sh";
                if (content.contains(oldGetSDL)) {
                    content = content.replace(oldGetSDL, "# getSDL.sh skipped (SDL headers predownloaded by setup)");
                    changed = true;
                }

                // Patch 10: copy SDL headers from TMPDIR into source tree (after rename)
                String oldCd = "cd sm64-izzys-port-android";
                String sdlCopyBeforeCd = "# Copy SDL headers from TMPDIR into source tree\n"
                    + "mkdir -p sm64-izzys-port-android/SDL/include/SDL2\n"
                    + "cp -a $TMPDIR/sdl_headers/* sm64-izzys-port-android/SDL/include/SDL2/ 2>/dev/null || true\n"
                    + "cd sm64-izzys-port-android";
                if (content.contains(oldCd)) {
                    content = content.replace(oldCd, sdlCopyBeforeCd);
                    changed = true;
                }

                // Patch 11: apply_patch.sh needs /bin/bash and 'patch'; skip
                String oldApplyPatch = "yes | tools/apply_patch.sh enhancements/60fps_ex.patch";
                if (content.contains(oldApplyPatch)) {
                    content = content.replace(oldApplyPatch, "# 60fps patch skipped (patch not installed)");
                    changed = true;
                }
                String oldApplyPatch2 = "yes | tools/apply_patch.sh enhancements/DynOS.1.0.patch";
                if (content.contains(oldApplyPatch2)) {
                    content = content.replace(oldApplyPatch2, "# DynOS patch skipped (patch not installed)");
                    changed = true;
                }

                // Patch 11: free space check fails when /storage/emulated/0 inaccessible
                String oldFreeSpace = "BLOCKS_FREE=$(awk -F ' ' '{print $4}' <(df | grep emulated))";
                if (content.contains(oldFreeSpace)) {
                    content = content.replace(oldFreeSpace, "BLOCKS_FREE=9999999");
                    changed = true;
                }

                if (changed) {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(scriptFile);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    Logger.logInfo(LOG_TAG, "Patched " + scriptName);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to patch " + scriptName + ": " + e.getMessage());
            }
        }

    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
