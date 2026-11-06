package br.app.conectatudo.v1;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import java.util.List;

/** QR dentro do próprio aplicativo, com permissão solicitada antes de iniciar a câmera. */
public class QrScannerActivity extends Activity {
    private static final int CAMERA_REQUEST = 403;
    private DecoratedBarcodeView scanner;
    private TextView instructions;
    private boolean finishedReading = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(12, 20, 34));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(12, 20, 34));
        instructions = new TextView(this);
        instructions.setText("📷 Aponte a câmera para o QR Code exibido no outro aparelho");
        instructions.setTextColor(Color.WHITE);
        instructions.setTextSize(17);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(22, 28, 22, 28);
        layout.addView(instructions);
        scanner = new DecoratedBarcodeView(this);
        layout.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this);
        cancel.setText("Cancelar leitura");
        cancel.setOnClickListener(v -> finish());
        layout.addView(cancel);
        setContentView(layout);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) requestCamera();
        else instructions.setText("Nenhuma câmera disponível. Use o IP e o PIN na tela principal.");
    }

    private void requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            beginScanning();
        } else {
            instructions.setText("Permita o uso da câmera para ler o QR Code. Ela só será usada nesta tela.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != CAMERA_REQUEST) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) beginScanning();
        else instructions.setText("Câmera não autorizada. Volte e conecte por IP/PIN, ou autorize a câmera nas configurações.");
    }

    private void beginScanning() {
        if (finishedReading) return;
        instructions.setText("📷 Aponte para o código QR — a leitura é automática");
        try {
            scanner.decodeContinuous(new BarcodeCallback() {
                @Override public void barcodeResult(BarcodeResult result) {
                    if (finishedReading || result == null || result.getText() == null) return;
                    String content = result.getText();
                    if (!content.matches("(?i)^conectatudo[0-9]*://connect\\?.*")) {
                        instructions.setText("Este não é um QR do ConectaTudo. Aponte para o QR exibido pelo outro aplicativo.");
                        return;
                    }
                    finishedReading = true;
                    scanner.pause();
                    Intent data = new Intent().putExtra("qr_content", content);
                    setResult(RESULT_OK, data);
                    finish();
                }
                @Override public void possibleResultPoints(List<ResultPoint> points) { }
            });
            scanner.resume();
        } catch (Exception ex) {
            instructions.setText("Não foi possível abrir a câmera: " + ex.getMessage() + ". Use IP e PIN para conectar.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (scanner != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !finishedReading) {
            try { scanner.resume(); } catch (Exception e) { instructions.setText("Falha ao iniciar câmera: " + e.getMessage()); }
        }
    }
    @Override protected void onPause() {
        if (scanner != null) scanner.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        try { if (scanner != null) scanner.pause(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
