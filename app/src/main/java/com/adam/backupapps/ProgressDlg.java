package com.adam.backupapps;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * <h1>ProgressDlg</h1>
 * A custom alert dialog that displays a progress bar
 */
public class ProgressDlg {
    private Dialog dialog;
    private TextView message;
    private ProgressBar progressBar;

    /**
     * Setup custom AlertDialog
     * @param context appliction context
     */
    public ProgressDlg(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setLayoutParams(params);
        message = new TextView(context);
        message.setLayoutParams(params);
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(params);
        linearLayout.addView(message);
        linearLayout.addView(progressBar);
        builder.setView(linearLayout);
        builder.setCancelable(false);
        dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
    }

    /**
     * Show dialog
     */
    public void show() {
        dialog.show();
    }

    /**
     * Set the status message with a string resource value
     * @param resMessage string resource id
     */
    public void setMessage(int resMessage) {
        message.setText(resMessage);
    }

    /**
     * Set the status message with a string value
     * @param message
     */
    public void setMessage(String message) {
        this.message.setText(message);
    }

    /**
     * Set the maximum progress count
     * @param max
     */
    public void setMaxProgress(int max) {
        progressBar.setMax(max);
    }

    /**
     * Set the progress
     * @param progress
     */
    public void setProgress(int progress) {
        progressBar.setProgress(progress);
    }

    /**
     * Increment the progress by a number
     * @param increment
     */
    public void incrementProgressBy(int increment) {
        progressBar.incrementProgressBy(increment);
    }

    /**
     * Dismiss the dialog
     */
    public void dismiss() {
        dialog.dismiss();
    }
}
