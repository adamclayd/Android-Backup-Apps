package com.adam.backupapps;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.codekidlabs.storagechooser.StorageChooser;
import com.adam.backupapps.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.chainfire.libsuperuser.Shell;

/**
 * <h1>Backup Apps</h1>
 *
 * App that backs up your installed applications or
 * restores a previously saved backup from another system
 *
 * Requires a rooted device
 *
 * @author Adam Davis
 */
public class MainActivity extends AppCompatActivity {
    private ArrayList<CheckBox> checkBoxes = new ArrayList<CheckBox>();

    private static final int ICON_HEIGHT = 64;
    private static final int ICON_WIDtH = 64;


    /* check permission vars */
    private final static int REQUEST_CODE_ASK_PERMISSIONS = 1;
    private static final String[] REQUIRED_SDK_PERMISSIONS = new String[] {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK
    };

    /**
     * Checks and requests permissions from the system
     */
    protected void checkPermissions() {
        final List<String> missingPermissions = new ArrayList<String>();
        // check all required dynamic permissions
        for (final String permission : REQUIRED_SDK_PERMISSIONS) {
            final int result = ContextCompat.checkSelfPermission(this, permission);
            if (result != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (!missingPermissions.isEmpty()) {
            // request all missing permissions
            final String[] permissions = missingPermissions
                    .toArray(new String[missingPermissions.size()]);
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_ASK_PERMISSIONS);
        } else {
            final int[] grantResults = new int[REQUIRED_SDK_PERMISSIONS.length];
            Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED);
            onRequestPermissionsResult(REQUEST_CODE_ASK_PERMISSIONS, REQUIRED_SDK_PERMISSIONS,
                    grantResults);
        }
    }

    /* exit if any permission is not granted */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_PERMISSIONS:
                for (int index = permissions.length - 1; index >= 0; --index) {
                    if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                        finish();
                        return;
                    }
                }
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupAssets();
        checkPermissions();
        initalizeShell();
        setupSelectAll();
        setupApps();
        setupBackupBtn();
    }

    /* install zip and unzip binaries */
    private void setupAssets() {
        File bin = new File(getFilesDir().getPath() + File.separator + "bin");
        File zip  = new File(getFilesDir().getPath() + File.separator + "bin" + File.separator + "zip");
        File unzip = new File(getFilesDir() + File.separator + "bin" + File.separator + "unzip");

        final int BUFFER = 4 * 1024;

        if (!bin.exists())
            bin.mkdir();

        // install zip executable
        if(!zip.exists()) {
            try {
                zip.createNewFile();
                InputStream in = getAssets().open("zip");
                OutputStream out = new FileOutputStream(zip);
                byte[] buffer = new byte[BUFFER];
                int read;

                while((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }

                out.flush();
                in.close();

                Shell.SH.run("chmod 744 " + zip.getPath());
            }
            catch(Exception e) {
                e.printStackTrace();
                finish();
            }
        }

        if(!unzip.exists()) {
            try {
                unzip.createNewFile();
                InputStream in = getAssets().open("unzip");
                OutputStream out = new FileOutputStream(unzip);
                byte[] buffer = new byte[BUFFER];
                int read;

                while((read = in.read(buffer)) != -1)
                    out.write(buffer, 0, read);

                out.flush();
                in.close();

                Shell.SH.run("chmod 744 " + unzip.getPath());
            }
            catch(Exception e) {
                e.printStackTrace();
                finish();
            }
        }
    }

    /**
     * Setup the select all checkbox
     */
    private void setupSelectAll() {
        final CheckBox selectAll = findViewById(R.id.selectAllCbx);

        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(selectAll.isChecked()) {
                    for(CheckBox cb : checkBoxes) {
                        cb.setChecked(true);
                    }
                }
                else {
                    for(CheckBox cb : checkBoxes) {
                        cb.setChecked(false);
                    }
                }
            }
        });
    }

    /**
     * Setup the checkboxes that represent apps in the list
     * of installed apps
     */
    private void setupApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        LinearLayout linearLayout = findViewById(R.id.linearLayout);
        final CheckBox selectAll = findViewById(R.id.selectAllCbx);

        for(final ApplicationInfo app : packages) {
            int mask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if(((app.flags & mask) == 0) && !app.packageName.equals(getApplicationInfo().packageName)) {
                CheckBox cb = new CheckBox(this);
                cb.setText(app.packageName);
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton btn, boolean isChecked) {
                        if(isChecked) {
                            if(isAllSelected())
                                selectAll.setChecked(true);

                            BackupService.selection.put(app.packageName, app);
                        }
                        else {
                            selectAll.setChecked(false);
                            BackupService.selection.remove(app.packageName);
                        }
                    }
                });

                checkBoxes.add(cb);

                ImageView image = new ImageView(this);
                image.setAdjustViewBounds(true);
                image.setMaxWidth(ICON_WIDtH);
                image.setMaxHeight(ICON_HEIGHT);

                try {
                    Drawable icon = pm.getApplicationIcon(app.packageName);
                    image.setImageDrawable(icon);
                }
                catch(Exception e) {
                    e.printStackTrace();
                }

                LinearLayout ll = new LinearLayout(this);
                ll.setOrientation(LinearLayout.HORIZONTAL);

                ll.addView(image);
                ll.addView(cb);

                linearLayout.addView(ll);

            }
        }
    }

    /**
     * Checks if all apps in the list are checked
     * @return <b>true</b> if all appin the list are selected <b>false</b> if not
     */
    private boolean isAllSelected() {
        boolean ret = true;
        for(CheckBox cb : checkBoxes) {
            if(!cb.isChecked())
                ret = false;
        }

        return ret;
    }

    /**
     * Setup the Backup button on click
     */
    private void setupBackupBtn() {
        Button backupBtn = findViewById(R.id.backupBtn);

        backupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // display alert dialog if no apps are select
                if(BackupService.selection.isEmpty()) {
                    alert(R.string.alert, R.string.no_selection);
                }
                // make custom alert dialog with a file selection view
                else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    LinearLayout ll = new LinearLayout(MainActivity.this);
                    ll.setOrientation(LinearLayout.HORIZONTAL);

                    // the textbox that is supposed to contain the path of the file to save
                    final EditText path = new EditText(MainActivity.this);
                    path.setHint(R.string.save_as);
                    path.setWidth(700);

                    // button that displays folder selector
                    Button browseBtn = new Button(MainActivity.this);
                    browseBtn.setText(R.string.browse);
                    browseBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            StorageChooser chooser = new StorageChooser.Builder()
                                    .withActivity(MainActivity.this)
                                    .withFragmentManager(getFragmentManager())
                                    .withMemoryBar(true)
                                    .allowCustomPath(true)
                                    .setType(StorageChooser.DIRECTORY_CHOOSER)
                                    .build();

                            chooser.setOnSelectListener(new StorageChooser.OnSelectListener() {
                                @Override
                                public void onSelect(String p) {
                                    path.setText(p + File.separator + "backup.zip");
                                }
                            });

                            chooser.show();
                        }
                    });

                    ll.addView(path);
                    ll.addView(browseBtn);
                    builder.setTitle(R.string.save_backup_as);
                    builder.setView(ll);
                    builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int i) {
                            String pth = path.getText().toString();

                            // Display alert dialog if the path is blank
                            if(pth.isEmpty()) {
                                alert(R.string.alert, R.string.choose_destination);
                            }
                            // display alert dialog if the path is invalid
                            else if(!new File(pth.substring(0, pth.lastIndexOf("/"))).exists()) {
                                alert(R.string.alert, R.string.no_such_file_or_directory);
                            }
                            // display alert if a backup or a restore is in progress
                            else if(BackupService.isRunning()) {
                                alert(R.string.alert, R.string.backup_running);
                            }
                            // start backup in background service
                            else {
                                ProgressDlg dlg = new ProgressDlg(MainActivity.this);
                                dlg.show();
                                BackupService.startActionBackup(MainActivity.this, pth, dlg);
                            }
                        }
                    });
                    builder.show();
                }
            }
        });
    }

    /**
     * Set restore button on click event
     */
    private void setupRestoreBtn() {
        Button restoreBtn = findViewById(R.id.restoreBtn);

        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(BackupService.isRunning())  {
                    alert(R.string.alert, R.string.backup_running);
                }
                else {
                    StorageChooser chooser = new StorageChooser.Builder()
                            .withActivity(MainActivity.this)
                            .withFragmentManager(getFragmentManager())
                            .withMemoryBar(true)
                            .allowCustomPath(true)
                            .setType(StorageChooser.FILE_PICKER)
                            .build();

                    chooser.setOnSelectListener(new StorageChooser.OnSelectListener() {
                        @Override
                        public void onSelect(String p) {
                            ProgressDlg dialog = new ProgressDlg(MainActivity.this);
                            dialog.show();
                            BackupService.startActionRestore(MainActivity.this, p, dialog);
                        }
                    });

                    chooser.show();
                }
            }
        });

    }

    /**
     * Display an alert dialog with an ok dismiss button
     * @param resTitle string resource id
     * @param resMessage string resource id
     */
    private void alert(int resTitle, int resMessage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(resTitle);
        builder.setMessage(resMessage);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /**
     * Display an alert dialog with an ok dismiss button
     * @param resTitle string resource id
     * @param message
     */
    private void alert(int resTitle, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(resTitle);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /**
     * Display an alert dialog with an ok dismiss button
     * @param title
     * @param resMessage string resource id
     */
    private void alert(String title, int resMessage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(resMessage);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /**
     * Display an alert dialog with an ok dismiss button
     * @param title
     * @param message
     */
    private void alert(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /**
     * Display an alert dialog with an ok dismiss button and
     * with out a title
     * @param resMessage string resource id
     */
    private void alert(int resMessage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(resMessage);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /**
     * Display an alert dialog with an ok dismiss button and
     * without a title
     * @param message
     */
    private void alert(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /* Asks and checks for root privileges */
    private void initalizeShell() {
        // ask for root privileges
        HandlerThread handlerThread = new HandlerThread("handler");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());
        Shell.Interactive shell = (new Shell.Builder()).useSU().setHandler(handler).open();

        // display error and exit if root privileges are not available
        if(shell == null || !shell.isRunning()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.error)
                    .setMessage(R.string.no_root)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.dismiss();
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            finish();
                        }
                    })
                    .show();
        }
    }
}