package com.example.smartstudentid.security;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages authenticated Administrator session state on the Android client.
 * Tokens and administrator credentials are never hardcoded and are verified
 * against the Server SQLite Database (`admin_accounts` table).
 */
public class AdminSessionManager {

    private static final String PREF_NAME = "smart_id_admin_session";
    private static final String KEY_LOGGED_IN = "admin_logged_in";
    private static final String KEY_TOKEN = "admin_token";
    private static final String KEY_USERNAME = "admin_username";
    private static final String KEY_FULL_NAME = "admin_full_name";
    private static final String KEY_ROLE = "admin_role";
    private static final String KEY_LOGIN_TIME = "admin_login_time";

    private static AdminSessionManager instance;
    private final SharedPreferences prefs;

    private AdminSessionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AdminSessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new AdminSessionManager(context);
        }
        return instance;
    }

    public void saveAdminSession(String token, String username, String fullName, String role) {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_TOKEN, token != null ? token : "")
                .putString(KEY_USERNAME, username != null ? username : "")
                .putString(KEY_FULL_NAME, fullName != null ? fullName : "")
                .putString(KEY_ROLE, role != null ? role : "SUPER_ADMIN")
                .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
                .apply();
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_LOGGED_IN, false);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getFullName() {
        return prefs.getString(KEY_FULL_NAME, "Quản trị viên");
    }

    public String getRole() {
        return prefs.getString(KEY_ROLE, "SUPER_ADMIN");
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "");
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
