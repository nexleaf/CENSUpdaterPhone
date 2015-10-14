package edu.ucla.cens.Updater;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import edu.ucla.cens.Updater.model.AppInfoModel;
import edu.ucla.cens.Updater.model.StatusModel;
import edu.ucla.cens.Updater.utils.AppInfoCache;
import edu.ucla.cens.Updater.utils.AppManager;
import edu.ucla.cens.Updater.utils.OnInstalledPackage;
import edu.ucla.cens.Updater.utils.PMCLI;
import edu.ucla.cens.systemlog.Log;


/**
 * Created by krngrvr09 on 10/6/15.
 */
public class Installer {
    private static final String TAG = "CENS.Updater.Installer";

    private static final int FINISHED_INSTALLING_PACKAGE = 1;
    private static final int FINISHED_UNINSTALLING_PACKAGE = 2;

    private static final int MESSAGE_FINISHED_DOWNLOADING = 1;
    private static final int MESSAGE_FINISHED_INSTALLING = 2;
    private static final int MESSAGE_UPDATE_INSTALLER_TEXT = 3;
    private static final int MESSAGE_UPDATE_DOWNLOADER_TEXT = 4;
    private static final int MESSAGE_UPDATE_PROGRESS_BAR = 5;
    private static final int MESSAGE_FINISHED_INITIAL_CLEANUP = 6;
    private static final int MESSAGE_FINISHED_UNINSTALLING = 7;
    private static final int MESSAGE_UPDATE_UI = 2;

    private static final int MAX_CHUNK_LENGTH = 4096;

    private static final int PROGRESS_BAR_MAX = 100;


    private Context mContext;
    private PackageDownloader downloaderThread;
    private PackageInstaller installerThread;
    //private PackageUninstaller uninstallerThread;

    private PackageInformation[] packagesToBeUpdated;
    private int currPackageIndex;

    private boolean currPackageError;
    private boolean activityKilled;

    private String newInstallerText;
    private String newDownloaderText;
    private int newProgressBarValue;

    private Handler messageHandler;


    private StatusModel model = StatusModel.get();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder mBuilder;

    public Installer(Context mContext){

        this.mContext = mContext;

        // Initializing Notification Manager and Notification Builder
        this.notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        this.mBuilder = new NotificationCompat.Builder(mContext);

        Log.initialize(mContext, Database.LOGGER_APP_NAME);

        if(Settings.Secure.getInt(mContext.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 0)
        {

            Toast.makeText(mContext, "Please enable the installation of non-market apps before continuing. Thank you.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_SETTINGS);
            mContext.startActivity(intent);

//            finish();
            return;
        }

        Database db = new Database(mContext);
        packagesToBeUpdated = db.getUpdates();

        if(packagesToBeUpdated == null)
        {
            Log.e(TAG, "List of packages to be updated is null.");
//            installerText.setText(TEXT_DATABASE_ERROR);
//            finish();
            return;
        }
        else if(packagesToBeUpdated.length <= 0)
        {
            Log.i(TAG, "List of packages has no updates in it.");
//            installerText.setText(TEXT_NO_PACKAGES);
//            finish();
            return;
        }
        else
        {
            activityKilled = false;
            currPackageIndex = 0;
            processPackage();
        }
        messageHandler = new Handler()
        {

            @Override
            public void handleMessage(Message msg)
            {
                //Log.d(TAG, "handleMessage: " + msg);
                if(msg.what == MESSAGE_FINISHED_DOWNLOADING)
                {
//                progressBar.setProgress(0);
                    installerThread = new PackageInstaller();
                    Thread installer = new Thread(installerThread);
                    installer.setName("Installer");
                    installer.start();
                }
                else if(msg.what == MESSAGE_FINISHED_UNINSTALLING)
                {
//                progressBar.setProgress(0);
                    installerThread = new PackageInstaller();
                    Thread installer = new Thread(installerThread);
                    installer.setName("Installer");
                    installer.start();
                }
                else if(msg.what == MESSAGE_FINISHED_INSTALLING)
                {
                    nextPackage();
                }
                else if(msg.what == MESSAGE_UPDATE_INSTALLER_TEXT)
                {
//                installerText.setText(newInstallerText);
                }
                else if(msg.what == MESSAGE_UPDATE_DOWNLOADER_TEXT)
                {
//                downloaderText.setText(newDownloaderText);
                }
                else if(msg.what == MESSAGE_UPDATE_PROGRESS_BAR)
                {
//                progressBar.setProgress(newProgressBarValue);
                }
                else if(msg.what == MESSAGE_FINISHED_INITIAL_CLEANUP)
                {
                    processPackage();
                }
            }
        };

    }

    /**
     * Private class that downloads the current file and sends a message back
     * to the UI thread when complete.
     *
     * @author John Jenkins
     */
    private class PackageDownloader implements Runnable
    {
        /**
         * Downloads the current package and stores it in the shared local
         * directory as world readable.
         */
        @Override
        public void run()
        {
//            String[] urls = packagesToBeUpdated[currPackageIndex].getUrl().split(",");
//            for(String app_url: urls) {
                // Launching a notification for each app that is installed.
                mBuilder.setContentTitle(packagesToBeUpdated[currPackageIndex].getQualifiedName())
                        .setContentText("Download in progress")
                        .setSmallIcon(R.drawable.u)
                        .setTicker(packagesToBeUpdated[currPackageIndex].getQualifiedName());
                mBuilder.setProgress(100, 0, false);
                notificationManager.notify(1, mBuilder.build());
                String packageQualifiedName = packagesToBeUpdated[currPackageIndex].getQualifiedName();

                // These are placed throughout the code as a way to signal that the
                // process should stop, but without leaving the JVM or anything
                // else in a half-open state.
                if (activityKilled) return;

                // Get the URL for the current package.
                URL url;
                try {
                    url = new URL(packagesToBeUpdated[currPackageIndex].getUrl());
                } catch (MalformedURLException e) {
                    error("Malformed URL in package " + packageQualifiedName, e);
                    return;
                }

                if (activityKilled) return;

                // Open the connection to the current package and get its length.
                HttpURLConnection connection;
                int totalLength, alreadyDownloaded;
                String lastModified, responseCode;

                // Shared preferences used to store size of the apk downloaded
                // and its last modified date.
                SharedPreferences sharedPreferences = mContext.getSharedPreferences(Database.PACKAGE_PREFERENCES, Context.MODE_PRIVATE);
                try {
                    alreadyDownloaded = sharedPreferences.getInt(packageQualifiedName + " alreadyDownloaded", 0);
                    lastModified = sharedPreferences.getString(packageQualifiedName + " lastmodified", "");
                    connection = (HttpURLConnection) url.openConnection();

                    // If this apk is partially downloaded, then ask for the rest of the apk.
                    if (alreadyDownloaded > 0) {
                        connection.setRequestProperty("Range", "bytes=" + alreadyDownloaded + "-");
                        connection.setRequestProperty("If-Range", lastModified);
                        connection.connect();
                    }
                    // If this is a new download, then store the last modified for future use.
                    else {
                        connection.connect();
                        lastModified = connection.getHeaderField("Last-Modified");
                        alreadyDownloaded = 0;
                    }
                    // Total length of the file is length of the file being
                    // downloaded + length which is already downloaded.
                    totalLength = connection.getContentLength() + alreadyDownloaded;
                    responseCode = String.valueOf(connection.getResponseCode());

                    if (totalLength <= 0) {
                        error("The total length of the file is invalid: " + totalLength, new IllegalStateException("The file no longer exists or has an invalid size."));
                        Updater updater = new Updater(mContext);
                        updater.doUpdate();
                        return;
                    }
                } catch (IOException e) {
                    error("Failed to connect to the remote file.", e);
                    return;
                }

                if (activityKilled) return;

                // Get the input stream to begin reading the content.
                InputStream dataStream;
                try {
                    dataStream = connection.getInputStream();
                } catch (IOException e) {
                    error("Failed to open an input stream from the url: " + url, e);
                    return;
                }

                if (activityKilled) return;

                // Create a connection to the local file that will store the APK.
                // The package is made world readable, so that Android's package
                // installer can read it.
                FileOutputStream apkFile;
                try {
                    // If partial content, then append the file. Else, write as usual.
                    if (responseCode.equals("206")) {
                        apkFile = mContext.openFileOutput(packageQualifiedName + ".apk", mContext.MODE_WORLD_READABLE | mContext.MODE_APPEND);
                    } else {
                        apkFile = mContext.openFileOutput(packageQualifiedName + ".apk", mContext.MODE_WORLD_READABLE);

                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    error("The array index, " + currPackageIndex + ", was out of bounds for the packages to be updated array which length: " + packagesToBeUpdated.length, e);
                    return;
                } catch (IllegalArgumentException e) {
                    error("The package filename was invalid.", e);
                    return;
                } catch (IOException e) {
                    error("Could not create temporary file.", e);
                    return;
                }

                if (activityKilled) return;
                int totalDownloaded = 0;
                try {
                    int currDownloaded = 0;
                    totalDownloaded = alreadyDownloaded;

                    // Download the file chunk by chunk each time updating the
                    // interface with our progress.
                    byte[] buff = new byte[MAX_CHUNK_LENGTH];
                    while ((currDownloaded = dataStream.read(buff)) != -1) {
                        try {
                            apkFile.write(buff, 0, currDownloaded);

                            totalDownloaded += currDownloaded;

                            // updateProgressBarValue(totalDownloaded, totalLength);
                            mBuilder.setProgress(totalLength, totalDownloaded, false);
                            // Displays the progress bar for the first time.
                            notificationManager.notify(1, mBuilder.build());

                            // This was originally being done to debug the code but
                            // is being left in as a flag that something odd has
                            // happened.
                            if (totalLength - totalDownloaded < 0) {
                                Log.e(TAG, "Downloaded more than the total size of the file.");
                            }
                        } catch (IOException e) {
                            error("Error while writing to the file output stream.", e);
                            return;
                        }
                    }
                } catch (IOException e) {
                    // If IO Exception, store current downloaded and last modified
                    // in shared preferences.
                    alreadyDownloaded = totalDownloaded;
                    sharedPreferences.edit().putInt(packageQualifiedName + " alreadyDownloaded", alreadyDownloaded).commit();
                    sharedPreferences.edit().putString(packageQualifiedName + " lastmodified", lastModified).commit();

                    error("Error while reading from the url input stream.", e);
                    return;
                }

                if (activityKilled) return;

                // Reset the values in shared preferences if the download is successful.
                sharedPreferences.edit().putInt(packageQualifiedName + " alreadyDownloaded", 0).commit();
                sharedPreferences.edit().putString(packageQualifiedName + " lastmodified", "").commit();
//            }
            messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_DOWNLOADING));
        }

        /**
         * Called whenever an error takes place to log it, update the UI, set
         * the appropriate shared variables, and send a message back that it
         * was finished.
         *
         * @param error A String representing the error that occurred.
         *
         * @param e The Exception thrown by the system.
         */
        private void error(String error, Exception e)
        {
            Log.e(TAG, error, e);
//            updateInstallerText("Error while downloading " + packagesToBeUpdated[currPackageIndex].getDisplayName());
            currPackageError = true;
            messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_DOWNLOADING));
        }

    }

    /**
     * Checks to make sure no error had previously occurred and, if not,
     * starts the Android installer with the package we just downloaded.
     *
     * @author John Jenkins
     * @version 1.0
     */
    private class PackageInstaller implements Runnable
    {
        /**
         * Checks to make sure no error had occurred and then starts the
         * Android installer with the information just given.
         */
        @Override
        public void run()
        {

            //Log.d(TAG, "PackageInstaller.run.1");

            // If the downloader failed, but we still arrived here, just fall
            // out with an error message.
            if(currPackageError)
            {
                Log.e(TAG, "Aborting installer for " + packagesToBeUpdated[currPackageIndex].getQualifiedName());
                messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_INSTALLING));
                return;
            }

            if(activityKilled) return;

            updateInstallerText("Installing " + packagesToBeUpdated[currPackageIndex].getDisplayName());

            if(activityKilled) return;

            String apkpath = mContext.getFilesDir().getAbsolutePath() + "/" + packagesToBeUpdated[currPackageIndex].getQualifiedName() + ".apk";
            String packageName = packagesToBeUpdated[currPackageIndex].getQualifiedName();
            File apkFile = new File(apkpath);
            //Log.d(TAG, "PackageInstaller.run.2: " + apkFile);
            if(apkFile.exists())
            {
                if(activityKilled) return;

                if(packagesToBeUpdated[currPackageIndex].getQualifiedName().equals("edu.ucla.cens.Updater"))
                {
                    SharedPreferences sharedPreferences = mContext.getSharedPreferences(Database.PACKAGE_PREFERENCES, Context.MODE_PRIVATE);
                    sharedPreferences.edit().putBoolean(Database.PREFERENCES_SELF_UPDATE, true).commit();
                }


				/*
				Intent installIntent = new Intent(android.content.Intent.ACTION_VIEW);
				installIntent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");


				startActivityForResult(installIntent, FINISHED_INSTALLING_PACKAGE);
				*/
                PackageInformation.Action action = packagesToBeUpdated[currPackageIndex].getAction();
                PMCLI am;
                Log.d(TAG, "am starting istall: " + apkpath);
                try {
                    am = new PMCLI(mContext);
                    am.setOnInstalledPackaged(new OnInstalledPackage() {

                        public void packageInstalled(String packageName, int returnCode, String message) {
                            String msg;
                            if (returnCode == PMCLI.INSTALL_SUCCEEDED) {

//                                PackageInformation inPackage = packagesToBeUpdated[currPackageIndex];
//                                AppInfoModel appInfo = new AppInfoModel(inPackage.getQualifiedName(),inPackage.getReleaseName(),inPackage.getDisplayName(),inPackage.getVersion(),inPackage.getUrl(),inPackage.getAction());
//                                AppInfoCache.get().add(appInfo);
//                                Log.d("appinfo1", String.valueOf(AppInfoCache.get().get(appInfo.getQualifiedName())));
                                msg = "Install succeeded for " + packageName + ": " + message;
                                Log.d(TAG, msg);
                                //updateInstallerText("Installed " + packagesToBeUpdated[currPackageIndex].getDisplayName());
                                updateInstallerText("Installed " + packageName);
                                model.addInfoMessage(msg);
                            } else {
                                msg = "Install failed for " + packageName + ": rc=" + returnCode + ": " + message;
                                Log.e(TAG, msg);
                                //updateInstallerText("Installed " + packagesToBeUpdated[currPackageIndex].getDisplayName());
                                updateInstallerText("Failed to install " + packageName);
                                model.addErrorMessage(msg);
                            }
                            // do async toast to run on ui thread
                            AppManager.get().doToastMessageAsync(msg);
                            messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_INSTALLING));
                        }

                        public void packageUninstalled(String packageName, int returnCode, String message) {
                            String msg;
                            if (returnCode == PMCLI.UNINSTALL_SUCCEEDED) {
                                msg = "Uninstall succeeded for " + packageName + ": " + message;
                                Log.d(TAG, msg);
                                //updateInstallerText("Installed " + packagesToBeUpdated[currPackageIndex].getDisplayName());
                                updateInstallerText("Uninstalled " + packageName);
                                model.addInfoMessage(msg);
                                messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_UNINSTALLING));
                            } else {
                                msg = "Uninstall failed for " + packageName + ": rc=" + returnCode + ": " + message;
                                Log.e(TAG, msg);
                                //updateInstallerText("Installed " + packagesToBeUpdated[currPackageIndex].getDisplayName());
                                updateInstallerText("Failed to uninstall " + packageName);
                                model.addErrorMessage(msg);
                            }
                            // do async toast to run on ui thread
                            AppManager.get().doToastMessageAsync(msg);
                            messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_INSTALLING));
                        }

                    });
                    if (action == PackageInformation.Action.UPDATE) {
                        am.installPackageViaShell(apkpath);
                    } else if (action == PackageInformation.Action.CLEAN) {
                        // if package not installed any more, install it now
                        try {
                            // Get the package's information. If this doesn't throw an
                            // exception, then the package must be installed.
                            PackageManager packageManager = mContext.getPackageManager();
                            packageManager.getPackageInfo(packageName, 0);
                            // no exception: let's uninstall it
                            am.uninstallPackageViaShell(packageName);
                        } catch(PackageManager.NameNotFoundException e) {
                            // The package isn't yet installed.
                            am.installPackageViaShell(apkpath);
                        }
                    } else {
                        throw new RuntimeException("Only Actions UPDATE and CLEAN uspported");
                    }
                } catch (SecurityException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } finally {
                }

            }
            else
            {
                Log.e(TAG, "File does not exist.");
                messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_FINISHED_INSTALLING));
            }
        }

        /**
         * Updates the shared variable for what String should be displayed in
         * the status title and sends a message back to the UI thread to
         * refresh the text.
         *
         * @param text The text to be shown in the status title.
         */
        private void updateInstallerText(String text)
        {
            newInstallerText = text;
            messageHandler.sendMessage(messageHandler.obtainMessage(MESSAGE_UPDATE_INSTALLER_TEXT));
        }
    }



    /**
     * Handles messages sent by the local Threads such as updating the text
     * and signaling completion of an activity.
     */


    /**
     * Increases our index and begins to process the next package.
     */
    private void nextPackage()
    {
        // Update the UI in the AppList, to show how many packages
        // have been installed.
//        AppInfoCache.get().save(mContext);
        AppInfoCache.get().refresh();
        AppManager.get().nototifyMainActivity();
        AppInfoCache.get().save(mContext);

        currPackageIndex++;
        processPackage();
    }

    /**
     * If we have processed all packages, it will delete the temporary APK
     * being used to download the packages then quit. If not, it will reset
     * the local variables and start the downloader thread for the next
     * package.
     */
    private void processPackage()
    {
        // If we have processed all packages, leave.
        if(currPackageIndex >= packagesToBeUpdated.length)
        {
            Log.i(TAG, "Done updating all packages.");
            // refresh app info cache
//            Handler mHandler = new Handler();
//            mHandler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
                    AppInfoCache.get().refresh();
                    AppManager.get().nototifyMainActivity();
//                }
//            }, 2000);


//              finish();
            return;
        }

        // We need to check if the update should actually be applied.
        if(packagesToBeUpdated[currPackageIndex].getToBeApplied())
        {
            currPackageError = false;

            // Check to see if the package was already installed for purposes
            // of reporting in the broadcast.
            PackageManager packageManager = mContext.getPackageManager();
            try
            {
                // Get the package's information. If this doesn't throw an
                // exception, then the package must be installed.
                packageManager.getPackageInfo(packagesToBeUpdated[currPackageIndex].getQualifiedName(), 0);

                // If the package is to be updated,
                if(packagesToBeUpdated[currPackageIndex].getAction().equals(PackageInformation.Action.UPDATE))
                {
                    // Spawn a new downloader thread and start it.
                    downloaderThread = new PackageDownloader();
                    Thread downloader = new Thread(downloaderThread);
                    downloader.setName("Downloader");
                    downloader.start();
                }
                // Otherwise, the package must be installed but it isn't an
                // update, so we need to first remove the original package.
                else
                {
                    // we do the same thing for action.CLEAN for now.
                    // that will trigger installer after download which will do the right thing:
                    //   it will first run uninstall, send an MESSAGE_FINISHED_UNINSTALLING message to handler
                    //   which will then trigger install
                    downloaderThread = new PackageDownloader();
                    Thread downloader = new Thread(downloaderThread);
                    downloader.setName("Downloader");
                    downloader.start();
					/*
					 * original code called system intent to uninstall with user interaction
					//Uri packageUri = Uri.parse("package:" + packagesToBeUpdated[currPackageIndex].getQualifiedName());
					//Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageUri);
					//startActivityForResult(uninstallIntent, FINISHED_UNINSTALLING_PACKAGE);
					*/
                }
            }
            // The package isn't yet installed.
            catch(PackageManager.NameNotFoundException e)
            {
                // Spawn a new downloader thread and start it.
                downloaderThread = new PackageDownloader();
                Thread downloader = new Thread(downloaderThread);
                downloader.setName("Downloader");
                downloader.start();
            }
        }
        else
        {
            nextPackage();
        }
    }
}
