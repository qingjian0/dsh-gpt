package com.gpt.hook.hooks;

import com.gpt.hook.DynHook;
import com.gpt.hook.XLog;

import de.robv.android.xposed.XC_MethodHook;

/** 分析 / 崩溃 / AB 实验上报阻断：匹配到的 SDK 方法一律置空 */
public final class Telemetry {

    private static final String[][] T = {
            {"com.google.firebase.analytics.FirebaseAnalytics", "logEvent"},
            {"com.google.firebase.analytics.FirebaseAnalytics", "setUserProperty"},
            {"com.google.firebase.crashlytics.FirebaseCrashlytics", "recordException"},
            {"com.google.firebase.crashlytics.FirebaseCrashlytics", "log"},
            {"com.google.firebase.crashlytics.FirebaseCrashlytics", "sendUnsentReports"},
            {"com.statsig.androidsdk.StatsigClient", "logEvent"},
            {"com.statsig.androidsdk.StatsigClient", "flush"},
            {"com.amplitude.api.Amplitude", "logEvent"},
            {"com.segment.analytics.Analytics", "track"},
            {"com.segment.analytics.Analytics", "screen"},
            {"com.segment.analytics.Analytics", "identify"},
            {"io.sentry.Sentry", "captureException"},
            {"io.sentry.Sentry", "captureMessage"},
            {"io.sentry.Sentry", "captureEvent"},
            {"com.mixpanel.android.mpmetrics.MixpanelAPI", "track"},
            {"com.braze.Braze", "logCustomEvent"},
            {"com.appsflyer.AppsFlyerLib", "logEvent"},
            {"com.adjust.sdk.Adjust", "trackEvent"},
            {"com.facebook.appevents.AppEventsLogger", "logEvent"},
            {"io.branch.referral.Branch", "userCompletedAction"},
            {"com.google.android.gms.measurement.AppMeasurement", "logEvent"},
            {"com.newrelic.agent.android.NewRelic", "recordCustomEvent"},
            {"com.datadog.android.rum.RumMonitor", "startView"},
    };

    public static void init(ClassLoader cl) {
        XC_MethodHook noop = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                XLog.i("[tlm] block " + (p.method == null ? "?" : p.method.getName()));
                p.setResult(null);
            }
        };
        int n = 0;
        for (String[] t : T) {
            Class<?> c = DynHook.cls(t[0], cl);
            if (c == null) continue;
            com.gpt.hook.DynHook.hookAll(c, t[1], noop);
            n++;
            XLog.i("tlm hook " + t[0] + "." + t[1]);
        }
        XLog.i("telemetry blocked classes=" + n);
    }
}
