package com.example.smartstudentid.ui.admin;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.smartstudentid.R;
import com.example.smartstudentid.apdu.ApduClient;
import com.example.smartstudentid.security.AdminSessionManager;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Administrator Authentication Activity.
 * Credentials are verified directly against the central SQLite Database (`smartcard.db`)
 * on the BridgeServer via `POST /api/auth/admin-login` with salted SHA-256 hashes.
 * No hardcoded credentials exist on the client side.
 */
public class AdminLoginActivity extends BaseSecureActivity {

    private EditText etAdminUsername;
    private EditText etAdminPassword;
    private TextView tvAdminLoginError;
    private ProgressBar pbAdminLogin;
    private MaterialButton btnSubmitAdminLogin;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_login);
        applySafeAreaPadding(findViewById(R.id.adminLoginRoot), true, true);

        // If already logged in with an active admin session, go directly to Dashboard
        if (AdminSessionManager.getInstance(this).isLoggedIn()) {
            openDashboardAndFinish();
            return;
        }

        bindViews();
    }

    private void bindViews() {
        etAdminUsername = findViewById(R.id.etAdminUsername);
        etAdminPassword = findViewById(R.id.etAdminPassword);
        tvAdminLoginError = findViewById(R.id.tvAdminLoginError);
        pbAdminLogin = findViewById(R.id.pbAdminLogin);
        btnSubmitAdminLogin = findViewById(R.id.btnSubmitAdminLogin);

        MaterialButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        btnSubmitAdminLogin.setOnClickListener(v -> handleAdminLogin());
    }

    private void handleAdminLogin() {
        String username = etAdminUsername.getText() != null ? etAdminUsername.getText().toString().trim() : "";
        String password = etAdminPassword.getText() != null ? etAdminPassword.getText().toString().trim() : "";

        if (username.isEmpty()) {
            showError("Vui lòng nhập tên đăng nhập quản trị viên");
            etAdminUsername.requestFocus();
            return;
        }

        if (password.isEmpty()) {
            showError("Vui lòng nhập mật khẩu");
            etAdminPassword.requestFocus();
            return;
        }

        setLoading(true);
        hideError();

        executor.execute(() -> {
            ApduClient.AdminAuthResult result = ApduClient.getInstance().authenticateAdmin(username, password);
            mainHandler.post(() -> {
                setLoading(false);
                if (result.success) {
                    AdminSessionManager.getInstance(this).saveAdminSession(
                            result.token,
                            result.username != null ? result.username : username,
                            result.fullName != null ? result.fullName : "Quản trị viên",
                            result.role != null ? result.role : "SUPER_ADMIN"
                    );
                    Toast.makeText(this, "Đăng nhập thành công: " + (result.fullName != null ? result.fullName : username), Toast.LENGTH_SHORT).show();
                    openDashboardAndFinish();
                } else {
                    showError(result.message != null ? result.message : "Đăng nhập thất bại");
                }
            });
        });
    }

    private void openDashboardAndFinish() {
        Intent intent = new Intent(this, AdminDashboardActivity.class);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        pbAdminLogin.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSubmitAdminLogin.setEnabled(!loading);
        btnSubmitAdminLogin.setText(loading ? "Đang xác thực..." : "Đăng nhập Quản trị viên");
    }

    private void showError(String message) {
        tvAdminLoginError.setText(message);
        tvAdminLoginError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvAdminLoginError.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
