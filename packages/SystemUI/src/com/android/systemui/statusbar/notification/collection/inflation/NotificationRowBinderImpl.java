/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.collection.inflation;

import static android.graphics.Color.WHITE;

import static com.android.systemui.flags.Flags.NOTIFICATION_INLINE_REPLY_ANIMATION;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;

import com.android.internal.util.ImageUtils;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Provider;

/** Handles inflating and updating views for notifications. */
@SysUISingleton
public class NotificationRowBinderImpl implements NotificationRowBinder {

    private static final String TAG = "NotificationViewManager";

    private final Context mContext;
    private final NotificationMessagingUtil mMessagingUtil;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    private final NotifBindPipeline mNotifBindPipeline;
    private final RowContentBindStage mRowContentBindStage;
    private final Provider<RowInflaterTask> mRowInflaterTaskProvider;
    private final ExpandableNotificationRowComponent.Builder
            mExpandableNotificationRowComponentBuilder;
    private final IconManager mIconManager;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private BindRowCallback mBindRowCallback;
    private NotificationClicker mNotificationClicker;
    private FeatureFlags mFeatureFlags;

    @Inject
    public NotificationRowBinderImpl(
            Context context,
            NotificationMessagingUtil notificationMessagingUtil,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotifBindPipeline notifBindPipeline,
            RowContentBindStage rowContentBindStage,
            Provider<RowInflaterTask> rowInflaterTaskProvider,
            ExpandableNotificationRowComponent.Builder expandableNotificationRowComponentBuilder,
            IconManager iconManager,
            FeatureFlags featureFlags) {
        mContext = context;
        mNotifBindPipeline = notifBindPipeline;
        mRowContentBindStage = rowContentBindStage;
        mMessagingUtil = notificationMessagingUtil;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mRowInflaterTaskProvider = rowInflaterTaskProvider;
        mExpandableNotificationRowComponentBuilder = expandableNotificationRowComponentBuilder;
        mIconManager = iconManager;
        mFeatureFlags = featureFlags;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            BindRowCallback bindRowCallback) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mBindRowCallback = bindRowCallback;

        mIconManager.attach();
    }

    public void setNotificationClicker(NotificationClicker clicker) {
        mNotificationClicker = clicker;
    }

    private static boolean isWhite(int color, int lightNumber) {
        return color != 0 && Color.red(color) + Color.green(color) + Color.blue(color) > 3 * lightNumber;
    }

    private static boolean isBlackAndNotTransparent(int color, int lightNumber) {
        return color != 0 && Color.red(color) + Color.green(color) + Color.blue(color) <= 3 * lightNumber;
    }

    private static int getColorThreshold(Bitmap inputBMP) {
        int[] pix = new int[inputBMP.getWidth() * inputBMP.getHeight()];
        inputBMP.getPixels(pix, 0, inputBMP.getWidth(), 0, 0, inputBMP.getWidth(), inputBMP.getHeight());

        int allLightNumber = 0;
        int hasColor = 0;
        for (int color : pix) {
            if (color != 0) {
                allLightNumber += (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
                hasColor++;
            }
        }

        if (hasColor > 0) {
            return (int) (allLightNumber / hasColor * 0.8);
        }

        return 0;
    }

    public static Bitmap getSinglePic(Bitmap inputBMP) {
        int[] pix = new int[inputBMP.getWidth() * inputBMP.getHeight()];
        inputBMP.getPixels(pix, 0, inputBMP.getWidth(), 0, 0, inputBMP.getWidth(), inputBMP.getHeight());
        Bitmap returnBMP = Bitmap.createBitmap(inputBMP.getWidth(), inputBMP.getHeight(), Bitmap.Config.ARGB_8888);
        int lightNumber = getColorThreshold(inputBMP);

        int up = pix[inputBMP.getWidth() * 3 / 2];
        int down = pix[inputBMP.getWidth() * inputBMP.getHeight() - inputBMP.getWidth() * 3 / 2];
        int left = pix[(inputBMP.getHeight() / 2) * inputBMP.getWidth() - inputBMP.getWidth() + 2];
        int right = pix[(inputBMP.getHeight() / 2) * inputBMP.getWidth() - 1];

        boolean colorReversal = isWhite(up, lightNumber) && isWhite(down, lightNumber)
                && isWhite(left, lightNumber) && isWhite(right, lightNumber);

        boolean hasPadding = isBlackAndNotTransparent(up, lightNumber) && isBlackAndNotTransparent(down, lightNumber)
                && isBlackAndNotTransparent(left, lightNumber) && isBlackAndNotTransparent(right, lightNumber);

        boolean transparent = up == 0 && down == 0 && left == 0 && right == 0;

        int[] colorTemp = new int[pix.length];
        int first = -1;
        for (int i = 0; i < pix.length; i++) {
            int color = pix[i];
            if (color != 0) {
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);

                if (first == -1) {
                    first = r + g + b <= 3 * lightNumber ? 0 : 1;
                }

                if ((hasPadding || colorReversal) && ((first == 0 && r + g + b <= 3 * lightNumber) || (first == 1 && r + g + b > 3 * lightNumber))) {
                    colorTemp[i] = Color.WHITE;
                } else if (!(hasPadding || colorReversal) && ((first == 0 && r + g + b <= 3 * lightNumber) || (first == 1 && r + g + b > 3 * lightNumber))) {
                    colorTemp[i] = Color.WHITE;
                }
            }
        }

        returnBMP.setPixels(colorTemp, 0, inputBMP.getWidth(), 0, 0, inputBMP.getWidth(), inputBMP.getHeight());

        return returnBMP;
    }

    public static Bitmap base64ToBitmap(String base64) {
        byte[] decode = Base64.decode(base64, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decode, 0, decode.length);
    }

    public static void fixNotificationIcon(StatusBarNotification statusBarNotification, Context context) {
        try {
            Notification notification = statusBarNotification.getNotification();
            String packageName = statusBarNotification.getPackageName();
            Icon smallIcon = notification.getSmallIcon();
            Drawable drawable = smallIcon.loadDrawable(context);
            // Get from settings
            try {
                Uri uri = Uri.parse("content://top.easterNday.ICON/ICON");
                ContentResolver resolver = context.getContentResolver();
                String[] projection = {"packageName", "iconBitmap"};
                String selection = "packageName = ?";
                String[] selectionArgs = {packageName};
                Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String bm = cursor.getString(cursor.getColumnIndexOrThrow("iconBitmap"));
                    notification.setSmallIcon(Icon.createWithBitmap(base64ToBitmap(bm)));
                    cursor.close();
                    return;
                }
            }
            catch (Exception e){
                Log.e("fixNotificationIcon",e.toString());
            }
            // Generate it automatically
            if (drawable instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                if (!new ImageUtils().isGrayscale(bitmap)) {
                    Bitmap bm = getSinglePic(bitmap);
                    notification.setSmallIcon(
                            Icon.createWithBitmap(bm)
                    );
                    return;
                }
            }
        } catch (Exception e) {
            Log.e("ICON", e.toString());
        }
    }

    /**
     * Inflates the views for the given entry (possibly asynchronously).
     */
    @Override
    public void inflateViews(
            NotificationEntry entry,
            @NonNull NotifInflater.Params params,
            NotificationRowContentBinder.InflationCallback callback)
            throws InflationException {

        fixNotificationIcon(entry.getSbn(),mContext);

        ViewGroup parent = mListContainer.getViewParentForNotification(entry);

        if (entry.rowExists()) {
            mIconManager.updateIcons(entry);
            ExpandableNotificationRow row = entry.getRow();
            row.reset();
            updateRow(entry, row);
            inflateContentViews(entry, params, row, callback);
        } else {
            mIconManager.createIcons(entry);
            mRowInflaterTaskProvider.get().inflate(mContext, parent, entry,
                    row -> {
                        // Setup the controller for the view.
                        ExpandableNotificationRowComponent component =
                                mExpandableNotificationRowComponentBuilder
                                        .expandableNotificationRow(row)
                                        .notificationEntry(entry)
                                        .onExpandClickListener(mPresenter)
                                        .listContainer(mListContainer)
                                        .build();
                        ExpandableNotificationRowController rowController =
                                component.getExpandableNotificationRowController();
                        rowController.init(entry);
                        entry.setRowController(rowController);
                        bindRow(entry, row);
                        updateRow(entry, row);
                        inflateContentViews(entry, params, row, callback);
                    });
        }
    }

    @Override
    public void releaseViews(NotificationEntry entry) {
        if (!entry.rowExists()) {
            return;
        }
        final RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_CONTRACTED);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_EXPANDED);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_PUBLIC);
        mRowContentBindStage.requestRebind(entry, null);
    }

    /**
     * Bind row to various controllers and managers. This is only called when the row is first
     * created.
     *
     * TODO: This method associates a row with an entry, but eventually needs to not do that
     */
    private void bindRow(NotificationEntry entry, ExpandableNotificationRow row) {
        mListContainer.bindRow(row);
        mNotificationRemoteInputManager.bindRow(row);
        row.setOnActivatedListener(mPresenter);
        entry.setRow(row);
        mNotifBindPipeline.manageRow(entry, row);
        mBindRowCallback.onBindRow(row);
        row.setInlineReplyAnimationFlagEnabled(
                mFeatureFlags.isEnabled(NOTIFICATION_INLINE_REPLY_ANIMATION));
    }

    /**
     * Update row after the notification has updated.
     *
     * @param entry notification that has updated
     */
    private void updateRow(
            NotificationEntry entry,
            ExpandableNotificationRow row) {
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        // bind the click event to the content area
        requireNonNull(mNotificationClicker).register(row, entry.getSbn());
    }

    /**
     * Inflate the row's basic content views.
     */
    private void inflateContentViews(
            NotificationEntry entry,
            @NonNull NotifInflater.Params inflaterParams,
            ExpandableNotificationRow row,
            @Nullable NotificationRowContentBinder.InflationCallback inflationCallback) {
        final boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(entry.getSbn(), entry.getImportance());
        final boolean isLowPriority = inflaterParams.isLowPriority();

        RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.requireContentViews(FLAG_CONTENT_VIEW_CONTRACTED);
        params.requireContentViews(FLAG_CONTENT_VIEW_EXPANDED);
        params.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        params.setUseLowPriority(isLowPriority);

        if (mNotificationLockscreenUserManager.needsRedaction(entry)) {
            params.requireContentViews(FLAG_CONTENT_VIEW_PUBLIC);
        } else {
            params.markContentViewsFreeable(FLAG_CONTENT_VIEW_PUBLIC);
        }

        params.rebindAllContentViews();
        mRowContentBindStage.requestRebind(entry, en -> {
            row.setUsesIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
            row.setIsLowPriority(isLowPriority);
            if (inflationCallback != null) {
                inflationCallback.onAsyncInflationFinished(en);
            }
        });
    }

    /** Callback for when a row is bound to an entry. */
    public interface BindRowCallback {
        /**
         * Called when a new row is created and bound to a notification.
         */
        void onBindRow(ExpandableNotificationRow row);
    }
}
