package com.example.smartstudentid.ui.student;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.smartstudentid.R;
import com.example.smartstudentid.security.BaseSecureActivity;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen CameraX QR code scanner for scanning the lecturer's rotating attendance QR.
 * Decodes YUV frames locally with ZXing Core without external camera apps or network calls.
 */
public class QrScannerActivity extends BaseSecureActivity {

    public static final String EXTRA_QR_CONTENT = "extra_qr_content";
    public static final String EXTRA_FALLBACK_OTP = "extra_fallback_otp";

    private PreviewView previewViewQr;
    private TextView tvScannerHint;
    private MaterialButton btnScannerTorch;

    private ExecutorService cameraExecutor;
    private Camera activeCamera;
    private boolean torchEnabled = false;
    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private final QRCodeReader qrReader = new QRCodeReader();
    private final Map<DecodeHintType, Object> decodeHints = new EnumMap<>(DecodeHintType.class);

    private ActivityResultLauncher<String> requestCameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

        applySafeAreaPadding(findViewById(R.id.scannerTopBar), true, false);
        applySafeAreaPadding(findViewById(R.id.scannerBottomBar), false, true);

        previewViewQr = findViewById(R.id.previewViewQr);
        tvScannerHint = findViewById(R.id.tvScannerHint);
        btnScannerTorch = findViewById(R.id.btnScannerTorch);

        decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        cameraExecutor = Executors.newSingleThreadExecutor();

        requestCameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        startCamera();
                    } else {
                        tvScannerHint.setText("Chưa cấp quyền Camera. Hãy cấp quyền hoặc bấm nhập mã 6 số dự phòng bên dưới.");
                    }
                }
        );

        findViewById(R.id.btnScannerBack).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnScannerTorch.setOnClickListener(v -> toggleTorch());

        findViewById(R.id.btnScannerUseOtp).setOnClickListener(v -> {
            if (delivered.compareAndSet(false, true)) {
                Intent data = new Intent();
                data.putExtra(EXTRA_FALLBACK_OTP, true);
                setResult(RESULT_OK, data);
                finish();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void toggleTorch() {
        if (activeCamera == null || !activeCamera.getCameraInfo().hasFlashUnit()) {
            return;
        }
        torchEnabled = !torchEnabled;
        activeCamera.getCameraControl().enableTorch(torchEnabled);
        btnScannerTorch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                torchEnabled ? 0xFF2563EB : 0x33FFFFFF));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewViewQr.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImageFrame);

                cameraProvider.unbindAll();
                activeCamera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                );
            } catch (Exception e) {
                tvScannerHint.setText("Không khởi động được camera. Vui lòng dùng mã 6 số dự phòng.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImageFrame(@NonNull ImageProxy image) {
        if (delivered.get()) {
            image.close();
            return;
        }
        try {
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer yBuf = yPlane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();

            byte[] yData = new byte[width * height];
            if (pixelStride == 1 && rowStride == width) {
                yBuf.get(yData, 0, yData.length);
            } else {
                byte[] rowBuf = new byte[rowStride];
                for (int row = 0; row < height; row++) {
                    yBuf.position(row * rowStride);
                    int remaining = Math.min(rowStride, yBuf.remaining());
                    yBuf.get(rowBuf, 0, remaining);
                    int dstOffset = row * width;
                    if (pixelStride == 1) {
                        System.arraycopy(rowBuf, 0, yData, dstOffset, width);
                    } else {
                        for (int col = 0; col < width; col++) {
                            yData[dstOffset + col] = rowBuf[col * pixelStride];
                        }
                    }
                }
            }

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yData, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = qrReader.decode(bitmap, decodeHints);
            if (result != null && result.getText() != null) {
                String text = result.getText().trim();
                if (text.startsWith("ATT|")) {
                    if (delivered.compareAndSet(false, true)) {
                        runOnUiThread(() -> {
                            Intent data = new Intent();
                            data.putExtra(EXTRA_QR_CONTENT, text);
                            setResult(RESULT_OK, data);
                            finish();
                        });
                    }
                } else {
                    runOnUiThread(() -> tvScannerHint.setText("Mã QR không thuộc hệ thống điểm danh lớp học"));
                }
            }
        } catch (ReaderException ignored) {
            // Frame did not contain a decodable QR code
        } catch (Exception ignored) {
        } finally {
            qrReader.reset();
            image.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
    }
}
