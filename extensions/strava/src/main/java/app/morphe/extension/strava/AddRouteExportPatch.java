/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/112
 */

package app.morphe.extension.strava;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressLint("NewApi")
public final class AddRouteExportPatch {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();

    public static volatile long currentRouteId = -1;

    public static void onShareObject(Object shareObject) {
        if (shareObject == null) return;
        try {
            Class<?> clazz = shareObject.getClass();
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                    if (field.getType() == long.class) {
                        field.setAccessible(true);
                        long val = field.getLong(shareObject);
                        if (val > 0) {
                            currentRouteId = val;
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static String onRouteShareUrl(String url) {
        if (url != null && url.contains("routes/")) {
            try {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("routes/(\\d+)").matcher(url);
                if (matcher.find()) {
                    currentRouteId = Long.parseLong(matcher.group(1));
                }
            } catch (Throwable ignored) {}
        }
        return url;
    }

    public static void onCopyLinkActivityStarted(android.app.Activity activity) {
        if (activity == null) return;
        try {
            long routeId = currentRouteId;
            if (routeId <= 0) {
                android.content.Intent intent = activity.getIntent();
                if (intent != null) {
                    String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
                    if (text != null) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("routes/(\\d+)").matcher(text);
                        if (matcher.find()) {
                            routeId = Long.parseLong(matcher.group(1));
                            currentRouteId = routeId;
                        }
                    }
                }
            }
            if (routeId > 0) {
                showExportDialog(activity, routeId);
            }
        } catch (Throwable ignored) {}
    }

    public static long extractRouteId(android.app.Activity activity) {
        if (activity != null && activity.getIntent() != null) {
            android.content.Intent intent = activity.getIntent();
            android.os.Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object val = extras.get(key);
                    if (val != null) {
                        try {
                            Class<?> clazz = val.getClass();
                            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                                for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                                    if (field.getType() == long.class) {
                                        field.setAccessible(true);
                                        long num = field.getLong(val);
                                        if (num > 0) return num;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (intent.getData() != null) {
                String uri = intent.getData().toString();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("routes/(\\d+)").matcher(uri);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
        }
        return currentRouteId;
    }


    public static void onShareSheetActivityStarted(final android.app.Activity activity) {
        if (activity == null) return;
        Utils.runOnMainThread(() -> {
            try {
                if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                    return;
                }
                final long routeId = extractRouteId(activity);
                android.view.View decor = activity.getWindow().getDecorView();
                if (decor != null) {
                    decor.postDelayed(() -> {
                        try {
                            if (!activity.isFinishing()) {
                                showExportDialog(activity, routeId);
                            }
                        } catch (Throwable ignored) {}
                    }, 300);
                } else {
                    showExportDialog(activity, routeId);
                }
            } catch (Throwable ignored) {}
        });
    }


    public static void showExportDialog(Context context, long routeId) {
        Utils.runOnMainThread(() -> {
            android.app.Activity activity = context instanceof android.app.Activity
                    ? (android.app.Activity) context
                    : Utils.getActivity();
            if (activity == null || activity.isFinishing()) return;
            Context ctx = activity;

            long finalRouteId = routeId > 0 ? routeId : currentRouteId;
            if (finalRouteId <= 0) {
                final android.widget.EditText input = new android.widget.EditText(ctx);
                input.setHint("Ex: 12345678");
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                new AlertDialog.Builder(ctx)
                        .setTitle("Exporter l'itinéraire")
                        .setMessage("Entrez l'ID de l'itinéraire Strava :")
                        .setView(input)
                        .setPositiveButton("GPX", (d, w) -> {
                            String text = input.getText().toString().replaceAll("[^0-9]", "");
                            if (!text.isEmpty()) {
                                downloadRoute(ctx, Long.parseLong(text), "gpx");
                            }
                        })
                        .setNeutralButton("TCX", (d, w) -> {
                            String text = input.getText().toString().replaceAll("[^0-9]", "");
                            if (!text.isEmpty()) {
                                downloadRoute(ctx, Long.parseLong(text), "tcx");
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }

            CharSequence[] items = new CharSequence[]{"GPX (.gpx)", "TCX (.tcx)"};
            new AlertDialog.Builder(ctx)
                    .setTitle("Exporter l'itinéraire (#" + finalRouteId + ")")
                    .setItems(items, (dialog, which) -> {
                        String format = which == 0 ? "gpx" : "tcx";
                        downloadRoute(ctx, finalRouteId, format);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    public static void downloadRoute(Context context, long routeId, String format) {
        showInfoToast("loading", "⏳ Export de l'itinéraire (" + format.toUpperCase() + ")...");
        Utils.runOnBackgroundThread(() -> {
            String url = "https://www.strava.com/routes/" + routeId + "/export_" + format;
            String fileName = "route_" + routeId + "." + format;
            try (Response response = fetch(url, context)) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("Réponse vide de Strava");
                }

                byte[] bytes = body.bytes();
                String textHead = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8);

                // Check if the response is valid XML or HTML error
                if (textHead.contains("<!DOCTYPE") || textHead.contains("<html") || textHead.contains("login") || textHead.contains("Sign In")) {
                    // Open directly in browser where user has active Strava session
                    Utils.runOnMainThread(() -> {
                        try {
                            android.content.Intent browserIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url));
                            browserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(browserIntent);
                            showInfoToast("download_browser", "🌐 Téléchargement dans le navigateur...");
                        } catch (Exception err) {
                            showErrorToast("download_failure", "❌ Échec de l'export : ", err);
                        }
                    });
                    return;
                }

                String mimeType = format.equalsIgnoreCase("gpx")
                        ? "application/gpx+xml"
                        : "application/vnd.garmin.tcx+xml";

                saveFile(fileName, mimeType, new java.io.ByteArrayInputStream(bytes));
                showInfoToast("download_success", "✔️ Itinéraire " + routeId + "." + format + " enregistré dans Téléchargements/Strava");
            } catch (Exception e) {
                showErrorToast("download_failure", "❌ Échec de l'export : ", e);
            }
        });
    }

    private static Response fetch(String url, Context context) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Strava/DeVanced");

        String cookies = getStoredCookies();
        if (cookies != null && !cookies.isEmpty()) {
            requestBuilder.header("Cookie", cookies);
        }

        String token = getStoredAuthToken(context);
        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        Response response = client.newCall(requestBuilder.build()).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("Code HTTP " + response.code());
        }
        return response;
    }

    private static String getStoredCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String c1 = cm.getCookie("https://www.strava.com");
            if (c1 != null && !c1.isEmpty()) return c1;
            String c2 = cm.getCookie("https://strava.com");
            if (c2 != null && !c2.isEmpty()) return c2;
            String c3 = cm.getCookie(".strava.com");
            if (c3 != null && !c3.isEmpty()) return c3;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getStoredAuthToken(Context context) {
        if (context == null) return null;
        try {
            File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists() && prefsDir.isDirectory()) {
                File[] files = prefsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().replace(".xml", "");
                        try {
                            android.content.SharedPreferences sp = context.getSharedPreferences(name, 0);
                            for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                                String key = entry.getKey().toLowerCase();
                                Object val = entry.getValue();
                                if (val instanceof String) {
                                    String strVal = (String) val;
                                    if ((key.contains("token") || key.contains("auth") || key.contains("access"))
                                            && strVal.length() >= 20 && !strVal.contains(" ") && !strVal.startsWith("http")) {
                                        return strVal;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void saveFile(String fileName, String mimeType, InputStream in) throws IOException {
        ContentResolver resolver = Utils.getContext().getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Strava");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Impossible de créer l'entrée MediaStore");
            }
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IOException("Flux de sortie indisponible");
                }
                transferTo(in, outputStream);
            } finally {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Strava");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, fileName);
            try (OutputStream outputStream = new FileOutputStream(file)) {
                transferTo(in, outputStream);
            }
        }
    }

    private static void transferTo(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
        out.flush();
    }

    private static String getString(String name, String fallback) {
        int id = ResourceUtils.getIdentifier(ResourceType.STRING, name);
        return id != 0
                ? Utils.getResources().getString(id)
                : fallback;
    }

    private static void showInfoToast(String resourceName, String fallback) {
        String text = getString(resourceName, fallback);
        Utils.showToastShort(text);
    }

    private static void showErrorToast(String resourceName, String fallback, Exception exception) {
        String text = getString(resourceName, fallback);
        Utils.showToastLong(text + ' ' + exception.getLocalizedMessage());
    }
}
