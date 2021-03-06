/*
 * Copyright (C) 2012 The Android Open Source Project
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

package org.omnirom.alarmclock;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService.RemoteViewsFactory;

import org.omnirom.deskclock.Utils;
import org.omnirom.deskclock.worldclock.CityObj;
import org.omnirom.deskclock.worldclock.WorldClockAdapter;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class DigitalWidgetViewsFactory implements RemoteViewsFactory {
    private static final String TAG = "WidgetViewsFactory";

    private Context mContext;
    private Resources mResources;
    private int mId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private RemoteWorldClockAdapter mAdapter;

    // An adapter to provide the view for the list of cities in the world clock.
    private class RemoteWorldClockAdapter extends WorldClockAdapter {
        private final float mFontSize;

        public RemoteWorldClockAdapter(Context context) {
            super(context);
            mClocksPerRow = context.getResources().getInteger(
                    org.omnirom.deskclock.R.integer.appwidget_world_clocks_per_row);
            mFontSize = context.getResources().getDimension(org.omnirom.deskclock.R.dimen.widget_medium_font_size);
        }

        public RemoteViews getViewAt(int position) {
            // There are 2 cities per item
            int index = position * 2;
            if (index < 0 || index >= mCitiesList.length) {
                return null;
            }

            RemoteViews views = new RemoteViews(
                    mContext.getPackageName(), org.omnirom.deskclock.R.layout.world_clock_remote_list_item);

            // Always how the left clock
            updateView(views, (CityObj) mCitiesList[index], org.omnirom.deskclock.R.id.left_clock,
                    org.omnirom.deskclock.R.id.city_name_left, org.omnirom.deskclock.R.id.city_day_left);
            // Show the right clock if any, make it invisible if there is no
            // clock on the right
            // to keep the left view on the left.
            if (index + 1 < mCitiesList.length) {
                updateView(views, (CityObj) mCitiesList[index + 1], org.omnirom.deskclock.R.id.right_clock,
                        org.omnirom.deskclock.R.id.city_name_right, org.omnirom.deskclock.R.id.city_day_right);
            } else {
                hideView(views, org.omnirom.deskclock.R.id.right_clock, org.omnirom.deskclock.R.id.city_name_right,
                        org.omnirom.deskclock.R.id.city_day_right);
            }

            // Hide last spacer if last row
            int lastRow = ((mCitiesList.length + 1) / 2) - 1;
            if (position == lastRow) {
                views.setViewVisibility(org.omnirom.deskclock.R.id.city_spacer, View.GONE);
            } else {
                views.setViewVisibility(org.omnirom.deskclock.R.id.city_spacer, View.VISIBLE);
            }

            return views;
        }

        private void updateView(RemoteViews clock, CityObj cityObj, int clockId,
                int labelId, int dayId) {
            final Calendar now = Calendar.getInstance();
            now.setTimeInMillis(System.currentTimeMillis());
            int myDayOfWeek = now.get(Calendar.DAY_OF_WEEK);
            CityObj cityInDb = mCitiesDb.get(cityObj.mCityId);
            String cityTZ = (cityInDb != null) ? cityInDb.mTimeZone : cityObj.mTimeZone;
            now.setTimeZone(TimeZone.getTimeZone(cityTZ));
            int cityDayOfWeek = now.get(Calendar.DAY_OF_WEEK);

            WidgetUtils.setTimeFormat(clock, (int)(mFontSize / 3), clockId, 0);
            clock.setTextViewTextSize(clockId, TypedValue.COMPLEX_UNIT_PX, mFontSize);
            clock.setString(clockId, "setTimeZone", cityObj.mTimeZone);

            // Home city or city not in DB , use data from the save selected cities list
            clock.setTextViewText(labelId, Utils.getCityName(cityObj, cityInDb));

            int clockColor = WidgetUtils.getClockColor(mContext, mId);
            clock.setTextColor(clockId, clockColor);
            clock.setTextColor(labelId, clockColor);
            clock.setTextColor(dayId, clockColor);

            if (myDayOfWeek != cityDayOfWeek) {
                clock.setTextViewText(dayId, mContext.getString(
                        org.omnirom.deskclock.R.string.world_day_of_week_label, now.getDisplayName(
                                Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())));
                clock.setViewVisibility(dayId, View.VISIBLE);
            } else {
                clock.setViewVisibility(dayId, View.GONE);
            }

            clock.setViewVisibility(clockId, View.VISIBLE);
            clock.setViewVisibility(labelId, View.VISIBLE);
        }

        private void hideView(
                RemoteViews clock, int clockId, int labelId, int dayId) {
            clock.setViewVisibility(clockId, View.INVISIBLE);
            clock.setViewVisibility(labelId, View.INVISIBLE);
            clock.setViewVisibility(dayId, View.INVISIBLE);
        }
    }

    public DigitalWidgetViewsFactory(Context context, Intent intent) {
        mContext = context;
        mResources = mContext.getResources();
        mId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        mAdapter = new RemoteWorldClockAdapter(context);
    }

    @SuppressWarnings("unused")
    public DigitalWidgetViewsFactory() {
    }

    @Override
    public int getCount() {
        if (WidgetUtils.showList(mContext, mId)) {
            return mAdapter.getCount();
        }
        return 0;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public RemoteViews getLoadingView() {
        return null;
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews v = mAdapter.getViewAt(position);
        if (v != null) {
            Intent fillInIntent = new Intent();
            v.setOnClickFillInIntent(org.omnirom.deskclock.R.id.widget_item, fillInIntent);
        }
        return v;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public void onCreate() {
        if (DigitalAppWidgetService.LOGGING) {
            Log.i(TAG, "DigitalWidget onCreate " + mId);
        }
    }

    @Override
    public void onDataSetChanged() {
        mAdapter.loadData(mContext);
        mAdapter.loadCitiesDb(mContext);
        mAdapter.updateHomeLabel(mContext);
    }

    @Override
    public void onDestroy() {
        if (DigitalAppWidgetService.LOGGING) {
            Log.i(TAG, "DigitalWidget onDestroy " + mId);
        }
    }
}

