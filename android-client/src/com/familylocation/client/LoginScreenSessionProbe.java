package com.familylocation.client;

import android.util.Log;
import org.json.JSONObject;

final class LoginScreenSessionProbe {
    enum Outcome {
        LOGIN_REQUIRED,
        SESSION_RESTORED,
        UPDATE_REQUIRED
    }

    interface Runtime {
        JSONObject getJson(String endpoint) throws Exception;

        boolean hasStoredSession();
    }

    static final class Result {
        final Outcome outcome;
        final JSONObject user;
        final int reportIntervalSeconds;
        final String versionName;
        final String apkUrl;

        private Result(Outcome outcome, JSONObject user, int reportIntervalSeconds, String versionName, String apkUrl) {
            this.outcome = outcome;
            this.user = user;
            this.reportIntervalSeconds = reportIntervalSeconds;
            this.versionName = versionName == null ? "" : versionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
        }

        static Result loginRequired() {
            return new Result(Outcome.LOGIN_REQUIRED, null, 300, "", "");
        }

        static Result sessionRestored(JSONObject user, int reportIntervalSeconds) {
            return new Result(Outcome.SESSION_RESTORED, user, reportIntervalSeconds, "", "");
        }

        static Result updateRequired(String versionName, String apkUrl) {
            return new Result(Outcome.UPDATE_REQUIRED, null, 300, versionName, apkUrl);
        }
    }

    private static final String TAG = "LoginSessionProbe";

    Result run(Runtime runtime, int appVersionCode) {
        Result update = checkRequiredUpdate(runtime, appVersionCode);
        if (update != null) {
            return update;
        }

        if (!runtime.hasStoredSession()) {
            return Result.loginRequired();
        }

        try {
            JSONObject response = runtime.getJson(ApiPaths.ME);
            JSONObject user = response.optJSONObject("user");
            if (user == null) {
                return Result.loginRequired();
            }
            return Result.sessionRestored(user, response.optInt("report_interval_seconds", 300));
        } catch (Exception exception) {
            Log.w(TAG, "Stored session restore failed: " + exception.getMessage());
            return Result.loginRequired();
        }
    }

    private Result checkRequiredUpdate(Runtime runtime, int appVersionCode) {
        try {
            JSONObject update = runtime.getJson(ApiPaths.APP_UPDATE + "?version_code=" + appVersionCode);
            boolean required = update.optBoolean("update_required", false);
            boolean force = update.optBoolean("force_update", true);
            String apkUrl = update.optString("apk_url", "");
            if (required && force && !apkUrl.isEmpty()) {
                return Result.updateRequired(update.optString("latest_version_name", ""), apkUrl);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Update check failed: " + exception.getMessage());
        }
        return null;
    }
}
