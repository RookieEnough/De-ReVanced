/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/114
 */

package app.morphe.extension.shared.patches;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Supplies the active Google account avatar to Google Photos' OneGoogle UI.
 *
 * <p>GmsCore stores the account avatar, but the newer OneGoogle APIs used by Photos do not
 * currently expose it. This bridge obtains the authenticated user-info picture, keeps a separate
 * cache for each account and updates the toolbar/account-sheet views without changing account
 * authentication itself.</p>
 */
final class GooglePhotosAccountAvatar {
    private static final String ACCOUNT_TYPE = "app.revanced";
    private static final String RESOURCE_PACKAGE_NAME = "com.google.android.apps.photos";
    private static final String PROFILE_TOKEN_TYPE =
            "oauth2:openid https://www.googleapis.com/auth/mobileapps.native "
                    + "https://www.googleapis.com/auth/photos.native";
    private static final String USER_INFO_URL =
            "https://www.googleapis.com/oauth2/v3/userinfo";

    private static final String PREFS_NAME = "morphe_google_photos_avatar";
    private static final String PREF_SELECTED_ACCOUNT = "selected_account";
    private static final String CACHE_FILE_PREFIX = "google_account_profile_avatar_";
    private static final long CACHE_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final long WINDOW_SCAN_THROTTLE_MILLIS = 250L;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final String ACCOUNT_AVATAR_OVERLAY_TAG =
            "morphe_google_photos_account_avatar";

    private static final AtomicReference<String> FETCHING_ACCOUNT = new AtomicReference<>();
    private static final Set<String> CANCELLED_AUTH_ACCOUNTS =
            Collections.synchronizedSet(new HashSet<>());
    private static final AtomicBoolean WINDOW_SCAN_ERROR_LOGGED = new AtomicBoolean();
    private static final Map<ImageView, String> SCHEDULED_TOOLBAR_AVATARS = new WeakHashMap<>();
    private static final Map<View, String> SCHEDULED_ACCOUNT_SHEETS = new WeakHashMap<>();
    private static final Set<View> OBSERVED_WINDOW_ROOTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Nullable
    private static volatile Bitmap avatar;
    @Nullable
    private static volatile String avatarAccountName;
    @Nullable
    private static volatile String selectedAccountName;
    private static volatile long lastWindowScanUptime;

    private GooglePhotosAccountAvatar() {
    }

    static void install(Activity activity) {
        Logger.printInfo(() -> "Installing the Google Photos account avatar bridge");
        View root = activity.getWindow().getDecorView();
        observeWindowRoot(activity, root);
        scanWindowRoots(activity, true);
        refresh(activity, root);

        // A few bounded follow-up scans catch windows created immediately after HomeActivity.
        // Later account-sheet windows are discovered through layout/focus callbacks instead of
        // polling WindowManagerGlobal for the entire lifetime of the app.
        Utils.runOnMainThreadDelayed(() -> scanWindowRoots(activity, true), 250);
        Utils.runOnMainThreadDelayed(() -> scanWindowRoots(activity, true), 1_000);
        Utils.runOnMainThreadDelayed(() -> scanWindowRoots(activity, true), 2_500);
    }

    private static void observeWindowRoot(Activity activity, View root) {
        if (!OBSERVED_WINDOW_ROOTS.add(root)) return;

        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(() -> {
            refresh(activity, root);
            scanWindowRoots(activity, false);
        });
        observer.addOnWindowFocusChangeListener(hasFocus -> {
            refresh(activity, root);
            scanWindowRoots(activity, true);
        });
        refresh(activity, root);
    }

    private static void scanWindowRoots(Activity activity, boolean force) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        long now = SystemClock.uptimeMillis();
        if (!force && now - lastWindowScanUptime < WINDOW_SCAN_THROTTLE_MILLIS) return;
        lastWindowScanUptime = now;

        try {
            Class<?> windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal");
            Object windowManagerGlobal = windowManagerGlobalClass
                    .getMethod("getInstance")
                    .invoke(null);
            java.lang.reflect.Field viewsField =
                    windowManagerGlobalClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object roots = viewsField.get(windowManagerGlobal);
            if (roots instanceof List<?>) {
                for (Object root : (List<?>) roots) {
                    if (root instanceof View) observeWindowRoot(activity, (View) root);
                }
            }
        } catch (Exception exception) {
            if (WINDOW_SCAN_ERROR_LOGGED.compareAndSet(false, true)) {
                Logger.printException(
                        () -> "Could not inspect Google Photos account-panel windows",
                        exception
                );
            }
        }
    }

    private static void refresh(Activity activity, View root) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        Account account = resolveSelectedAccount(activity, root);
        if (account != null) activateAccount(activity, account.name);

        applyAvatar(activity, root);

        if (account != null
                && (avatar == null || !sameAccount(account.name, avatarAccountName))) {
            requestProfileToken(activity, root, account);
        }
    }

    @Nullable
    private static Account resolveSelectedAccount(Activity activity, View root) {
        String accountFromUi = findSelectedAccountName(activity, root);
        AccountManager accountManager = AccountManager.get(activity);
        Account[] accounts = accountManager.getAccountsByType(ACCOUNT_TYPE);

        if (accountFromUi != null) {
            Account visibleAccount = findAccount(accounts, accountFromUi);
            return visibleAccount != null
                    ? visibleAccount
                    : new Account(accountFromUi, ACCOUNT_TYPE);
        }

        Account current = findAccount(accounts, selectedAccountName);
        if (current != null) return current;

        SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, 0);
        String rememberedName = preferences.getString(PREF_SELECTED_ACCOUNT, null);
        Account remembered = findAccount(accounts, rememberedName);
        if (remembered != null) return remembered;

        // Choosing accounts[0] when several accounts are present can show another user's avatar.
        // Only fall back automatically when there is no ambiguity.
        return accounts.length == 1 ? accounts[0] : null;
    }

    @Nullable
    private static Account findAccount(Account[] accounts, @Nullable String accountName) {
        if (accountName == null) return null;
        for (Account account : accounts) {
            if (sameAccount(account.name, accountName)) return account;
        }
        return null;
    }

    @Nullable
    private static String findSelectedAccountName(Activity activity, View root) {
        int selectedAccountId = getResourceId(activity, "selected_account_disc");
        if (selectedAccountId != 0) {
            String email = findEmailInViewAndParents(root.findViewById(selectedAccountId));
            if (email != null) return email;
        }

        int toolbarAvatarId = getResourceId(activity, "og_apd_internal_image_view");
        if (toolbarAvatarId != 0) {
            String email = findEmailInViewAndParents(root.findViewById(toolbarAvatarId));
            if (email != null) return email;
        }

        int accountSheetAvatarId = getResourceId(activity, "og_bento_selected_account_avatar");
        if (accountSheetAvatarId != 0) {
            return findEmailInViewAndParents(root.findViewById(accountSheetAvatarId));
        }

        return null;
    }

    private static int getResourceId(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        if (id != 0) return id;
        return activity.getResources().getIdentifier(name, "id", RESOURCE_PACKAGE_NAME);
    }

    @Nullable
    private static String findEmailInViewAndParents(@Nullable View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 5; depth++) {
            String email = extractEmail(current.getContentDescription());
            if (email != null) return email;

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    @Nullable
    private static String extractEmail(@Nullable CharSequence text) {
        if (text == null) return null;
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static void activateAccount(Activity activity, String accountName) {
        if (!sameAccount(accountName, selectedAccountName)) {
            selectedAccountName = accountName;
            activity.getSharedPreferences(PREFS_NAME, 0)
                    .edit()
                    .putString(PREF_SELECTED_ACCOUNT, accountName)
                    .apply();

            Bitmap cachedAvatar = readCachedAvatar(activity, accountName);
            avatar = cachedAvatar;
            avatarAccountName = cachedAvatar == null ? null : accountName;
            Logger.printInfo(() -> "Google Photos avatar account changed");
            return;
        }

        if (avatar == null && !sameAccount(accountName, avatarAccountName)) {
            Bitmap cachedAvatar = readCachedAvatar(activity, accountName);
            if (cachedAvatar != null) {
                avatar = cachedAvatar;
                avatarAccountName = accountName;
            }
        }
    }

    private static void requestProfileToken(Activity activity, View root, Account account) {
        final String accountName = account.name;
        final String accountKey = accountName.toLowerCase(Locale.ROOT);
        if (avatar != null && sameAccount(accountName, avatarAccountName)) return;
        if (CANCELLED_AUTH_ACCOUNTS.contains(accountKey)) return;
        if (!FETCHING_ACCOUNT.compareAndSet(null, accountName)) return;

        try {
            Logger.printInfo(() -> "Requesting the Google Photos avatar token");

            Bundle options = new Bundle();
            options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, activity.getPackageName());
            AccountManager.get(activity).getAuthToken(
                    account,
                    PROFILE_TOKEN_TYPE,
                    options,
                    activity,
                    future -> handleProfileTokenResult(
                            activity,
                            root,
                            accountName,
                            accountKey,
                            future
                    ),
                    null
            );
        } catch (Exception exception) {
            FETCHING_ACCOUNT.compareAndSet(accountName, null);
            Logger.printException(
                    () -> "Could not request the Google Photos profile token",
                    exception
            );
        }
    }

    private static void handleProfileTokenResult(
            Activity activity,
            View root,
            String accountName,
            String accountKey,
            AccountManagerFuture<Bundle> future
    ) {
        final String token;
        try {
            Bundle result = future.getResult();
            token = result.getString(AccountManager.KEY_AUTHTOKEN);
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("GmsCore returned no profile token");
            }
            CANCELLED_AUTH_ACCOUNTS.remove(accountKey);
        } catch (OperationCanceledException exception) {
            // Morphe returns a consent Intent when this OAuth service has not been permitted yet.
            // The Activity overload above lets AccountManager launch it and resume this callback.
            // If the user denies/cancels, suppress repeated prompts until the process restarts.
            CANCELLED_AUTH_ACCOUNTS.add(accountKey);
            FETCHING_ACCOUNT.compareAndSet(accountName, null);
            Logger.printInfo(() -> "Google Photos avatar permission was not granted");
            return;
        } catch (Exception exception) {
            FETCHING_ACCOUNT.compareAndSet(accountName, null);
            Logger.printInfo(
                    () -> "Could not obtain the Google Photos profile token: " + exception.getMessage()
            );
            if (selectedAccountName != null
                    && !sameAccount(accountName, selectedAccountName)) {
                Utils.runOnMainThread(() -> refresh(activity, root));
            }
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            boolean refreshDifferentAccount = false;
            try {
                Bitmap downloadedAvatar = downloadAvatar(token);
                if (downloadedAvatar == null) {
                    throw new IllegalStateException("Google user-info returned no avatar");
                }

                writeCachedAvatar(activity, accountName, downloadedAvatar);

                if (sameAccount(accountName, selectedAccountName)) {
                    avatar = downloadedAvatar;
                    avatarAccountName = accountName;
                    Logger.printInfo(() -> "Google Photos account avatar loaded");
                    Utils.runOnMainThread(() -> applyAvatar(activity, root));
                } else {
                    refreshDifferentAccount = selectedAccountName != null;
                }
            } catch (Exception exception) {
                Logger.printInfo(
                        () -> "Could not load the Google Photos account avatar: " + exception.getMessage()
                );
                refreshDifferentAccount = selectedAccountName != null
                        && !sameAccount(accountName, selectedAccountName);
            } finally {
                FETCHING_ACCOUNT.compareAndSet(accountName, null);
                if (refreshDifferentAccount) {
                    Utils.runOnMainThread(() -> refresh(activity, root));
                }
            }
        });
    }

    @Nullable
    private static Bitmap downloadAvatar(String token) throws Exception {
        HttpURLConnection userInfoConnection = openConnection(USER_INFO_URL);
        userInfoConnection.setRequestProperty("Authorization", "Bearer " + token);

        try {
            int status = userInfoConnection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Google user-info HTTP status " + status);
            }

            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    userInfoConnection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
            }

            JSONObject response = new JSONObject(jsonBuilder.toString());
            String pictureUrl = response.optString("picture", null);
            if (pictureUrl == null || pictureUrl.isEmpty()) return null;

            HttpURLConnection imageConnection = openConnection(pictureUrl);
            try {
                if (imageConnection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                try (InputStream stream = new BufferedInputStream(imageConnection.getInputStream())) {
                    return getCircularBitmap(BitmapFactory.decodeStream(stream));
                }
            } finally {
                imageConnection.disconnect();
            }
        } finally {
            userInfoConnection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json,image/*");
        return connection;
    }

    private static void applyAvatar(Activity activity, View root) {
        Bitmap currentAvatar = avatar;
        String currentAccount = avatarAccountName;
        if (currentAvatar == null
                || currentAccount == null
                || !sameAccount(currentAccount, selectedAccountName)
                || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }

        int toolbarAvatarId = getResourceId(activity, "og_apd_internal_image_view");
        if (toolbarAvatarId != 0) {
            View toolbarAvatar = root.findViewById(toolbarAvatarId);
            if (toolbarAvatar instanceof ImageView) {
                updateImageView((ImageView) toolbarAvatar, currentAvatar, currentAccount);
            }
        }

        int accountSheetAvatarId = getResourceId(activity, "og_bento_selected_account_avatar");
        if (accountSheetAvatarId != 0) {
            View accountSheetAvatar = root.findViewById(accountSheetAvatarId);
            applyAccountSheetAvatar(accountSheetAvatar, currentAvatar, currentAccount);
            if (accountSheetAvatar != null
                    && shouldScheduleAccountSheet(accountSheetAvatar, currentAccount)) {
                accountSheetAvatar.postDelayed(
                        () -> applyAccountSheetAvatar(
                                accountSheetAvatar, currentAvatar, currentAccount),
                        100);
                accountSheetAvatar.postDelayed(
                        () -> applyAccountSheetAvatar(
                                accountSheetAvatar, currentAvatar, currentAccount),
                        400);
                accountSheetAvatar.postDelayed(
                        () -> applyAccountSheetAvatar(
                                accountSheetAvatar, currentAvatar, currentAccount),
                        1_200);
            }
        }
    }

    @Nullable
    private static Bitmap getCircularBitmap(@Nullable Bitmap src) {
        if (src == null) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        int size = Math.min(width, height);

        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);

        canvas.drawARGB(0, 0, 0, 0);
        Rect srcRect = new Rect(
                (width - size) / 2,
                (height - size) / 2,
                (width + size) / 2,
                (height + size) / 2
        );
        RectF dstRect = new RectF(0, 0, size, size);

        canvas.drawRoundRect(dstRect, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        paint.setXfermode(null);

        return output;
    }

    private static void applyAccountSheetAvatar(
            @Nullable View accountSheetAvatar,
            Bitmap bitmap,
            String accountName
    ) {
        if (!isCurrentAvatar(bitmap, accountName)
                || !(accountSheetAvatar instanceof ViewGroup)) {
            return;
        }

        ViewGroup group = (ViewGroup) accountSheetAvatar;
        ImageView targetImageView = findTargetImageView(group);
        if (targetImageView != null) {
            targetImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            targetImageView.setImageBitmap(bitmap);
            return;
        }

        ImageView overlay = null;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (ACCOUNT_AVATAR_OVERLAY_TAG.equals(child.getTag()) && child instanceof ImageView) {
                overlay = (ImageView) child;
                break;
            }
        }

        if (overlay == null) {
            overlay = new ImageView(group.getContext());
            overlay.setTag(ACCOUNT_AVATAR_OVERLAY_TAG);
            overlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
            group.addView(
                    overlay,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }
        overlay.setImageBitmap(bitmap);
    }

    private static boolean shouldScheduleAccountSheet(View view, String accountName) {
        String previouslyScheduledAccount = SCHEDULED_ACCOUNT_SHEETS.put(view, accountName);
        return !sameAccount(accountName, previouslyScheduledAccount);
    }

    @Nullable
    private static ImageView findTargetImageView(ViewGroup group) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ImageView
                    && !ACCOUNT_AVATAR_OVERLAY_TAG.equals(child.getTag())) {
                return (ImageView) child;
            }
        }

        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ViewGroup) {
                ImageView nested = findTargetImageView((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void updateImageView(ImageView imageView, Bitmap bitmap, String accountName) {
        if (!isCurrentAvatar(bitmap, accountName)) return;

        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(bitmap);

        String previouslyScheduledAccount = SCHEDULED_TOOLBAR_AVATARS.put(imageView, accountName);
        if (!sameAccount(accountName, previouslyScheduledAccount)) {
            imageView.postDelayed(() -> setImageBitmap(imageView, bitmap, accountName), 100);
            imageView.postDelayed(() -> setImageBitmap(imageView, bitmap, accountName), 500);
            imageView.postDelayed(() -> setImageBitmap(imageView, bitmap, accountName), 1_500);
            imageView.postDelayed(() -> setImageBitmap(imageView, bitmap, accountName), 5_000);
            imageView.postDelayed(() -> setImageBitmap(imageView, bitmap, accountName), 12_000);
        }
    }

    private static void setImageBitmap(ImageView imageView, Bitmap bitmap, String accountName) {
        if (imageView.isAttachedToWindow() && isCurrentAvatar(bitmap, accountName)) {
            imageView.setImageBitmap(bitmap);
        }
    }

    private static boolean isCurrentAvatar(Bitmap bitmap, String accountName) {
        return bitmap == avatar
                && sameAccount(accountName, avatarAccountName)
                && sameAccount(accountName, selectedAccountName);
    }

    private static boolean sameAccount(@Nullable String first, @Nullable String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    @Nullable
    private static Bitmap readCachedAvatar(Activity activity, String accountName) {
        File cacheFile = getCacheFile(activity, accountName);
        if (!cacheFile.isFile()) return null;
        if (System.currentTimeMillis() - cacheFile.lastModified() > CACHE_MAX_AGE_MILLIS) {
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return null;
        }

        try (InputStream stream = new FileInputStream(cacheFile)) {
            return getCircularBitmap(BitmapFactory.decodeStream(stream));
        } catch (Exception exception) {
            Logger.printException(() -> "Could not read the cached Google account avatar", exception);
            return null;
        }
    }

    private static void writeCachedAvatar(Activity activity, String accountName, Bitmap bitmap) {
        File cacheFile = getCacheFile(activity, accountName);
        try (FileOutputStream output = new FileOutputStream(cacheFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (Exception exception) {
            Logger.printException(() -> "Could not cache the Google account avatar", exception);
        }
    }

    private static File getCacheFile(Activity activity, String accountName) {
        return new File(
                activity.getCacheDir(),
                CACHE_FILE_PREFIX + hashAccountName(accountName) + ".png"
        );
    }

    private static String hashAccountName(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    accountName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception exception) {
            // SHA-256 is required by Android; keep a deterministic fallback for completeness.
            return Integer.toHexString(accountName.toLowerCase(Locale.ROOT).hashCode());
        }
    }
}
