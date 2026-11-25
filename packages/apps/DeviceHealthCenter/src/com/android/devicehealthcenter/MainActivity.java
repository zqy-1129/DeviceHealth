/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.devicehealthcenter;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.devicehealth.DeviceHealthDaemonInfo;
import android.devicehealth.DeviceHealthEvent;
import android.devicehealth.DeviceHealthListener;
import android.devicehealth.DeviceHealthManager;
import android.devicehealth.DeviceHealthMetric;
import android.devicehealth.DeviceHealthSnapshot;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_EVENTS = 1;
    private static final int TAB_DIAGNOSTICS = 2;
    private static final long REFRESH_INTERVAL_MS = 2000;
    private static final long PUSH_FALLBACK_INTERVAL_MS = 10_000;

    private static final int FILTER_ALL = -1;
    private static final int FILTER_NORMAL = DeviceHealthSnapshot.SEVERITY_NORMAL;
    private static final int FILTER_WARNING = DeviceHealthSnapshot.SEVERITY_WARNING;
    private static final int FILTER_CRITICAL = DeviceHealthSnapshot.SEVERITY_CRITICAL;

    private static final int HISTORY_SIZE = 60;
    private static final int EVENT_QUERY_LIMIT = 100;

    private static final int CONNECTION_OK = 0;
    private static final int CONNECTION_RECONNECTING = 1;
    private static final int CONNECTION_ERROR = 2;
    private static final int CONNECTION_CONNECTING = 3;

    private static final String STATE_TAB = "tab";
    private static final String STATE_FILTER = "filter";
    private static final String STATE_DEEP_LINK_SHOWN = "deep_link_shown";

    private DeviceHealthManager mManager;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mRefreshing = new AtomicBoolean(false);

    private final ArrayDeque<Float> mTemperatureHistory = new ArrayDeque<>();
    private final ArrayDeque<Float> mMemoryHistory = new ArrayDeque<>();
    private List<DeviceHealthEvent> mLastEvents = Collections.emptyList();
    private DeviceHealthSnapshot mLastSnapshot;
    private DeviceHealthDaemonInfo mLastDaemonInfo;
    private int mEventFilter = FILTER_ALL;
    private int mCurrentTab = TAB_OVERVIEW;
    private int mConnectionState = CONNECTION_CONNECTING;
    private int mRefreshSuccessCount;
    private int mRefreshFailureCount;
    private int mConsecutiveFailures;
    private String mLastFailureMessage = "";
    private String mApexVersion = "";
    private boolean mDiagnosticsActionRunning;

    private TextView mStatusPill;
    private TextView mStatusText;
    private TextView mUpdateText;
    private LinearLayout mThemeButton;
    private ImageView mThemeIcon;
    private TextView mThemeLabel;
    private ImageButton mRefreshButton;

    private TextView mTemperatureText;
    private TextView mTemperatureMeta;
    private TextView mSimulatedBadge;
    private TextView mMemoryText;
    private ProgressBar mMemoryBar;
    private GaugeView mGauge;
    private TextView mTemperatureTrendValue;
    private TextView mMemoryTrendValue;
    private TrendView mTemperatureTrend;
    private TrendView mMemoryTrend;
    private LinearLayout mMetricsContainer;

    private LinearLayout mOverviewContainer;
    private LinearLayout mEventsContainer;
    private LinearLayout mEventsList;
    private TextView mEventsEmpty;
    private TextView mEventsCount;
    private TextView mEventDetailText;
    private TextView mClearEventsButton;

    private LinearLayout mDiagnosticsContainer;
    private ComponentRow mHalRow;
    private ComponentRow mDaemonRow;
    private ComponentRow mFrameworkRow;
    private ComponentRow mAppRow;
    private TextView mApexVersionText;
    private TextView mLastErrorText;
    private TextView mSnapshotAgeText;
    private TextView mRefreshStatsText;
    private TextView mSampleStatsText;
    private TextView mSelfTestResultText;
    private TextView mSelfTestBadge;
    private Button mSelfTestButton;
    private Button mSimulateCriticalButton;
    private Button mClearSimulatedButton;

    private ScrollView mScrollView;
    private View mDeepLinkRow;
    private boolean mDeepLinkDialogShown;
    private TextView mTabOverview;
    private TextView mTabEvents;
    private TextView mTabDiagnostics;
    private TextView mFilterAll;
    private TextView mFilterWarning;
    private TextView mFilterCritical;
    private TextView mFilterRecovery;
    private int mDeepLinkEventId = -1;
    private String mDeepLinkMessage = "";
    private boolean mStarted;
    private boolean mListenerRegistered;
    private boolean mDestroyed;
    private boolean mThemeAnimating;

    private final DeviceHealthListener mHealthListener = new DeviceHealthListener() {
        @Override
        public void onSnapshotChanged(DeviceHealthSnapshot snapshot) {
            mHandler.post(MainActivity.this::refresh);
        }

        @Override
        public void onEvent(DeviceHealthEvent event) {
            mHandler.post(MainActivity.this::refresh);
        }
    };

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed) {
                return;
            }
            refresh();
            if (!mDestroyed) {
                mHandler.postDelayed(this, mListenerRegistered
                        ? PUSH_FALLBACK_INTERVAL_MS : REFRESH_INTERVAL_MS);
            }
        }
    };

    private static final class ComponentRow {
        final View dot;
        final TextView detail;
        final TextView tag;

        ComponentRow(View root) {
            dot = root.findViewById(R.id.statusDot);
            detail = root.findViewById(R.id.statusDetail);
            tag = root.findViewById(R.id.statusTag);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusPill = findViewById(R.id.statusPill);
        mStatusText = findViewById(R.id.statusText);
        mUpdateText = findViewById(R.id.updateText);
        mThemeButton = findViewById(R.id.themeButton);
        mThemeIcon = findViewById(R.id.themeIcon);
        mThemeLabel = findViewById(R.id.themeLabel);
        mRefreshButton = findViewById(R.id.refreshButton);
        mTemperatureText = findViewById(R.id.temperatureText);
        mTemperatureMeta = findViewById(R.id.temperatureMeta);
        mSimulatedBadge = findViewById(R.id.simulatedBadge);
        mMemoryText = findViewById(R.id.memoryText);
        mMemoryBar = findViewById(R.id.memoryBar);
        mGauge = findViewById(R.id.gauge);
        mTemperatureTrendValue = findViewById(R.id.temperatureTrendValue);
        mMemoryTrendValue = findViewById(R.id.memoryTrendValue);
        mTemperatureTrend = findViewById(R.id.temperatureTrend);
        mMemoryTrend = findViewById(R.id.memoryTrend);
        mMetricsContainer = findViewById(R.id.metricsContainer);
        mOverviewContainer = findViewById(R.id.overviewContainer);
        mEventsContainer = findViewById(R.id.eventsContainer);
        mEventsList = findViewById(R.id.eventsList);
        mEventsEmpty = findViewById(R.id.eventsEmpty);
        mEventsCount = findViewById(R.id.eventsCount);
        mEventDetailText = findViewById(R.id.eventDetailText);
        mClearEventsButton = findViewById(R.id.clearEventsButton);
        mDiagnosticsContainer = findViewById(R.id.diagnosticsContainer);
        mApexVersionText = findViewById(R.id.apexVersionText);
        mLastErrorText = findViewById(R.id.lastErrorText);
        mSnapshotAgeText = findViewById(R.id.snapshotAgeText);
        mRefreshStatsText = findViewById(R.id.refreshStatsText);
        mSampleStatsText = findViewById(R.id.sampleStatsText);
        mSelfTestResultText = findViewById(R.id.selfTestResultText);
        mSelfTestBadge = findViewById(R.id.selfTestBadge);
        mSelfTestButton = findViewById(R.id.selfTestButton);
        mSimulateCriticalButton = findViewById(R.id.simulateCriticalButton);
        mClearSimulatedButton = findViewById(R.id.clearSimulatedButton);
        mScrollView = findViewById(R.id.mainScroll);
        mTabOverview = findViewById(R.id.tabOverview);
        mTabEvents = findViewById(R.id.tabEvents);
        mTabDiagnostics = findViewById(R.id.tabDiagnostics);
        mFilterAll = findViewById(R.id.filterAll);
        mFilterWarning = findViewById(R.id.filterWarning);
        mFilterCritical = findViewById(R.id.filterCritical);
        mFilterRecovery = findViewById(R.id.filterRecovery);
        mHalRow = bindComponentRow(R.id.halRow, getString(R.string.component_hal));
        mDaemonRow = bindComponentRow(R.id.daemonRow, getString(R.string.component_daemon));
        mFrameworkRow = bindComponentRow(R.id.frameworkRow,
                getString(R.string.component_framework));
        mAppRow = bindComponentRow(R.id.appRow, getString(R.string.component_app));

        if (savedInstanceState != null) {
            mCurrentTab = savedInstanceState.getInt(STATE_TAB, TAB_OVERVIEW);
            mEventFilter = savedInstanceState.getInt(STATE_FILTER, FILTER_ALL);
        }

        mTabOverview.setOnClickListener(v -> setTab(TAB_OVERVIEW));
        mTabEvents.setOnClickListener(v -> setTab(TAB_EVENTS));
        mTabDiagnostics.setOnClickListener(v -> setTab(TAB_DIAGNOSTICS));
        mFilterAll.setOnClickListener(v -> setEventFilter(FILTER_ALL));
        mFilterWarning.setOnClickListener(v -> setEventFilter(FILTER_WARNING));
        mFilterCritical.setOnClickListener(v -> setEventFilter(FILTER_CRITICAL));
        mFilterRecovery.setOnClickListener(v -> setEventFilter(FILTER_NORMAL));
        mSelfTestButton.setOnClickListener(v -> runSelfTest());
        mSimulateCriticalButton.setOnClickListener(v -> simulateCritical());
        mClearSimulatedButton.setOnClickListener(v -> clearSimulated());
        mClearEventsButton.setOnClickListener(v -> clearEvents());
        mRefreshButton.setOnClickListener(v -> refresh());
        mThemeButton.setOnClickListener(v -> toggleTheme());

        updateThemeButton();
        updateFilterButtons();
        updateConnectionPill();
        setTab(mCurrentTab);
        setSelfTestBadge(getString(R.string.badge_idle),
                getColor(R.color.dh_muted));
        applyPill(mSimulatedBadge, getColor(R.color.dh_amber), 30);

        mExecutor.execute(() -> {
            String version = queryApexVersion();
            runOnUiThread(() -> {
                if (mDestroyed) {
                    return;
                }
                mApexVersion = version;
                if (mApexVersionText != null) {
                    mApexVersionText.setText(version);
                }
            });
        });

        try {
            mManager = getSystemService(DeviceHealthManager.class);
        } catch (RuntimeException e) {
            mManager = null;
        }

        if (mManager == null) {
            mConnectionState = CONNECTION_ERROR;
            updateConnectionPill();
            showError(getString(R.string.error_manager_unavailable));
        } else {
            refresh();
        }
        boolean deepLinkShown = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_DEEP_LINK_SHOWN, false);
        handleDeepLink(getIntent(), deepLinkShown);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mCurrentTab);
        outState.putInt(STATE_FILTER, mEventFilter);
        outState.putBoolean(STATE_DEEP_LINK_SHOWN, mDeepLinkDialogShown);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        mHandler.post(mRefreshRunnable);
        registerHealthListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mThemeAnimating = false;
        if (mThemeIcon != null) {
            mThemeIcon.setRotation(0f);
            mThemeIcon.setScaleX(1f);
            mThemeIcon.setScaleY(1f);
        }
        if (mThemeButton != null) {
            mThemeButton.setScaleX(1f);
            mThemeButton.setScaleY(1f);
        }
    }

    @Override
    protected void onStop() {
        mStarted = false;
        mHandler.removeCallbacks(mRefreshRunnable);
        unregisterHealthListener();
        super.onStop();
    }

    private ComponentRow bindComponentRow(int rootId, String name) {
        View root = findViewById(rootId);
        if (root == null) {
            return null;
        }
        TextView nameView = root.findViewById(R.id.statusName);
        if (nameView != null) {
            nameView.setText(name);
        }
        return new ComponentRow(root);
    }

    private void registerHealthListener() {
        if (mManager == null || mDestroyed || mExecutor.isShutdown()) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                boolean registered;
                try {
                    mManager.registerListener(getMainExecutor(), mHealthListener);
                    registered = true;
                } catch (RuntimeException e) {
                    registered = false;
                }
                boolean finalRegistered = registered;
                runOnUiThread(() -> {
                    if (mDestroyed) {
                        return;
                    }
                    mListenerRegistered = finalRegistered && mStarted;
                    if (finalRegistered && !mStarted) {
                        unregisterHealthListener();
                    }
                    mHandler.removeCallbacks(mRefreshRunnable);
                    mHandler.post(mRefreshRunnable);
                });
            });
        } catch (RejectedExecutionException e) {
            // The activity is shutting down.
        }
    }

    private void unregisterHealthListener() {
        if (mManager == null || mDestroyed || mExecutor.isShutdown()) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                try {
                    mManager.unregisterListener(mHealthListener);
                } catch (RuntimeException e) {
                    // The framework restarted; the registration is already gone.
                }
            });
        } catch (RejectedExecutionException e) {
            // The activity is shutting down.
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        if (mManager != null && mListenerRegistered) {
            try {
                mExecutor.execute(() -> {
                    try {
                        mManager.unregisterListener(mHealthListener);
                    } catch (RuntimeException e) {
                        // Already unregistered or the framework restarted.
                    }
                });
            } catch (RejectedExecutionException e) {
                // The executor is already shutting down.
            }
        }
        mExecutor.shutdown();
        super.onDestroy();
    }

    private void toggleTheme() {
        if (mThemeAnimating) {
            return;
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyTheme();
            return;
        }
        mThemeAnimating = true;

        mThemeButton.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(90)
                .withEndAction(() -> mThemeButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(170)
                        .start())
                .start();

        ObjectAnimator rotation = ObjectAnimator.ofFloat(
                mThemeIcon, View.ROTATION, 0f, 180f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                mThemeIcon, View.SCALE_X, 1f, 1.12f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                mThemeIcon, View.SCALE_Y, 1f, 1.12f, 1f);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(rotation, scaleX, scaleY);
        animatorSet.setDuration(260);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                applyTheme();
            }
        });
        animatorSet.start();
    }

    private void applyTheme() {
        mThemeAnimating = false;
        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        if (uiModeManager == null) {
            return;
        }
        uiModeManager.setApplicationNightMode(isNightMode()
                ? UiModeManager.MODE_NIGHT_NO
                : UiModeManager.MODE_NIGHT_YES);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateThemeButton() {
        if (mThemeIcon == null || mThemeLabel == null) {
            return;
        }
        boolean night = isNightMode();
        mThemeIcon.setImageResource(night
                ? R.drawable.ic_theme_light : R.drawable.ic_theme_dark);
        mThemeLabel.setText(night
                ? R.string.theme_light_short : R.string.theme_dark_short);
        if (mThemeButton != null) {
            mThemeButton.setContentDescription(getString(night
                    ? R.string.switch_to_light : R.string.switch_to_dark));
        }
    }

    private void setTab(int tab) {
        mCurrentTab = tab;
        mOverviewContainer.setVisibility(tab == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
        mEventsContainer.setVisibility(tab == TAB_EVENTS ? View.VISIBLE : View.GONE);
        mDiagnosticsContainer.setVisibility(tab == TAB_DIAGNOSTICS ? View.VISIBLE : View.GONE);

        styleTab(mTabOverview, tab == TAB_OVERVIEW);
        styleTab(mTabEvents, tab == TAB_EVENTS);
        styleTab(mTabDiagnostics, tab == TAB_DIAGNOSTICS);
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active
                ? R.drawable.bg_tab_active : R.drawable.bg_tab_inactive);
        tab.setTextColor(getColor(active ? R.color.dh_teal_text : R.color.dh_sub));
        tab.setElevation(active ? dp(2) : 0f);
    }

    private void setEventFilter(int filter) {
        mEventFilter = filter;
        updateFilterButtons();
        renderEvents(mLastEvents);
        updateEventDetail();
    }

    private void updateFilterButtons() {
        setFilterChipState(mFilterAll, mEventFilter == FILTER_ALL);
        setFilterChipState(mFilterWarning, mEventFilter == FILTER_WARNING);
        setFilterChipState(mFilterCritical, mEventFilter == FILTER_CRITICAL);
        setFilterChipState(mFilterRecovery, mEventFilter == FILTER_NORMAL);
    }

    private void setFilterChipState(TextView chip, boolean active) {
        if (chip == null) {
            return;
        }
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(getColor(active
                ? R.color.dh_on_chip_selected : R.color.dh_sub));
    }

    private void updateConnectionPill() {
        if (mStatusPill == null) {
            return;
        }
        final int textRes;
        final int colorRes;
        switch (mConnectionState) {
            case CONNECTION_RECONNECTING:
                textRes = R.string.connection_reconnecting;
                colorRes = R.color.dh_amber;
                break;
            case CONNECTION_ERROR:
                textRes = R.string.connection_offline;
                colorRes = R.color.dh_red;
                break;
            case CONNECTION_CONNECTING:
                textRes = R.string.connection_connecting;
                colorRes = R.color.dh_muted;
                break;
            default:
                textRes = R.string.connection_live;
                colorRes = R.color.dh_green;
                break;
        }
        final int color = getColor(colorRes);
        mStatusPill.setText(textRes);
        mStatusPill.setTextColor(color);
        applyPill(mStatusPill, color, 26);
        mStatusPill.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private void refresh() {
        if (mDestroyed || mManager == null || mExecutor.isShutdown()
                || !mRefreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                try {
                    DeviceHealthSnapshot snapshot = mManager.getSnapshot();
                    List<DeviceHealthEvent> events = mManager.getRecentEvents(EVENT_QUERY_LIMIT);
                    DeviceHealthDaemonInfo info = mManager.getDaemonInfo();
                    runOnUiThread(() -> {
                        if (mDestroyed) {
                            mRefreshing.set(false);
                            return;
                        }
                        mRefreshing.set(false);
                        mConnectionState = CONNECTION_OK;
                        mConsecutiveFailures = 0;
                        mRefreshSuccessCount++;
                        render(snapshot, events, info);
                    });
                } catch (SecurityException e) {
                    runOnUiThread(() -> {
                        if (mDestroyed) {
                            mRefreshing.set(false);
                            return;
                        }
                        mRefreshing.set(false);
                        mRefreshFailureCount++;
                        mConsecutiveFailures++;
                        mLastFailureMessage = getString(R.string.error_no_permission);
                        mConnectionState = CONNECTION_ERROR;
                        updateConnectionPill();
                        showError(mLastFailureMessage);
                        renderDiagnosticsFromLastState();
                    });
                } catch (RuntimeException e) {
                    runOnUiThread(() -> {
                        if (mDestroyed) {
                            mRefreshing.set(false);
                            return;
                        }
                        mRefreshing.set(false);
                        mRefreshFailureCount++;
                        mConsecutiveFailures++;
                        mLastFailureMessage = getString(R.string.error_read_failed,
                                e.getMessage() == null ? "" : e.getMessage());
                        mConnectionState = mLastSnapshot != null
                                ? CONNECTION_RECONNECTING : CONNECTION_ERROR;
                        updateConnectionPill();
                        if (mConnectionState == CONNECTION_RECONNECTING) {
                            mStatusText.setText(R.string.status_reconnecting);
                            mStatusText.setTextColor(getColor(R.color.dh_amber));
                            mUpdateText.setText("第 " + mConsecutiveFailures + " 次重试 · "
                                    + mLastFailureMessage);
                        } else {
                            showError(mLastFailureMessage);
                        }
                        renderDiagnosticsFromLastState();
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            mRefreshing.set(false);
        }
    }

    private void render(DeviceHealthSnapshot snapshot, List<DeviceHealthEvent> events,
            DeviceHealthDaemonInfo info) {
        final int severity = snapshot.getOverallSeverity();
        mStatusText.setText(severityName(severity));
        mStatusText.setTextColor(severityColor(severity));
        mUpdateText.setText(ageText(snapshot.getTimestampMs(), snapshot.isStale()));
        updateConnectionPill();

        DeviceHealthMetric hottest = null;
        DeviceHealthMetric memory = null;
        for (DeviceHealthMetric metric : snapshot.getMetrics()) {
            if (metric.getId().startsWith("thermal.")
                    && (hottest == null || metric.getValue() > hottest.getValue())) {
                hottest = metric;
            } else if ("memory.pressure".equals(metric.getId())) {
                memory = metric;
            }
        }

        if (hottest != null) {
            mTemperatureText.setText(styledValue(hottest, 0.42f));
            mTemperatureMeta.setText(metricDisplayName(hottest) + " · "
                    + (hottest.isSimulated()
                    ? getString(R.string.simulated_data) : getString(R.string.realtime_data)));
            mSimulatedBadge.setVisibility(hottest.isSimulated() ? View.VISIBLE : View.GONE);
            mGauge.setValue(hottest.getValue(), 100f);
        } else {
            mTemperatureText.setText(R.string.value_dash);
            mTemperatureMeta.setText("");
            mSimulatedBadge.setVisibility(View.GONE);
            mGauge.setValue(0f, 100f);
        }
        mGauge.setState(severityName(severity), severityColor(severity));
        mGauge.setContentDescription("最高温度 "
                + (hottest == null ? getString(R.string.value_dash) : formatValue(hottest))
                + "，总体状态 " + severityName(severity));

        if (memory != null) {
            mMemoryText.setText(styledNumber(
                    String.format(Locale.US, "%.1f", memory.getValue()), "%", 0.62f));
            mMemoryBar.setProgress(Math.round(memory.getValue()));
        } else {
            mMemoryText.setText(R.string.value_dash);
            mMemoryBar.setProgress(0);
        }

        if (hottest != null) {
            addHistory(mTemperatureHistory, hottest.getValue());
        }
        if (memory != null) {
            addHistory(mMemoryHistory, memory.getValue());
        }
        updateTrend(mTemperatureTrend, mTemperatureTrendValue, mTemperatureHistory,
                R.color.dh_series_temp, hottest, "温度");
        updateTrend(mMemoryTrend, mMemoryTrendValue, mMemoryHistory,
                R.color.dh_series_memory, memory, "内存压力");

        renderMetricTiles(snapshot.getMetrics());

        mLastSnapshot = snapshot;
        mLastDaemonInfo = info;
        mLastEvents = events == null ? Collections.emptyList() : events;
        renderEvents(mLastEvents);
        renderDiagnostics(snapshot, info, mLastEvents);

        if (mDeepLinkEventId > 0) {
            for (DeviceHealthEvent event : mLastEvents) {
                if (event.getEventId() == mDeepLinkEventId) {
                    mDeepLinkMessage = event.getMessage();
                    break;
                }
            }
        }
        updateEventDetail();

        if (mDeepLinkEventId > 0 && !mDeepLinkDialogShown) {
            for (DeviceHealthEvent event : mLastEvents) {
                if (event.getEventId() == mDeepLinkEventId) {
                    mDeepLinkDialogShown = true;
                    showEventDetails(event);
                    break;
                }
            }
        }
    }

    private void renderMetricTiles(List<DeviceHealthMetric> metrics) {
        mMetricsContainer.removeAllViews();
        if (metrics == null || metrics.isEmpty()) {
            mMetricsContainer.setVisibility(View.GONE);
            return;
        }
        mMetricsContainer.setVisibility(View.VISIBLE);
        for (int i = 0; i < metrics.size(); i++) {
            DeviceHealthMetric metric = metrics.get(i);
            View tile = getLayoutInflater().inflate(
                    R.layout.item_metric_tile, mMetricsContainer, false);
            TextView name = tile.findViewById(R.id.metricName);
            TextView value = tile.findViewById(R.id.metricValue);
            name.setText(metricDisplayName(metric)
                    + (metric.isSimulated() ? " · " + getString(R.string.simulated) : ""));
            name.setTextColor(getColor(metric.isSimulated()
                    ? R.color.dh_amber : R.color.dh_muted));
            value.setText(formatValue(metric));
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) tile.getLayoutParams();
            params.setMarginEnd(i == metrics.size() - 1 ? 0 : dp(8));
            tile.setLayoutParams(params);
            mMetricsContainer.addView(tile);
        }
    }

    private void addHistory(ArrayDeque<Float> history, float value) {
        history.addLast(value);
        while (history.size() > HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    private void updateTrend(TrendView view, TextView valueView, ArrayDeque<Float> history,
            int colorRes, DeviceHealthMetric latest, String descriptionName) {
        if (view == null) {
            return;
        }
        view.setSeries(new ArrayList<>(history), getColor(colorRes));
        if (valueView != null) {
            valueView.setText(latest == null
                    ? getString(R.string.value_dash) : formatValue(latest));
        }
        view.setContentDescription(descriptionName + "趋势，当前 "
                + (latest == null ? getString(R.string.value_dash) : formatValue(latest))
                + "，共 " + history.size() + " 个采样");
    }

    private void renderDiagnosticsFromLastState() {
        renderDiagnostics(mLastSnapshot, mLastDaemonInfo, mLastEvents);
    }

    private void renderDiagnostics(DeviceHealthSnapshot snapshot, DeviceHealthDaemonInfo info,
            List<DeviceHealthEvent> events) {
        if (mHalRow == null) {
            return;
        }

        int thermalCount = 0;
        boolean allSimulated = true;
        if (snapshot != null) {
            for (DeviceHealthMetric metric : snapshot.getMetrics()) {
                if (metric.getId().startsWith("thermal.")) {
                    thermalCount++;
                    if (!metric.isSimulated()) {
                        allSimulated = false;
                    }
                }
            }
        }
        if (thermalCount == 0) {
            setComponent(mHalRow, getString(R.string.hal_detail_unavailable),
                    getString(R.string.tag_unavailable), getColor(R.color.dh_red));
        } else if (allSimulated) {
            setComponent(mHalRow, getString(R.string.hal_detail_simulated),
                    getString(R.string.tag_simulated), getColor(R.color.dh_amber));
        } else {
            setComponent(mHalRow, getString(R.string.hal_detail_normal),
                    getString(R.string.tag_normal), getColor(R.color.dh_green));
        }

        if (info == null || info.getInterfaceVersion() <= 0) {
            setComponent(mDaemonRow, getString(R.string.daemon_detail_unavailable),
                    getString(R.string.tag_unavailable), getColor(R.color.dh_red));
        } else if (info.getCompatibility() == DeviceHealthDaemonInfo.COMPATIBILITY_TOO_OLD) {
            setComponent(mDaemonRow, getString(R.string.daemon_detail_too_old),
                    getString(R.string.tag_incompatible), getColor(R.color.dh_red));
        } else if (info.getCompatibility() == DeviceHealthDaemonInfo.COMPATIBILITY_NEWER) {
            setComponent(mDaemonRow, getString(R.string.daemon_detail_newer,
                            info.getInterfaceVersion()),
                    getString(R.string.tag_newer), getColor(R.color.dh_amber));
        } else {
            int color = info.getLastError().isEmpty()
                    ? getColor(R.color.dh_green) : getColor(R.color.dh_amber);
            setComponent(mDaemonRow, getString(R.string.daemon_detail_normal,
                            info.getInterfaceVersion(),
                            Integer.toHexString(info.getCapabilities()), info.getPid()),
                    getString(R.string.tag_version, info.getInterfaceVersion()), color);
        }

        if (snapshot == null) {
            setComponent(mFrameworkRow, getString(R.string.framework_detail_waiting),
                    getString(R.string.value_dash), getColor(R.color.dh_muted));
        } else if (snapshot.isStale()) {
            setComponent(mFrameworkRow, getString(R.string.framework_detail_stale),
                    getString(R.string.tag_stale), getColor(R.color.dh_amber));
        } else if (snapshot.getOverallSeverity() == DeviceHealthSnapshot.SEVERITY_UNKNOWN) {
            setComponent(mFrameworkRow, getString(R.string.framework_detail_unknown),
                    getString(R.string.tag_unknown), getColor(R.color.dh_red));
        } else if (snapshot.getOverallSeverity() == DeviceHealthSnapshot.SEVERITY_STALE) {
            setComponent(mFrameworkRow, getString(R.string.framework_detail_expired),
                    getString(R.string.tag_expired), getColor(R.color.dh_amber));
        } else {
            long ageSeconds = Math.max(0,
                    (SystemClock.elapsedRealtime() - snapshot.getTimestampMs()) / 1000);
            setComponent(mFrameworkRow,
                    getString(R.string.framework_detail_normal, ageSeconds),
                    getString(R.string.tag_normal), getColor(R.color.dh_green));
        }

        switch (mConnectionState) {
            case CONNECTION_RECONNECTING:
                setComponent(mAppRow, getString(R.string.app_detail_reconnecting,
                                mConsecutiveFailures, mLastFailureMessage),
                        getString(R.string.tag_reconnecting), getColor(R.color.dh_amber));
                break;
            case CONNECTION_ERROR:
                setComponent(mAppRow, getString(R.string.app_detail_error,
                                mLastFailureMessage),
                        getString(R.string.tag_unavailable), getColor(R.color.dh_red));
                break;
            case CONNECTION_CONNECTING:
                setComponent(mAppRow, getString(R.string.connection_connecting),
                        getString(R.string.value_dash), getColor(R.color.dh_muted));
                break;
            default:
                setComponent(mAppRow, getString(mListenerRegistered
                                ? R.string.app_detail_normal_push
                                : R.string.app_detail_normal_poll,
                                mRefreshSuccessCount),
                        getString(mListenerRegistered
                                ? R.string.tag_monitor : R.string.tag_polling),
                        getColor(R.color.dh_green));
                break;
        }

        String lastError = info != null ? info.getLastError() : "";
        mLastErrorText.setText(lastError.isEmpty()
                ? getString(R.string.value_none) : lastError);
        mLastErrorText.setTextColor(getColor(lastError.isEmpty()
                ? R.color.dh_green : R.color.dh_amber));
        mApexVersionText.setText(mApexVersion.isEmpty()
                ? getString(R.string.value_loading) : mApexVersion);

        long ageSeconds = snapshot == null ? -1
                : Math.max(0, (SystemClock.elapsedRealtime() - snapshot.getTimestampMs()) / 1000);
        mSnapshotAgeText.setText(ageSeconds < 0
                ? getString(R.string.value_dash) : ageSeconds + " 秒前");
        mRefreshStatsText.setText(mRefreshSuccessCount + " / " + mRefreshFailureCount);

        int metricCount = snapshot == null ? 0 : snapshot.getMetrics().size();
        int eventCount = events == null ? 0 : events.size();
        int trendPoints = Math.max(mTemperatureHistory.size(), mMemoryHistory.size());
        mSampleStatsText.setText(getString(R.string.stats_line,
                metricCount, eventCount, trendPoints,
                mRefreshSuccessCount, mRefreshFailureCount));
    }

    private void setComponent(ComponentRow row, String detail, String tag, int color) {
        if (row == null) {
            return;
        }
        row.detail.setText(detail);
        row.tag.setText(tag);
        row.tag.setTextColor(color);
        applyPill(row.tag, color, 28);
        setStatusDot(row.dot, color);
    }

    private void setStatusDot(View dot, int color) {
        if (dot == null) {
            return;
        }
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(withAlpha(color, 44));
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(color);
        LayerDrawable layer = new LayerDrawable(new GradientDrawable[]{outer, inner});
        int inset = dp(3);
        layer.setLayerInset(1, inset, inset, inset, inset);
        dot.setBackground(layer);
    }

    private void applyPill(TextView view, int color, int alpha) {
        if (view == null) {
            return;
        }
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(999));
        background.setColor(withAlpha(color, alpha));
        view.setBackground(background);
    }

    private void setSelfTestBadge(String text, int color) {
        if (mSelfTestBadge == null) {
            return;
        }
        mSelfTestBadge.setText(text);
        mSelfTestBadge.setTextColor(color);
        applyPill(mSelfTestBadge, color, 28);
    }

    private String queryApexVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    "com.android.devicehealth", PackageManager.MATCH_APEX);
            long versionCode = info.getLongVersionCode();
            if (info.versionName == null || info.versionName.isEmpty()) {
                return "versionCode " + versionCode;
            }
            return info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return getString(R.string.value_none);
        }
    }

    private void runSelfTest() {
        if (mManager == null) {
            mSelfTestResultText.setTextColor(getColor(R.color.dh_red));
            mSelfTestResultText.setText("FAIL manager：" + getString(
                    R.string.error_manager_unavailable));
            setSelfTestBadge(getString(R.string.badge_fail), getColor(R.color.dh_red));
            return;
        }
        if (mDiagnosticsActionRunning) {
            return;
        }
        setDiagnosticsBusy(true);
        mSelfTestResultText.setText(R.string.self_test_running);
        mSelfTestResultText.setTextColor(getColor(R.color.dh_muted));
        submit(() -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = true;

            boolean permission = checkSelfPermission(Manifest.permission.MANAGE_DEVICE_HEALTH)
                    == PackageManager.PERMISSION_GRANTED;
            appendCheck(sb, permission, "权限 MANAGE_DEVICE_HEALTH");
            ok = ok && permission;

            DeviceHealthSnapshot snapshot = null;
            DeviceHealthDaemonInfo info = null;
            try {
                snapshot = mManager.getSnapshot();
                info = mManager.getDaemonInfo();
                mManager.getRecentEvents(1);
                appendCheck(sb, true, "framework API 可访问");
            } catch (RuntimeException e) {
                appendCheck(sb, false, "framework API 访问失败: " + e.getMessage());
                ok = false;
            }

            if (snapshot != null) {
                boolean hasMetrics = !snapshot.getMetrics().isEmpty();
                appendCheck(sb, hasMetrics,
                        "快照指标 " + snapshot.getMetrics().size() + " 个");
                ok = ok && hasMetrics;
                boolean hasThermal = false;
                for (DeviceHealthMetric metric : snapshot.getMetrics()) {
                    if (metric.getId().startsWith("thermal.")) {
                        hasThermal = true;
                        break;
                    }
                }
                appendCheck(sb, hasThermal, "thermal 数据");
                ok = ok && hasThermal;
            } else {
                appendCheck(sb, false, "快照不可用");
                ok = false;
            }

            boolean daemonOk = info != null && info.getInterfaceVersion() > 0;
            appendCheck(sb, daemonOk, "daemon 接口版本 "
                    + (info != null ? info.getInterfaceVersion() : 0));
            ok = ok && daemonOk;

            if (info != null) {
                boolean compatible =
                        info.getCompatibility() != DeviceHealthDaemonInfo.COMPATIBILITY_TOO_OLD;
                appendCheck(sb, compatible,
                        "daemon 兼容性：" + compatibilityName(info.getCompatibility()));
                ok = ok && compatible;
            }

            String apex = queryApexVersion();
            boolean apexOk = !getString(R.string.value_none).equals(apex);
            appendCheck(sb, apexOk, "APEX 版本 " + apex);
            ok = ok && apexOk;

            boolean finalOk = ok;
            String result = sb.toString()
                    + (finalOk ? "SELF-TEST PASS" : "SELF-TEST FAIL");
            runOnUiThread(() -> {
                if (mDestroyed) {
                    return;
                }
                setDiagnosticsBusy(false);
                mSelfTestResultText.setText(result);
                mSelfTestResultText.setTextColor(getColor(finalOk
                        ? R.color.dh_green : R.color.dh_red));
                setSelfTestBadge(getString(finalOk
                                ? R.string.badge_pass : R.string.badge_fail),
                        getColor(finalOk ? R.color.dh_green : R.color.dh_red));
            });
        });
    }

    private void appendCheck(StringBuilder sb, boolean pass, String message) {
        sb.append(pass ? "PASS " : "FAIL ").append(message).append('\n');
    }

    private void simulateCritical() {
        runDiagnosticsAction(() -> {
            mManager.simulateCritical("thermal.cpu");
            return "已注入 thermal.cpu 严重模拟值，约 10 秒后进入 CRITICAL";
        });
    }

    private void clearSimulated() {
        runDiagnosticsAction(() -> {
            mManager.clearSimulated();
            return "已清除模拟值，策略将在后续采样中恢复";
        });
    }

    private void clearEvents() {
        if (mManager == null) {
            return;
        }
        submit(() -> {
            try {
                mManager.clearEvents();
                runOnUiThread(() -> {
                    if (mDestroyed) {
                        return;
                    }
                    mDeepLinkEventId = -1;
                    mDeepLinkMessage = "";
                    mDeepLinkDialogShown = false;
                    mDeepLinkRow = null;
                    updateEventDetail();
                    refresh();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (mDestroyed) {
                        return;
                    }
                    Toast.makeText(this, "清空失败：" + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private interface DiagnosticsAction {
        String run() throws Exception;
    }

    private void runDiagnosticsAction(DiagnosticsAction action) {
        if (mManager == null) {
            mSelfTestResultText.setTextColor(getColor(R.color.dh_red));
            mSelfTestResultText.setText(getString(R.string.error_manager_unavailable));
            setSelfTestBadge(getString(R.string.badge_failed), getColor(R.color.dh_red));
            return;
        }
        if (mDiagnosticsActionRunning) {
            return;
        }
        setDiagnosticsBusy(true);
        mSelfTestResultText.setText(R.string.self_test_running);
        mSelfTestResultText.setTextColor(getColor(R.color.dh_muted));
        submit(() -> {
            String message;
            boolean success;
            try {
                message = action.run();
                success = true;
            } catch (SecurityException e) {
                message = "模拟仅在 userdebug 构建可用";
                success = false;
            } catch (Exception e) {
                message = "操作失败：" + e.getMessage();
                success = false;
            }
            boolean finalSuccess = success;
            String finalMessage = message;
            runOnUiThread(() -> {
                if (mDestroyed) {
                    return;
                }
                setDiagnosticsBusy(false);
                mSelfTestResultText.setText(finalMessage);
                mSelfTestResultText.setTextColor(getColor(finalSuccess
                        ? R.color.dh_teal_text : R.color.dh_red));
                setSelfTestBadge(getString(finalSuccess
                                ? R.string.badge_done : R.string.badge_failed),
                        getColor(finalSuccess ? R.color.dh_teal : R.color.dh_red));
                refresh();
            });
        });
    }

    private void setDiagnosticsBusy(boolean busy) {
        mDiagnosticsActionRunning = busy;
        setButtonEnabled(mSelfTestButton, !busy);
        setButtonEnabled(mSimulateCriticalButton, !busy);
        setButtonEnabled(mClearSimulatedButton, !busy);
        if (busy) {
            setSelfTestBadge(getString(R.string.badge_running),
                    getColor(R.color.dh_muted));
        }
    }

    private void setButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void renderEvents(List<DeviceHealthEvent> events) {
        if (mEventsList == null) {
            return;
        }
        mEventsList.removeAllViews();
        mDeepLinkRow = null;
        if (mEventsCount != null) {
            mEventsCount.setText(getString(R.string.events_count, events.size()));
        }
        int shown = 0;
        for (DeviceHealthEvent event : events) {
            if (mEventFilter != FILTER_ALL && event.getToSeverity() != mEventFilter) {
                continue;
            }
            shown++;
            View row = createEventRow(event);
            if (event.getEventId() == mDeepLinkEventId) {
                row.setBackgroundResource(R.drawable.bg_event_card_highlight);
                mDeepLinkRow = row;
            }
            mEventsList.addView(row);
        }

        boolean empty = shown == 0;
        mEventsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mEventsList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            mEventsEmpty.setText(events.isEmpty()
                    ? R.string.no_events : R.string.no_matching_events);
        }

        if (mDeepLinkRow != null && mScrollView != null) {
            final View target = mDeepLinkRow;
            mEventsContainer.post(() -> {
                android.graphics.Rect rect = new android.graphics.Rect();
                target.getDrawingRect(rect);
                mScrollView.offsetDescendantRectToMyCoords(target, rect);
                mScrollView.smoothScrollTo(0, Math.max(0, rect.top - dp(16)));
            });
        }
    }

    private View createEventRow(DeviceHealthEvent event) {
        View row = getLayoutInflater().inflate(R.layout.item_event, mEventsList, false);
        final int severity = event.getToSeverity();
        final int color = severityColor(severity);

        View rail = row.findViewById(R.id.eventRail);
        if (rail != null) {
            rail.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        ImageView icon = row.findViewById(R.id.eventIcon);
        if (icon != null) {
            icon.setImageResource(severity == DeviceHealthSnapshot.SEVERITY_NORMAL
                    ? R.drawable.ic_event_recovery : R.drawable.ic_event_warning);
            icon.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable iconBackground = new GradientDrawable();
            iconBackground.setShape(GradientDrawable.RECTANGLE);
            iconBackground.setCornerRadius(dp(10));
            iconBackground.setColor(withAlpha(color, 26));
            icon.setBackground(iconBackground);
        }

        TextView severityView = row.findViewById(R.id.eventSeverity);
        severityView.setText(eventSeverityName(severity));
        severityView.setTextColor(color);
        applyPill(severityView, color, 28);

        TextView idView = row.findViewById(R.id.eventId);
        idView.setText("#" + event.getEventId());

        TextView timeView = row.findViewById(R.id.eventTime);
        timeView.setText(formatEventAge(event.getTimestampMs()));

        TextView metricView = row.findViewById(R.id.eventMetric);
        metricView.setText(event.getMetricId());

        TextView messageView = row.findViewById(R.id.eventMessage);
        messageView.setText(event.getMessage());

        row.setOnClickListener(v -> showEventDetails(event));
        row.setContentDescription("事件 #" + event.getEventId() + "，"
                + eventSeverityName(severity) + "，" + event.getMetricId());
        return row;
    }

    private String eventSeverityName(int severity) {
        if (severity == DeviceHealthSnapshot.SEVERITY_NORMAL) {
            return getString(R.string.filter_recovery);
        }
        return severityName(severity);
    }

    private String formatEventAge(long timestampMs) {
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - timestampMs) / 1000);
        if (seconds < 10) {
            return getString(R.string.event_age_just_now);
        }
        if (seconds < 60) {
            return getString(R.string.event_age_seconds, seconds);
        }
        if (seconds < 3600) {
            return getString(R.string.event_age_minutes, seconds / 60);
        }
        return getString(R.string.event_age_hours, seconds / 3600);
    }

    private void showEventDetails(DeviceHealthEvent event) {
        long ageSeconds = Math.max(0,
                (SystemClock.elapsedRealtime() - event.getTimestampMs()) / 1000);
        String body = getString(R.string.event_details_body,
                event.getEventId(),
                event.getMetricId(),
                severityName(event.getFromSeverity()),
                severityName(event.getToSeverity()),
                event.getValue(),
                event.getThreshold(),
                ageSeconds,
                event.getMessage());
        new AlertDialog.Builder(this)
                .setTitle(R.string.event_details_title)
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void handleDeepLink(Intent intent, boolean alreadyShown) {
        if (intent == null) {
            return;
        }
        int eventId = intent.getIntExtra("event_id", -1);
        if (eventId <= 0) {
            return;
        }
        mDeepLinkEventId = eventId;
        String message = intent.getStringExtra("event_message");
        mDeepLinkMessage = message != null ? message : ("事件 #" + eventId);
        mDeepLinkDialogShown = alreadyShown;
        mDeepLinkRow = null;
        if (mEventFilter != FILTER_ALL) {
            setEventFilter(FILTER_ALL);
        }
        setTab(TAB_EVENTS);
        updateEventDetail();
        refresh();
    }

    private void updateEventDetail() {
        if (mEventDetailText == null) {
            return;
        }
        if (mDeepLinkEventId <= 0) {
            mEventDetailText.setVisibility(View.GONE);
            return;
        }
        mEventDetailText.setVisibility(View.VISIBLE);
        mEventDetailText.setTextColor(getColor(R.color.dh_red));
        mEventDetailText.setText(getString(R.string.deep_link_event, mDeepLinkEventId)
                + "\n" + mDeepLinkMessage);
    }

    private void showError(String message) {
        mStatusText.setText(R.string.status_unavailable);
        mStatusText.setTextColor(getColor(R.color.dh_red));
        mUpdateText.setText(message);
        mTemperatureText.setText(R.string.value_dash);
        mTemperatureMeta.setText("");
        mSimulatedBadge.setVisibility(View.GONE);
        mGauge.setValue(0f, 100f);
        mGauge.setState(getString(R.string.value_dash), getColor(R.color.dh_muted));
        mMemoryText.setText(R.string.value_dash);
        mMemoryBar.setProgress(0);
        mMetricsContainer.removeAllViews();
        mMetricsContainer.setVisibility(View.GONE);
    }

    private String ageText(long timestampMs, boolean stale) {
        long ageSeconds = Math.max(0, (SystemClock.elapsedRealtime() - timestampMs) / 1000);
        return String.format(Locale.US, "更新于 %d 秒前%s", ageSeconds, stale ? " · 缓存" : "");
    }

    private CharSequence styledValue(DeviceHealthMetric metric, float unitScale) {
        final String number = String.format(Locale.US, "%.1f", metric.getValue());
        String unit = "";
        if ("C".equals(metric.getUnit())) {
            unit = "°C";
        } else if ("percent".equals(metric.getUnit())) {
            unit = "%";
        } else if (metric.getUnit() != null && !metric.getUnit().isEmpty()) {
            unit = " " + metric.getUnit();
        }
        return styledNumber(number, unit, unitScale);
    }

    private CharSequence styledNumber(String number, String unit, float unitScale) {
        SpannableString text = new SpannableString(number + unit);
        if (!unit.isEmpty()) {
            int start = number.length();
            text.setSpan(new RelativeSizeSpan(unitScale), start, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(getColor(R.color.dh_sub)),
                    start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private String metricDisplayName(DeviceHealthMetric metric) {
        if ("memory.pressure".equals(metric.getId())) {
            return getString(R.string.metric_memory);
        }
        if (metric.getName() != null && !metric.getName().isEmpty()) {
            return metric.getName();
        }
        return metric.getId();
    }

    private static String formatValue(DeviceHealthMetric metric) {
        if ("C".equals(metric.getUnit())) {
            return String.format(Locale.US, "%.1f°C", metric.getValue());
        }
        if ("percent".equals(metric.getUnit())) {
            return String.format(Locale.US, "%.1f%%", metric.getValue());
        }
        return String.format(Locale.US, "%.1f %s", metric.getValue(), metric.getUnit());
    }

    private static String compatibilityName(int compatibility) {
        switch (compatibility) {
            case DeviceHealthDaemonInfo.COMPATIBILITY_OK:
                return "兼容";
            case DeviceHealthDaemonInfo.COMPATIBILITY_TOO_OLD:
                return "版本过低（降级模式）";
            case DeviceHealthDaemonInfo.COMPATIBILITY_NEWER:
                return "较新（已忽略未知能力）";
            default:
                return "不可用";
        }
    }

    private String severityName(int severity) {
        switch (severity) {
            case DeviceHealthSnapshot.SEVERITY_NORMAL:
                return getString(R.string.severity_normal);
            case DeviceHealthSnapshot.SEVERITY_WARNING:
                return getString(R.string.severity_warning);
            case DeviceHealthSnapshot.SEVERITY_CRITICAL:
                return getString(R.string.severity_critical);
            case DeviceHealthSnapshot.SEVERITY_STALE:
                return getString(R.string.severity_stale);
            default:
                return getString(R.string.severity_unknown);
        }
    }

    private int severityColor(int severity) {
        switch (severity) {
            case DeviceHealthSnapshot.SEVERITY_NORMAL:
                return getColor(R.color.dh_green);
            case DeviceHealthSnapshot.SEVERITY_WARNING:
                return getColor(R.color.dh_amber);
            case DeviceHealthSnapshot.SEVERITY_CRITICAL:
                return getColor(R.color.dh_red);
            case DeviceHealthSnapshot.SEVERITY_STALE:
                return getColor(R.color.dh_amber);
            case DeviceHealthSnapshot.SEVERITY_UNKNOWN:
                return getColor(R.color.dh_red);
            default:
                return getColor(R.color.dh_muted);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private boolean submit(Runnable task) {
        if (mDestroyed || mExecutor.isShutdown()) {
            return false;
        }
        try {
            mExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
