package de.meowing.cobaltwrapper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Notificación propia de descarga, con progreso real, para que se vea igual
 * sin importar si el archivo viene de un enlace directo, un blob generado
 * en el navegador, o un data URI.
 */
public class DownloadNotifier {

    private static final String CHANNEL_ID = "downloads";
    private final Context context;

    public DownloadNotifier(Context context) {
        this.context = context.getApplicationContext();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Progress of files being downloaded through cobalt");
                manager.createNotificationChannel(channel);
            }
        }
    }

    public void showIndeterminate(int notifId, String title) {
        notify(notifId, baseBuilder(title, "Downloading…")
                .setProgress(0, 0, true)
                .setOngoing(true));
    }

    public void showProgress(int notifId, String title, int percent) {
        notify(notifId, baseBuilder(title, percent + "%")
                .setProgress(100, percent, false)
                .setOngoing(true));
    }

    public void showCompleted(int notifId, String title) {
        notify(notifId, baseBuilder(title, "Download complete")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true));
    }

    public void showFailed(int notifId, String title) {
        notify(notifId, baseBuilder(title, "Download failed")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true));
    }

    private NotificationCompat.Builder baseBuilder(String title, String text) {
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, openApp, flags);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }

    private void notify(int notifId, NotificationCompat.Builder builder) {
        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build());
        } catch (SecurityException ignored) {
            // Sin permiso de notificaciones; la descarga sigue funcionando,
            // solo no se muestra el aviso.
        }
    }
}
