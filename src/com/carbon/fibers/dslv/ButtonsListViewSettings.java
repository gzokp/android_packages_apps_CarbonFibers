/*
 * Copyright (C) 2013 Slimroms
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

package com.carbon.fibers.dslv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.ListFragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.Toast;

import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.internal.util.slim.ImageHelper;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.util.slim.DeviceUtils.FilteredDeviceFeaturesArray;
import com.android.internal.util.slim.PolicyHelper;

import com.carbon.fibers.preference.SettingsPreferenceFragment;
import com.carbon.fibers.R;
import com.carbon.fibers.dslv.DragSortListView;
import com.carbon.fibers.dslv.DragSortController;
import com.carbon.fibers.fragments.navbar.ShortcutPickerHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public class ButtonsListViewSettings extends ListFragment implements
            ShortcutPickerHelper.OnPickListener {

    private static final int DLG_SHOW_ACTION_DIALOG   = 0;
    private static final int DLG_SHOW_ICON_PICKER     = 1;
    private static final int DLG_DELETION_NOT_ALLOWED = 2;
    private static final int DLG_SHOW_HELP_SCREEN     = 3;
    private static final int DLG_RESET_TO_DEFAULT     = 4;

    private static final int MENU_HELP = Menu.FIRST;
    private static final int MENU_ADD = MENU_HELP + 1;
    private static final int MENU_RESET = MENU_ADD + 1;

    private static final int NAV_BAR               = 0;
    private static final int PIE                   = 1;
    private static final int PIE_SECOND            = 2;
    private static final int NAV_RING              = 3;
    private static final int LOCKSCREEN_SHORTCUT   = 4;
    private static final int POWER_MENU_SHORTCUT   = 5;

    private static final int DEFAULT_MAX_BUTTON_NUMBER = 5;

    public static final int REQUEST_PICK_CUSTOM_ICON = 1000;

    private int mButtonMode;
    private int mMaxAllowedButtons;
    private boolean mUseAppPickerOnly;
    private boolean mDisableLongpress;
    private boolean mDisableIconPicker;
    private boolean mDisableDeleteLastEntry;

    private TextView mDisableMessage;

    private ButtonConfigsAdapter mButtonConfigsAdapter;

    private ArrayList<ButtonConfig> mButtonConfigs;
    private ButtonConfig mButtonConfig;

    private boolean mAdditionalFragmentAttached;
    private String mAdditionalFragment;
    private View mDivider;

    private int mPendingIndex = -1;
    private boolean mPendingLongpress;
    private boolean mPendingNewButton;

    private String[] mActionDialogValues;
    private String[] mActionDialogEntries;
    private String mActionValuesKey;
    private String mActionEntriesKey;

    private Activity mActivity;
    private ShortcutPickerHelper mPicker;

    private File mImageTmp;

    private DragSortListView.DropListener onDrop =
        new DragSortListView.DropListener() {
            @Override
            public void drop(int from, int to) {
                ButtonConfig item = mButtonConfigsAdapter.getItem(from);

                mButtonConfigsAdapter.remove(item);
                mButtonConfigsAdapter.insert(item, to);

                setConfig(mButtonConfigs, false);
            }
        };

    private DragSortListView.RemoveListener onRemove =
        new DragSortListView.RemoveListener() {
            @Override
            public void remove(int which) {
                ButtonConfig item = mButtonConfigsAdapter.getItem(which);
                mButtonConfigsAdapter.remove(item);
                if (mDisableDeleteLastEntry && mButtonConfigs.size() == 0) {
                    mButtonConfigsAdapter.add(item);
                    showDialogInner(DLG_DELETION_NOT_ALLOWED, 0, false, false);
                } else {
                    deleteIconFileIfPresent(item, true);
                    setConfig(mButtonConfigs, false);
                    if (mButtonConfigs.size() == 0) {
                        showDisableMessage(true);
                    }
                }
            }
        };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

        return inflater.inflate(R.layout.buttons_list_view_main, container, false);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = activity;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Resources res = getResources();

        mButtonMode = getArguments().getInt("buttonMode", NAV_BAR);
        mMaxAllowedButtons = getArguments().getInt("maxAllowedButtons", DEFAULT_MAX_BUTTON_NUMBER);
        mAdditionalFragment = getArguments().getString("fragment", null);
        mActionValuesKey = getArguments().getString("actionValues", "shortcut_action_values");
        mActionEntriesKey = getArguments().getString("actionEntries", "shortcut_action_entries");
        mDisableLongpress = getArguments().getBoolean("disableLongpress", false);
        mUseAppPickerOnly = getArguments().getBoolean("useAppPickerOnly", false);
        mDisableIconPicker = getArguments().getBoolean("disableIconPicker", false);
        mDisableDeleteLastEntry = getArguments().getBoolean("disableDeleteLastEntry", false);

        mDisableMessage = (TextView) view.findViewById(R.id.disable_message);

        FilteredDeviceFeaturesArray finalActionDialogArray = new FilteredDeviceFeaturesArray();
        finalActionDialogArray = DeviceUtils.filterUnsupportedDeviceFeatures(mActivity,
            res.getStringArray(res.getIdentifier(mActionValuesKey, "array", "com.android.settings")),
            res.getStringArray(res.getIdentifier(mActionEntriesKey, "array", "com.android.settings")));
        mActionDialogValues = finalActionDialogArray.values;
        mActionDialogEntries = finalActionDialogArray.entries;

        mPicker = new ShortcutPickerHelper(mActivity, this);

        mImageTmp = new File(mActivity.getCacheDir()
                + File.separator + "shortcut.tmp");

        DragSortListView listView = (DragSortListView) getListView();

        listView.setDropListener(onDrop);
        listView.setRemoveListener(onRemove);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int arg2,
                    long arg3) {
                if (!mUseAppPickerOnly) {
                    showDialogInner(DLG_SHOW_ACTION_DIALOG, arg2, false, false);
                } else {
                    if (mPicker != null) {
                        mPendingIndex = arg2;
                        mPendingLongpress = false;
                        mPendingNewButton = false;
                        mPicker.pickShortcut(getId());
                    }
                }
            }
        });

        if (!mDisableLongpress) {
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int arg2,
                        long arg3) {
                    if (!mUseAppPickerOnly) {
                        showDialogInner(DLG_SHOW_ACTION_DIALOG, arg2, true, false);
                    } else {
                        if (mPicker != null) {
                            mPendingIndex = arg2;
                            mPendingLongpress = true;
                            mPendingNewButton = false;
                            mPicker.pickShortcut(getId());
                        }
                    }
                    return true;
                }
            });
        }

        mButtonConfigs = getConfig();

        if (mButtonConfigs != null) {
            mButtonConfigsAdapter = new ButtonConfigsAdapter(mActivity, mButtonConfigs);
            setListAdapter(mButtonConfigsAdapter);
            showDisableMessage(mButtonConfigs.size() == 0);
        }

        mDivider = (View) view.findViewById(R.id.divider);
        loadAdditionalFragment();

        // get shared preference
        SharedPreferences preferences =
                mActivity.getSharedPreferences("dslv_settings", Activity.MODE_PRIVATE);
        if (!preferences.getBoolean("first_help_shown_mode_" + mButtonMode, false)) {
            preferences.edit()
                    .putBoolean("first_help_shown_mode_" + mButtonMode, true).commit();
            showDialogInner(DLG_SHOW_HELP_SCREEN, 0, false, false);
        }

        setHasOptionsMenu(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mAdditionalFragmentAttached) {
            FragmentManager fragmentManager = getFragmentManager();
            Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);
            if (fragment != null && !fragmentManager.isDestroyed()) {
                fragmentManager.beginTransaction().remove(fragment).commit();
            }
        }
    }

    private void loadAdditionalFragment() {
        if (mAdditionalFragment != null && !mAdditionalFragment.isEmpty()) {
            try {
                Class<?> classAdditionalFragment = Class.forName(mAdditionalFragment);
                Fragment fragment = (Fragment) classAdditionalFragment.newInstance();
                getFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, fragment).commit();
                if (mDivider != null) {
                    mDivider.setVisibility(View.VISIBLE);
                }
                mAdditionalFragmentAttached = true;
            } catch (Exception e) {
                mAdditionalFragmentAttached = false;
                e.printStackTrace();
            }
        }
    }

    @Override
    public void shortcutPicked(String action,
                String description, Bitmap bmp, boolean isApplication) {
        if (mPendingIndex == -1) {
            return;
        }
        if (bmp != null && !mPendingLongpress) {
            // Icon is present, save it for future use and add the file path to the action.
            String fileName = mActivity.getFilesDir()
                    + File.separator + "shortcut_" + System.currentTimeMillis() + ".png";
            try {
                FileOutputStream out = new FileOutputStream(fileName);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                action = action + "?hasExtraIcon=" + fileName;
                File image = new File(fileName);
                image.setReadable(true, false);
            }
        }
        if (mPendingNewButton) {
            addNewButton(action, description);
        } else {
            updateButton(action, description, null, mPendingIndex, mPendingLongpress);
        }
        mPendingLongpress = false;
        mPendingNewButton = false;
        mPendingIndex = -1;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == ShortcutPickerHelper.REQUEST_PICK_SHORTCUT
                    || requestCode == ShortcutPickerHelper.REQUEST_PICK_APPLICATION
                    || requestCode == ShortcutPickerHelper.REQUEST_CREATE_SHORTCUT) {
                mPicker.onActivityResult(requestCode, resultCode, data);

            } else if (requestCode == REQUEST_PICK_CUSTOM_ICON && mPendingIndex != -1) {
                if (mImageTmp.length() == 0 || !mImageTmp.exists()) {
                    mPendingIndex = -1;
                    Toast.makeText(mActivity,
                            getResources().getString(R.string.shortcut_image_not_valid),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                File image = new File(mActivity.getFilesDir() + File.separator
                        + "shortcut_" + System.currentTimeMillis() + ".png");
                String path = image.getAbsolutePath();
                mImageTmp.renameTo(image);
                image.setReadable(true, false);
                updateButton(null, null, path, mPendingIndex, false);
                mPendingIndex = -1;
            }
        } else {
            if (mImageTmp.exists()) {
                mImageTmp.delete();
            }
            mPendingLongpress = false;
            mPendingNewButton = false;
            mPendingIndex = -1;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateButton(String action, String description, String icon,
                int which, boolean longpress) {

        if (!longpress && checkForDuplicateMainNavButtons(action)) {
            return;
        }

        ButtonConfig button = mButtonConfigsAdapter.getItem(which);
        mButtonConfigsAdapter.remove(button);

        if (!longpress) {
            deleteIconFileIfPresent(button, false);
        }

        if (icon != null) {
            button.setIcon(icon);
        } else {
            if (longpress) {
                button.setLongpressAction(action);
                button.setLongpressActionDescription(description);
            } else {
                deleteIconFileIfPresent(button, true);
                button.setClickAction(action);
                button.setClickActionDescription(description);
                button.setIcon(ButtonsConstants.ICON_EMPTY);
            }
        }

        mButtonConfigsAdapter.insert(button, which);
        showDisableMessage(false);
        setConfig(mButtonConfigs, false);
    }

    private boolean checkForDuplicateMainNavButtons(String action) {
        ButtonConfig button;
        for (int i = 0; i < mButtonConfigs.size(); i++) {
            button = mButtonConfigsAdapter.getItem(i);
            if (button.getClickAction().equals(action)) {
                Toast.makeText(mActivity,
                        getResources().getString(R.string.shortcut_duplicate_entry),
                        Toast.LENGTH_LONG).show();
                return true;
            }
        }
        return false;
    }

    private void deleteIconFileIfPresent(ButtonConfig button, boolean deleteShortCutIcon) {
        File oldImage = new File(button.getIcon());
        if (oldImage.exists()) {
            oldImage.delete();
        }
        oldImage = new File(button.getClickAction().replaceAll(".*?hasExtraIcon=", ""));
        if (oldImage.exists() && deleteShortCutIcon) {
            oldImage.delete();
        }
    }

    private void showDisableMessage(boolean show) {
        if (mDisableMessage == null || mDisableDeleteLastEntry) {
            return;
        }
        if (show) {
            mDisableMessage.setVisibility(View.VISIBLE);
        } else {
            mDisableMessage.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                if (mButtonConfigs.size() == mMaxAllowedButtons) {
                    Toast.makeText(mActivity,
                            getResources().getString(R.string.shortcut_action_max),
                            Toast.LENGTH_LONG).show();
                    break;
                }
                if (!mUseAppPickerOnly) {
                    showDialogInner(DLG_SHOW_ACTION_DIALOG, 0, false, true);
                } else {
                    if (mPicker != null) {
                        mPendingIndex = 0;
                        mPendingLongpress = false;
                        mPendingNewButton = true;
                        mPicker.pickShortcut(getId());
                    }
                }
                break;
            case MENU_RESET:
                    showDialogInner(DLG_RESET_TO_DEFAULT, 0, false, true);
                break;
            case MENU_HELP:
                    showDialogInner(DLG_SHOW_HELP_SCREEN, 0, false, true);
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(0, MENU_RESET, 0, R.string.shortcut_action_reset)
                .setIcon(R.drawable.ic_action_backup) // use the backup icon
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_ADD, 0, R.string.shortcut_action_add)
                .setIcon(R.drawable.ic_menu_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_HELP, 0, R.string.shortcut_action_add)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private void addNewButton(String action, String description) {
        if (checkForDuplicateMainNavButtons(action)) {
            return;
        }
        ButtonConfig button = new ButtonConfig(
            action, description,
            ButtonsConstants.ACTION_NULL, getResources().getString(R.string.shortcut_action_none),
            ButtonsConstants.ICON_EMPTY);

            mButtonConfigsAdapter.add(button);
            showDisableMessage(false);
            setConfig(mButtonConfigs, false);
    }

    private ArrayList<ButtonConfig> getConfig() {
        switch (mButtonMode) {
            case NAV_BAR:
                return ButtonsHelper.getNavBarConfigWithDescription(
                    mActivity, mActionValuesKey, mActionEntriesKey);
            case NAV_RING:
                return ButtonsHelper.getNavRingConfigWithDescription(
                    mActivity, mActionValuesKey, mActionEntriesKey);
            case PIE:
                return ButtonsHelper.getPieConfigWithDescription(
                    mActivity, mActionValuesKey, mActionEntriesKey);
            case PIE_SECOND:
                return ButtonsHelper.getPieSecondLayerConfigWithDescription(
                    mActivity, mActionValuesKey, mActionEntriesKey);
            case POWER_MENU_SHORTCUT:
                return PolicyHelper.getPowerMenuConfigWithDescription(
                    mActivity, mActionValuesKey, mActionEntriesKey);
            case LOCKSCREEN_SHORTCUT:
                return ButtonsHelper.getLockscreenShortcutConfig(mActivity);
        }
        return null;
    }

    private void setConfig(ArrayList<ButtonConfig> buttonConfigs, boolean reset) {
        switch (mButtonMode) {
            case NAV_BAR:
                ButtonsHelper.setNavBarConfig(mActivity, buttonConfigs, reset);
                break;
            case NAV_RING:
                ButtonsHelper.setNavRingConfig(mActivity, buttonConfigs, reset);
                break;
            case PIE:
                ButtonsHelper.setPieConfig(mActivity, buttonConfigs, reset);
                break;
            case PIE_SECOND:
                ButtonsHelper.setPieSecondLayerConfig(mActivity, buttonConfigs, reset);
                break;
            case POWER_MENU_SHORTCUT:
                PolicyHelper.setPowerMenuConfig(mActivity, buttonConfigs, reset);
                break;
            case LOCKSCREEN_SHORTCUT:
                ButtonsHelper.setLockscreenShortcutConfig(mActivity, buttonConfigs, reset);
                break;
        }
    }

    private class ViewHolder {
        public TextView longpressActionDescriptionView;
        public ImageView iconView;
    }

    private class ButtonConfigsAdapter extends ArrayAdapter<ButtonConfig> {

        public ButtonConfigsAdapter(Context context, List<ButtonConfig> clickActionDescriptions) {
            super(context, R.layout.buttons_list_view_item,
                    R.id.click_action_description, clickActionDescriptions);
        }

        public View getView(final int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent);

            if (v != convertView && v != null) {
                ViewHolder holder = new ViewHolder();

                TextView longpressActionDecription =
                    (TextView) v.findViewById(R.id.longpress_action_description);
                ImageView icon = (ImageView) v.findViewById(R.id.icon);

                if (mDisableLongpress) {
                    longpressActionDecription.setVisibility(View.GONE);
                } else {
                    holder.longpressActionDescriptionView = longpressActionDecription;
                }

                holder.iconView = icon;

                v.setTag(holder);
            }

            ViewHolder holder = (ViewHolder) v.getTag();

            if (!mDisableLongpress) {
                holder.longpressActionDescriptionView.setText(
                    getResources().getString(R.string.shortcut_action_longpress)
                    + " " + getItem(position).getLongpressActionDescription());
            }
            if (mButtonMode == POWER_MENU_SHORTCUT) {
                holder.iconView.setImageDrawable(ImageHelper.resize(
                        mActivity, PolicyHelper.getPowerMenuIconImage(mActivity,
                        getItem(position).getClickAction(),
                        getItem(position).getIcon(), false), 36));
            } else {
                holder.iconView.setImageDrawable(ImageHelper.resize(
                        mActivity, ButtonsHelper.getButtonIconImage(mActivity,
                        getItem(position).getClickAction(),
                        getItem(position).getIcon()), 36));
            }

            if (!mDisableIconPicker && holder.iconView.getDrawable() != null) {
                holder.iconView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mPendingIndex = position;
                        showDialogInner(DLG_SHOW_ICON_PICKER, 0, false, false);
                    }
                });
            }

            return v;
        }
    }

    private void showDialogInner(int id, int which, boolean longpress, boolean newButton) {
        DialogFragment newFragment =
            MyAlertDialogFragment.newInstance(id, which, longpress, newButton);
        newFragment.setTargetFragment(this, 0);
        newFragment.show(getFragmentManager(), "dialog " + id);
    }

    public static class MyAlertDialogFragment extends DialogFragment {

        public static MyAlertDialogFragment newInstance(int id,
                int which, boolean longpress, boolean newButton) {
            MyAlertDialogFragment frag = new MyAlertDialogFragment();
            Bundle args = new Bundle();
            args.putInt("id", id);
            args.putInt("which", which);
            args.putBoolean("longpress", longpress);
            args.putBoolean("newButton", newButton);
            frag.setArguments(args);
            return frag;
        }

        ButtonsListViewSettings getOwner() {
            return (ButtonsListViewSettings) getTargetFragment();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            int id = getArguments().getInt("id");
            final int which = getArguments().getInt("which");
            final boolean longpress = getArguments().getBoolean("longpress");
            final boolean newButton = getArguments().getBoolean("newButton");
            switch (id) {
                case DLG_RESET_TO_DEFAULT:
                    return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.shortcut_action_reset)
                    .setMessage(R.string.reset)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // first delete custom icons in case they exist
                            ArrayList<ButtonConfig> buttonConfigs = getOwner().getConfig();
                            for (int i = 0; i < buttonConfigs.size(); i++) {
                                getOwner().deleteIconFileIfPresent(buttonConfigs.get(i), true);
                            }

                            // reset provider values and button adapter to default
                            getOwner().setConfig(null, true);
                            getOwner().mButtonConfigsAdapter.clear();

                            // Add the new default objects fetched from @getConfig()
                            buttonConfigs = getOwner().getConfig();
                            final int newConfigsSize = buttonConfigs.size();
                            for (int i = 0; i < newConfigsSize; i++) {
                                getOwner().mButtonConfigsAdapter.add(buttonConfigs.get(i));
                            }

                            // dirty helper if buttonConfigs list has no entries
                            // to proper update the content. .notifyDatSetC
