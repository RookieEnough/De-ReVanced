/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/117
 */

package app.morphe.extension.strava;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;

import com.strava.modularframework.data.ModularEntryContainer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Adds an "Export GPX" item to the activity detail (ADP) overflow menu.
 *
 * An activity has no GPX endpoint that the app's credentials can reach: the website's
 * {@code activities/{id}/export_gpx} is authenticated purely by the {@code _strava4_session}
 * web cookie, which a natively logged in app does not hold. The GPX is therefore
 * reconstructed from the app's own authenticated data:
 * <ul>
 *   <li>{@code GET /api/v3/activities/{id}/streams/...} for the track and its sensor data
 *   <li>{@code GET /api/v3/activities/{id}} for the name, sport type and start time
 * </ul>
 * and serialised as a StravaGPX compatible file, which is identical to the website's export
 * for every field the app exposes.
 *
 * The menu item is injected into the modular framework menu of the ADP container, and its tap
 * is caught by the sentinel {@link #DESTINATION_URL} on the modular navigation event. The item
 * is built reflectively so that this extension carries no obfuscated Strava type names.
 */
@SuppressLint("NewApi")
public final class AddActivityExportPatch {

    private static final OkHttpClient client = new OkHttpClient();

    private static final String API_BASE = "https://www.strava.com/api/v3/activities/";
    private static final String STREAM_TYPES = "latlng,time,altitude,heartrate,cadence,temp,watts";

    /** Sentinel destination + element name that mark our injected menu item. */
    private static final String DESTINATION_URL = "morphe://export_gpx";
    private static final String ELEMENT_NAME = "morphe_export_gpx";

    // region patch hooks

    /**
     * Invoked from {@code AdpModularResponse$Success.<init>} with the activity's
     * modular container. Appends our "Download GPX" item to its menu list.
     */
    @SuppressWarnings("unchecked")
    public static void augmentAdpMenu(ModularEntryContainer container) {
        try {
            if (container == null) return;

            // Read the backing field directly, NOT getMenuItems(): other patches (Hide
            // distractions) wrap getMenuItems() to return a filtered *copy*, so mutating that
            // return value would be discarded.
            Field field = findField(container.getClass(), "menuItems");
            if (field == null) {
                Logger.printException(() -> "No menuItems field on " + container.getClass());
                return;
            }
            field.setAccessible(true);
            Object raw = field.get(container);
            List<Object> items = raw instanceof List ? (List<Object>) raw : null;

            if (items != null) {
                for (Object it : items) {
                    if (it != null && ELEMENT_NAME.equals(callString(it, "getElementName"))) {
                        return; // already added
                    }
                }
            }
            Object menuItem = buildMenuItem(container);
            if (menuItem == null) return;

            if (items != null) {
                try {
                    items.add(menuItem);
                    return;
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Menu list is immutable, replacing it", ex);
                }
            }
            // Menu list was null or immutable: replace the field with a mutable copy.
            List<Object> copy = new ArrayList<>();
            if (items != null) copy.addAll(items);
            copy.add(menuItem);
            field.set(container, copy);
        } catch (Throwable ex) {
            Logger.printException(() -> "augmentAdpMenu failure", ex);
        }
    }

    /** The app's own label for exporting an activity as GPX, as used by the feed's menu. */
    private static String menuText() {
        return getString("menu_feed_ctx_export_gpx", "Export GPX");
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Invoked at the top of the ADP view model's {@code onEvent}. Returns true if the
     * event is our menu item's navigation (so the caller consumes it).
     */
    public static boolean onAdpEvent(Object event) {
        try {
            if (event == null) return false;
            Object dest = findFieldInstanceOf(event, "com.strava.modularframework.data.Destination");
            if (dest == null) return false;
            Object url = callString(dest, "getUrl");
            if (!DESTINATION_URL.equals(url)) return false;

            Activity activity = (Activity) findFieldAssignableTo(event, Activity.class);
            long id = activity != null ? extractActivityId(activity) : -1;
            Context ctx = activity != null ? activity : Utils.getContext();
            if (id <= 0) {
                showErrorToast("download_failure", "❌", new IOException("No activity id"));
                return true;
            }
            download(ctx, id);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "onAdpEvent failure", ex);
            return false;
        }
    }

    // endregion

    // region menu item construction (reflection; no obfuscated names)

    private static Object buildMenuItem(ModularEntryContainer container) {
        try {
            Class<?> mmiClass = Class.forName("com.strava.modularframework.data.ModularMenuItem");
            Constructor<?> ctor = null;
            for (Constructor<?> c : mmiClass.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 4) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) return null;
            Class<?>[] pt = ctor.getParameterTypes();
            Class<?> textInterface = pt[0];   // obfuscated text-descriptor interface: String a(Context)
            Class<?> destClass = pt[1];       // com.strava.modularframework.data.Destination

            Object text = Proxy.newProxyInstance(
                    textInterface.getClassLoader(),
                    new Class[]{textInterface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "toString": return menuText();
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == (args != null ? args[0] : null);
                            }
                        }
                        return menuText(); // Obfuscated `String a(Context)` that supplies the label.
                    });

            Object destination = destClass.getDeclaredConstructor(String.class).newInstance(DESTINATION_URL);

            // The visible overflow items are text-only (no icons), so a null icon puts ours in
            // the same bucket. (The ADP renderer buckets items by icon presence.)
            ctor.setAccessible(true);
            return ctor.newInstance(text, destination, null, ELEMENT_NAME);
        } catch (Throwable ex) {
            Logger.printException(() -> "buildMenuItem failure", ex);
            return null;
        }
    }


    // endregion

    // region reflection helpers

    private static String callString(Object target, String getter) {
        try {
            Object r = target.getClass().getMethod(getter).invoke(target);
            return r != null ? r.toString() : null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "No " + getter + " on " + target.getClass(), ex);
            return null;
        }
    }

    private static Object findFieldInstanceOf(Object owner, String className) {
        try {
            Class<?> target = Class.forName(className);
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!target.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object v = field.get(owner);
                    if (target.isInstance(v)) return v;
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "findFieldInstanceOf failure", ex);
        }
        return null;
    }

    private static Object findFieldAssignableTo(Object owner, Class<?> target) {
        try {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object v = field.get(owner);
                    if (target.isInstance(v)) return v;
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "findFieldAssignableTo failure", ex);
        }
        return null;
    }

    // endregion

    // region activity id + download

    /** Reads the activity id from the launching intent of the ADP activity. */
    public static long extractActivityId(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent != null) {
                long extra = intent.getLongExtra("activityId", -1L);
                if (extra > 0) return extra;
                extra = intent.getLongExtra("com.strava.activityId", -1L);
                if (extra > 0) return extra;
                if (intent.getData() != null) {
                    Matcher m = Pattern.compile("activities/(\\d+)").matcher(intent.getData().toString());
                    if (m.find()) return Long.parseLong(m.group(1));
                }
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    for (String key : extras.keySet()) {
                        if (!key.toLowerCase(Locale.US).contains("activit")) continue;
                        long val = extras.getLong(key, -1L);
                        if (val > 0) return val;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "extractActivityId failure", ex);
        }
        return -1;
    }

    private static void download(final Context context, final long activityId) {
        showInfoToast("loading", "⏳");
        Utils.runOnBackgroundThread(() -> {
            try {
                String token = getAuthToken(context);
                if (token == null) {
                    showErrorToast("download_failure", "❌", new IOException("No Strava auth token"));
                    return;
                }
                JSONObject detail = getJson(API_BASE + activityId, token);
                JSONObject streams = getJson(
                        API_BASE + activityId + "/streams/" + STREAM_TYPES + "?key_by_type=true",
                        token);

                String gpx = buildGpx(detail, streams);
                String fileName = safeFileName(detail.optString("name", "activity_" + activityId)) + ".gpx";

                saveToDownloads(fileName, "application/gpx+xml",
                        new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
                showInfoToast("exo_download_completed", "✔️");
            } catch (Exception ex) {
                Logger.printInfo(() -> "download failure", ex);
                showErrorToast("download_failure", "❌", ex);
            }
        });
    }

    private static String getString(String name, String fallback) {
        int id = ResourceUtils.getIdentifier(ResourceType.STRING, name);
        return id != 0 ? Utils.getResources().getString(id) : fallback;
    }

    private static void showInfoToast(String resourceName, String fallback) {
        Utils.showToastShort(getString(resourceName, fallback));
    }

    private static void showErrorToast(String resourceName, String fallback, Exception exception) {
        Utils.showToastLong(getString(resourceName, fallback) + ' ' + exception.getLocalizedMessage());
    }

    // endregion

    // region GPX serialisation

    private static String buildGpx(JSONObject detail, JSONObject streams) throws Exception {
        double[][] latlng = readLatLng(streams);
        if (latlng == null || latlng.length == 0) {
            throw new IOException("Activity has no GPS data");
        }
        int n = latlng.length;
        double[] altitude = readNumeric(streams, "altitude", n);
        long[] time = readTime(streams, n);
        double[] heartrate = readNumeric(streams, "heartrate", n);
        double[] cadence = readNumeric(streams, "cadence", n);
        double[] watts = readNumeric(streams, "watts", n);
        double[] temp = readNumeric(streams, "temp", n);

        String actName = detail.optString("name", "Strava Activity");
        String actType = gpxType(detail);
        long startEpochMs = parseIsoToEpochMs(detail.optString("start_date", null));

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        StringBuilder sb = new StringBuilder(n * 160 + 512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx creator=\"StravaGPX Morphe\" version=\"1.1\" ")
          .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
          .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
          .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd ")
          .append("http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd\" ")
          .append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n");

        if (startEpochMs > 0) {
            sb.append(" <metadata>\n  <time>").append(iso.format(new Date(startEpochMs))).append("</time>\n </metadata>\n");
        }
        sb.append(" <trk>\n  <name>").append(xml(actName)).append("</name>\n");
        if (actType != null) sb.append("  <type>").append(xml(actType)).append("</type>\n");
        sb.append("  <trkseg>\n");

        for (int i = 0; i < n; i++) {
            sb.append("   <trkpt lat=\"").append(fmt7(latlng[i][0]))
              .append("\" lon=\"").append(fmt7(latlng[i][1])).append("\">\n");
            if (altitude != null && !Double.isNaN(altitude[i])) {
                sb.append("    <ele>").append(fmt1(altitude[i])).append("</ele>\n");
            }
            if (time != null && startEpochMs > 0) {
                sb.append("    <time>").append(iso.format(new Date(startEpochMs + time[i] * 1000L))).append("</time>\n");
            }
            boolean hasHr = heartrate != null && !Double.isNaN(heartrate[i]);
            boolean hasCad = cadence != null && !Double.isNaN(cadence[i]);
            boolean hasWatts = watts != null && !Double.isNaN(watts[i]);
            boolean hasTemp = temp != null && !Double.isNaN(temp[i]);
            if (hasHr || hasCad || hasWatts || hasTemp) {
                sb.append("    <extensions>\n");
                if (hasWatts) sb.append("     <power>").append((int) Math.round(watts[i])).append("</power>\n");
                if (hasHr || hasCad || hasTemp) {
                    sb.append("     <gpxtpx:TrackPointExtension>\n");
                    if (hasTemp) sb.append("      <gpxtpx:atemp>").append(fmt1(temp[i])).append("</gpxtpx:atemp>\n");
                    if (hasHr) sb.append("      <gpxtpx:hr>").append((int) Math.round(heartrate[i])).append("</gpxtpx:hr>\n");
                    if (hasCad) sb.append("      <gpxtpx:cad>").append((int) Math.round(cadence[i])).append("</gpxtpx:cad>\n");
                    sb.append("     </gpxtpx:TrackPointExtension>\n");
                }
                sb.append("    </extensions>\n");
            }
            sb.append("   </trkpt>\n");
        }

        sb.append("  </trkseg>\n </trk>\n</gpx>\n");
        return sb.toString();
    }

    private static double[][] readLatLng(JSONObject streams) {
        JSONObject s = streams.optJSONObject("latlng");
        if (s == null) return null;
        JSONArray data = s.optJSONArray("data");
        if (data == null) return null;
        double[][] out = new double[data.length()][2];
        for (int i = 0; i < data.length(); i++) {
            JSONArray p = data.optJSONArray(i);
            if (p != null && p.length() >= 2) {
                out[i][0] = p.optDouble(0);
                out[i][1] = p.optDouble(1);
            }
        }
        return out;
    }

    private static double[] readNumeric(JSONObject streams, String key, int n) {
        JSONObject s = streams.optJSONObject(key);
        if (s == null) return null;
        JSONArray data = s.optJSONArray("data");
        if (data == null) return null;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = i < data.length() && !data.isNull(i) ? data.optDouble(i, Double.NaN) : Double.NaN;
        }
        return out;
    }

    private static long[] readTime(JSONObject streams, int n) {
        JSONObject s = streams.optJSONObject("time");
        if (s == null) return null;
        JSONArray data = s.optJSONArray("data");
        if (data == null) return null;
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = i < data.length() ? data.optLong(i, 0) : 0;
        }
        return out;
    }

    private static String gpxType(JSONObject detail) {
        String sport = detail.optString("sport_type", detail.optString("type", ""));
        if (sport.isEmpty()) return null;
        switch (sport) {
            case "Run":
            case "TrailRun": return "running";
            case "Ride":
            case "MountainBikeRide":
            case "GravelRide": return "cycling";
            case "Walk": return "walking";
            case "Hike": return "hiking";
            default: return sport.toLowerCase(Locale.US);
        }
    }

    // endregion

    // region networking / auth

    private static JSONObject getJson(String url, String token) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response");
            try {
                return new JSONObject(body.string());
            } catch (Exception ex) {
                throw new IOException("Invalid JSON from " + url, ex);
            }
        }
    }

    /**
     * The extension runs inside the Strava process, so it can read the app's own
     * SharedPreferences directly. Prefer the short-lived OAuth token the app itself
     * sends; fall back to the permanent access token.
     */
    private static String getAuthToken(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            String[] keys = {"pref.user.short_lived_access_token", "pref.user.access_token"};
            for (String key : keys) {
                String v = prefs.getString(key, null);
                if (v != null && v.length() >= 20) return v;
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "getAuthToken failure", ex);
        }
        return null;
    }

    // endregion

    // region file output

    private static void saveToDownloads(String fileName, String mimeType, InputStream in) throws IOException {
        ContentResolver resolver = Utils.getContext().getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Strava");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create MediaStore entry");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Output stream unavailable");
                transferTo(in, out);
            } finally {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        } else {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Strava");
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                transferTo(in, out);
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

    // endregion

    // region helpers

    private static long parseIsoToEpochMs(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso);
            return d != null ? d.getTime() : -1;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not parse start date: " + iso, ex);
            return -1;
        }
    }

    private static String fmt7(double v) {
        return String.format(Locale.US, "%.7f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeFileName(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "activity" : cleaned;
    }

    // endregion
}
