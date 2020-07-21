package com.adam.backupapps;

import android.app.AlertDialog;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.adam.backupapps.R;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import eu.chainfire.libsuperuser.Shell;

/**
 * <h1>BackupService</h1>
 * Service that runs a backup on installed user apps or a restore of
 * previously backed up apps
 *
 * @author Adam Davis
 */
public class BackupService extends IntentService {
    private static final String ACTION_BACKUP = "com.example.backupapps.action.BACKUP";
    private static final String ACTION_RESTORE = "com.example.backupapps.action.RESTORE";

    private static final String EXTRA_ZIPFILE = "com.example.backupapps.extra.ZIPFILE";

    private final int BUFFER = 1024 * 1024 * 10;

    private static ProgressDlg dialog;
    private static boolean isRunning = false;

    /* root privilege command vars */
    private Shell.Interactive rootShell;
    private Handler rootHandler;
    private HandlerThread rootHandlerThread;

    /* interactive shell vars*/
    private Shell.Interactive userShell;
    private Handler userHandler;
    private HandlerThread userHandlerThread;

    /* keep service alive vars */
    private int counter = 0;
    private Timer timer;
    private TimerTask timerTask;
    private final int NOTIFICATION_ID = 9999;
    private final String NOTIFICATION_CHANNEL_ID = "com.adam.backupapps.BackupService";
    private final String CHANNEL_NAME = "Backup Service";
    private final String NOTIFICATION_TITLE = "Backup Service is running in background";

    /* Name of temporary directory that stores the backup data */
    private final String TEMP_DIR = getFilesDir().getPath() + File.separator + "backup";

    /* File name of application to install in restore process */
    private final String BASE_APK = "base.apk";


    /**
     * List of selected applictions include in the backup
     */
    public static final HashMap<String, ApplicationInfo> selection = new HashMap<String, ApplicationInfo>();

    /**
     * XML enums for the config file that the
     * restore process uses
     */
    private class XML {
        public static final String CONFIG_FILE = "config.xml";
        public static final String START_ITEM = "<Item>";
        public static final String END_ITEM = "</Item>";
        public static final String START_DIR = "<Directory>";
        public static final String END_DIR = "</Directory>";
        public static final String START_DST = "<Destination>";
        public static final String END_DST = "</Destination>";
    }

    /**
     * Class that contains the config values
     * for the restore config file
     */
    private class ConfigFile {
        static final String ITEM = "Item";
        static final String DIRECTORY = "Directory";
        static final String DESTINATION = "Destination";
        public String directory;
        public String destination;

        public ConfigFile() {}
    }

    public BackupService() {
        super("BackupService");
        initalizeShell();
    }

    /* Keep service alive */
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O)
            startMyOwnForeground();
        else
            startForeground(NOTIFICATION_ID, new Notification());
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private void startMyOwnForeground() {
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                .setContentTitle(NOTIFICATION_TITLE)
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    /* Start keep service alive timer */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        startTimer();
        return START_STICKY;
    }

    /* Start keep service alive timer */
    private void startTimer() {
        timer = new Timer();
        timerTask = new TimerTask() {
            public void run() {
                Log.i("Count", "=========  "+ (counter++));
            }
        };
        timer.schedule(timerTask, 1000, 1000); //
    }

    /* Stop keep service alive timer */
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTimer();
    }

    /* Stop keep service alive timer */
    public void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /* Keep service alive binder */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Starts a backup of selected Apps
     * @param context application context
     * @param zipFile path of the backup zip file to save the backup to
     */
    public static void startActionBackup(Context context, String zipFile, ProgressDlg dlg) {
        dialog = dlg;
        Intent intent = new Intent(context, BackupService.class);
        intent.setAction(ACTION_BACKUP);
        intent.putExtra(EXTRA_ZIPFILE, zipFile);
        context.startService(intent);
    }

    /**
     * Starts a restore of previously backedup apps
     * @param context application context
     * @param zipFile path to the backup zip file
     */
    public static void startActionRestore(Context context, String zipFile, ProgressDlg dlg) {
        dialog = dlg;
        Intent intent = new Intent(context, BackupService.class);
        intent.setAction(ACTION_RESTORE);
        intent.putExtra(EXTRA_ZIPFILE, zipFile);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_BACKUP.equals(action)) {
                final String zipFile = intent.getStringExtra(EXTRA_ZIPFILE);
                handleActionBackup(zipFile);
            } else if (ACTION_RESTORE.equals(action)) {
                final String zipFile = intent.getStringExtra(EXTRA_ZIPFILE);
                handleActionRestore(zipFile);
            }
        }
    }

    /**
     * Handle the backup action
     * @param zipFile
     */
    private void handleActionBackup(String zipFile) {
        // if the service is not running then start the backup
        if(!isRunning) {
            isRunning = true;
            Collection<ApplicationInfo> values = selection.values();
            if(hasEnoughSpaceFilesDir(values)) {
                // create temporary directory in the application files directory for the backup
                File tempDir = new File(TEMP_DIR);

                if (tempDir.exists())
                    runUserCommand("rm -rf " + tempDir.getPath());

                tempDir.mkdir();

                dialog.setMessage(R.string.copying);
                dialog.setProgress(0);
                dialog.setMaxProgress(values.size());

                for (ApplicationInfo app : values) {
                    // create a directory for the backup files of the current appliction
                    File appBackupDir = new File(tempDir.getPath() + File.separator + app.packageName);
                    appBackupDir.mkdir();

                    // copy application dataDir and the apk to the temporary directory
                    runRootCommand(
                            "cp -rf " + app.dataDir + " " + appBackupDir.getPath() + " && " +
                                    "cp -rf " + app.sourceDir + " " + appBackupDir.getPath() + " && "
                    );

                    // make a config file for the restore process
                    try {
                        String xml =
                                XML.START_ITEM + "\n\t" +
                                        XML.START_DIR + app.dataDir.substring(app.dataDir.lastIndexOf("/") + 1) + XML.END_DIR + "\n\t" +
                                        XML.START_DST + app.dataDir.substring(0, app.dataDir.lastIndexOf("/")) + XML.END_DST + "\n" +
                                        XML.END_ITEM;

                        File config = new File(appBackupDir.getPath() + File.separator + XML.CONFIG_FILE);
                        config.createNewFile();
                        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(config.getPath()));
                        writer.write(xml);
                        writer.close();
                    }
                    // cleanup and exit on error
                    catch (Exception e) {
                        e.printStackTrace();
                        cleanup();
                        System.exit(0);
                    }

                    dialog.incrementProgressBy(1);
                }

                // set permissions for user rights on the copied data files
                runRootCommand("chmod 777 -R " + tempDir.getPath());


                if(hasEnoughSpaceStorageDir()) {
                    // zip the contents of the backup folder
                    try {
                        dialog.setMessage(R.string.backing_up);
                        dialog.setProgress(0);
                        dialog.setMaxProgress(getFileCount(tempDir));

                        File zip = new File(zipFile);
                        zip.createNewFile();

                        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
                        byte[] buffer = new byte[BUFFER];
                        zipFile(tempDir, out, tempDir.getParent().length(), buffer);


                       cleanup();
                    }
                    // clean up and exit on error
                    catch (Exception e) {
                        e.printStackTrace();
                        cleanup();
                        runUserCommand("rm -rf " + zipFile);
                        System.exit(0);
                    }
                }
                else {
                    cleanup();
                    alertNotEnoughSpace();
                }

            }
            else {
                alertNotEnoughSpace();
            }

            dialog.dismiss();
            dialog = null;

            // stop service after backup is complete
            stopForeground(true);
            stopSelf();

            isRunning = false;
        }

    }

    /**
     * Handle the restore action
     * @param zipFile
     */
    private void handleActionRestore(String zipFile) {
        if(!isRunning) {
            isRunning = true;
            try {
                if (hasEnoughSpaceFilesDir(zipFile)) {
                    dialog.setProgress(0);
                    dialog.setMessage(R.string.unzipping);

                    // unzip backup
                    runUserCommand("cd " + getFilesDir().getPath());
                    runUserCommand("bin/unzip " + zipFile);

                    if (hasEnoughSpaceStorageDir()) {
                        File tempDir = new File(TEMP_DIR);
                        dialog.setMessage(R.string.restoring);
                        dialog.setProgress(0);
                        dialog.setMaxProgress(tempDir.listFiles().length);

                        for (File f : tempDir.listFiles()) {
                            ConfigFile config = parseConfig(f.getPath() + File.separator + XML.CONFIG_FILE);
                            runRootCommand("pm install " + f.getPath() + File.separator + BASE_APK);
                            runRootCommand("cp -rf " + f.getPath() + File.separator + config.directory + " " + config.destination);
                            runRootCommand("chmod 777 -R " + config.destination + File.separator + config.directory);
                        }

                        cleanup();
                    } else {
                        alertNotEnoughSpace();
                        cleanup();
                    }

                }
                else {
                    alertNotEnoughSpace();
                }
            }
            catch(Exception e) {
                e.printStackTrace();
                cleanup();
                System.exit(0);
            }

            isRunning = false;
        }
    }


    /**
     * Execute a command as root
     * @param cmd
     * @return list of output lines
     */
    private ArrayList<String> runRootCommand(String cmd) {
        final ArrayList<String> result = new ArrayList<>();

        // callback being called on a background handler thread
        rootShell.addCommand(cmd, 0, new Shell.OnCommandResultListener() {
            @Override
            public void onCommandResult(int commandCode, int exitCode, List<String> output) {

                for (String line : output) {
                    result.add(line);
                }
            }
        });
        rootShell.waitForIdle();
        return result;

    }

    private ArrayList<String> runUserCommand(String cmd) {
        final ArrayList<String> result = new ArrayList<>();

        // callback being called on a background handler thread
        userShell.addCommand(cmd, 0, new Shell.OnCommandResultListener() {
            @Override
            public void onCommandResult(int commandCode, int exitCode, List<String> output) {

                for (String line : output) {
                    result.add(line);
                }
            }
        });
        userShell.waitForIdle();
        return result;

    }

    private void initalizeShell() {
        rootHandlerThread = new HandlerThread("rootHandler");
        rootHandlerThread.start();
        rootHandler = new Handler(rootHandlerThread.getLooper());
        rootShell = (new Shell.Builder()).useSU().setHandler(rootHandler).open();

        userHandlerThread = new HandlerThread("userHandler");
        userHandlerThread.start();
        userHandler = new Handler(userHandlerThread.getLooper());
        userShell = (new Shell.Builder()).useSU().setHandler(userHandler).open();
    }

    /**
     * Get the number of files recursivley in a folder
     * @param file start directory
     * @return int
     */
    private int getFileCount(File file) throws IOException {
        // don't follow symbolic links
        Path path = Paths.get(file.getPath());
        if(Files.isSymbolicLink(path) || !file.isDirectory()) {
            return 1;
        }
        else {
            int count = 0;
            for(File f : file.listFiles())
                count += getFileCount(f);

            return count;
        }
    }

    /**
     * Recurrsively zip a folder
     * @param file
     * @param out
     * @param basePathLength
     * @param buffer
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void zipFile(File file, ZipOutputStream out, int basePathLength, byte[] buffer) throws FileNotFoundException, IOException {
        // don't follow symbolic links
        Path path = Paths.get(file.getPath());
        if(Files.isSymbolicLink(path) || !file.isDirectory()) {
            String relPath = file.getPath().substring(basePathLength);

            FileInputStream fi = new FileInputStream(file.getPath());
            BufferedInputStream origin = new BufferedInputStream(fi, BUFFER);
            ZipEntry entry = new ZipEntry(relPath);
            out.putNextEntry(entry);

            int count;
            while((count = origin.read(buffer, 0, BUFFER)) != -1)
                out.write(buffer, 0, count);

            origin.close();

            dialog.incrementProgressBy(1);
        }
        else {
            for(File f : file.listFiles())
                zipFile(f, out, basePathLength, buffer);
        }
    }

    /**
     * Execute df to check the available disk of a mount point
     * @param path
     */
    private int checkAvailableSpace(String path) {
        ArrayList<String> result = runRootCommand("df -k " + path);
        String[] split = result.get(1).split("\\s+");

        // available space is the third index of the command out put
        return Integer.parseInt(split[3]);
    }

    /**
     * Execute du to check the amount of disk space that a file
     * or folder occupies
     * @param path
     * @return
     */
    private int getDiskUsage(String path) {
        ArrayList<String> result = runRootCommand("du -ks " + path);
        String[] split = result.get(0).split("\\s+");

        // disk space is the first index of the command output
        return Integer.parseInt(split[0]);
    }

    /**
     * Check if the application data mount point has enough space
     * to copy the apps respective data dirs to this application's
     * data dir
     * @param apps
     * @return
     */
    private boolean hasEnoughSpaceFilesDir(Collection<ApplicationInfo> apps) {
        int availableSpace = checkAvailableSpace(getFilesDir().getPath());
        int diskUsage = 0;
        for(ApplicationInfo app : apps) {
            diskUsage += getDiskUsage(app.sourceDir) + getDiskUsage(app.dataDir);
        }

        availableSpace -= diskUsage;
        return availableSpace >= 0;
    }

    /**
     * Check if there is enough disk space to extrace
     * the backup file
     * @param zipFile
     * @return
     */
    private boolean hasEnoughSpaceFilesDir(String zipFile) {
        int availableSpace = checkAvailableSpace(getFilesDir().getPath());
        int diskUsage = getDiskUsage(zipFile);

        availableSpace -= diskUsage;
        return availableSpace >= 0;
    }

    /**
     * Check if there is enough space for the
     * backup file
     * @return
     */
    private boolean hasEnoughSpaceStorageDir() {
        int availableSpace = checkAvailableSpace(Environment.getExternalStorageDirectory().getPath());
        int diskUsage = getDiskUsage(getFilesDir().getPath() + "/backup");

        availableSpace -= diskUsage;
        return availableSpace >= 0;
    }

    /**
     * Check if a backup or a restore is currently running
     * @return
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Parse the restore config file
     * @param configFile
     */
    private ConfigFile parseConfig(String configFile) throws ParserConfigurationException, FileNotFoundException, IOException, SAXException {
        FileInputStream fis = new FileInputStream(configFile);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(fis);

        Element element = doc.getDocumentElement();
        element.normalize();

        NodeList nList = doc.getElementsByTagName(ConfigFile.ITEM);
        Element item = (Element)nList.item(0);
        Node directory = item.getElementsByTagName(ConfigFile.DIRECTORY).item(0).getChildNodes().item(0);
        Node destination = item.getElementsByTagName(ConfigFile.DESTINATION).item(0).getChildNodes().item(0);

        ConfigFile config = new ConfigFile();
        config.directory = directory.getNodeValue();
        config.destination = destination.getNodeValue();

        return config;
    }

    private void alertNotEnoughSpace() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.alert);
        builder.setMessage(R.string.not_enough_space);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        alert.show();
    }

    /**
     * Cleanup tempory directory
     */
    private void cleanup() {
        runUserCommand("rm -rf " + TEMP_DIR);
    }
}
