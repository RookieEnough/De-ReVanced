/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/6b06b9d1328b971a06d10b4247f4c10f050e4f61/extensions/strava/src/main/java/app/revanced/extension/strava/AddMediaDownloadPatch.java
 */

package app.morphe.extension.strava;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.strava.mediamodels.data.MediaType;
import com.strava.photos.data.Media;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressLint("NewApi")
public final class AddMediaDownloadPatch {
    public static final int ACTION_DOWNLOAD = -1;
    public static final int ACTION_OPEN_LINK = -2;
    public static final int ACTION_COPY_LINK = -3;

    private static final OkHttpClient client = new OkHttpClient();

    public static boolean handleAction(int actionId, Media media) {
        String url = getUrl(media);
        switch (actionId) {
            case ACTION_DOWNLOAD:
                String name = media.getId();
                if (media.getType() == MediaType.VIDEO) {
                    downloadVideo(url, name);
                } else {
                    downloadPhoto(url, name);
                }
                return true;
            case ACTION_OPEN_LINK:
                Utils.openLink(url);
                return true;
            case ACTION_COPY_LINK:
                copyLink(url);
                return true;
            default:
                return false;
        }
    }

    public static void copyLink(CharSequence url) {
        Utils.setClipboard(url);
        showInfoToast("link_copied_to_clipboard", "🔗");
    }

    public static void downloadPhoto(String url, String name) {
        showInfoToast("loading", "⏳");
        Utils.runOnBackgroundThread(() -> {
            try (Response response = fetch(url)) {
                ResponseBody body = response.body();
                String mimeType = body.contentType().toString();
                String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                ContentResolver resolver = Utils.getContext().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name + '.' + extension);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Strava");
                Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                Uri row = resolver.insert(collection, values);
                try (OutputStream outputStream = resolver.openOutputStream(row)) {
                    transferTo(body.byteStream(), outputStream);
                } finally {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(row, values, null);
                }
                showInfoToast("animated_content_save_image_success", "✔️");
            } catch (IOException e) {
                showErrorToast("download_failure", "❌", e);
            }
        });
    }

    /**
     * Downloads a video in the M3U8 / HLS (HTTP Live Streaming) format.
     */
    public static void downloadVideo(String url, String name) {
        showInfoToast("loading", "⏳");
        Utils.runOnBackgroundThread(() -> {
            try {
                String highestQualityStreamUrl;
                try (Response response = fetch(url)) {
                    highestQualityStreamUrl = replaceFileName(url, lines(response).findFirst().get());
                }
                List<Future<Response>> futures;
                try (Response response = fetch(highestQualityStreamUrl)) {
                    futures = lines(response)
                            .map(line -> replaceFileName(highestQualityStreamUrl, line))
                            .map(chunkUrl -> Utils.submitOnBackgroundThread(() -> fetch(chunkUrl)))
                            .collect(Collectors.toList());
                }
                ContentResolver resolver = Utils.getContext().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name + '.' + "mp4");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                values.put(MediaStore.Video.Media.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension("mp4"));
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Strava");
                Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Uri row = resolver.insert(collection, values);
                try (OutputStream outputStream = resolver.openOutputStream(row)) {
                    Throwable error = null;
                    for (Future<Response> future : futures) {
                        if (error != null) {
                            if (future.cancel(true)) {
                                continue;
                            }
                        }
                        try (Response response = future.get()) {
                            if (error == null) {
                                transferTo(response.body().byteStream(), outputStream);
                            }
                        } catch (InterruptedException | IOException e) {
                            error = e;
                        } catch (ExecutionException e) {
                            error = e.getCause();
                        }
                    }
                    if (error != null) {
                        throw new IOException(error);
                    }
                } finally {
                    values.clear();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(row, values, null);
                }
                showInfoToast("animated_content_save_video_success", "✔️");
            } catch (IOException e) {
                showErrorToast("download_failure", "❌", e);
            }
        });
    }

    private static String getUrl(Media media) {
        return media.getType() == MediaType.VIDEO
                ? ((Media.Video) media).getVideoUrl()
                : media.getLargestUrl();
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

    private static void showErrorToast(String resourceName, String fallback, IOException exception) {
        String text = getString(resourceName, fallback);
        Utils.showToastLong(text + ' ' + exception.getLocalizedMessage());
    }

    private static Response fetch(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Got HTTP status code " + response.code());
        }
        return response;
    }

    /**
     * {@code inputStream.transferTo(outputStream)} is "too new".
     */
    private static void transferTo(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 8];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
    }

    /**
     * Gets all file names.
     */
    private static Stream<String> lines(Response response) {
        BufferedReader reader = new BufferedReader(response.body().charStream());
        return reader.lines().filter(line -> !line.startsWith("#"));
    }

    private static String replaceFileName(String uri, String newName) {
        return uri.substring(0, uri.lastIndexOf('/') + 1) + newName;
    }
}

