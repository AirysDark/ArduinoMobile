package com.airysdark.arduinomobile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.airysdark.arduinomobile.USB_PERMISSION";

    private final BoardChoice[] boards = new BoardChoice[]{
            new BoardChoice("Arduino Uno (ATmega328P)", "arduino:avr:uno", 115200),
            new BoardChoice("Arduino Nano - new bootloader", "arduino:avr:nano:cpu=atmega328", 115200),
            new BoardChoice("Arduino Nano - old bootloader", "arduino:avr:nano:cpu=atmega328old", 57600)
    };

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbSerialDriver pendingDriver;
    private String compiledHex;

    private EditText tokenEdit;
    private EditText sketchEdit;
    private Spinner boardSpinner;
    private TextView deviceText;
    private TextView statusText;
    private Button flashButton;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null && pendingDriver != null) {
                openDriver(pendingDriver);
            } else {
                log("USB permission denied.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        buildUi();
        detectDevice();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Arduino Mobile");
        title.setTextSize(24);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Edit → Compile with GitHub Actions → Flash over USB OTG");
        subtitle.setPadding(0, 0, 0, pad);
        root.addView(subtitle);

        boardSpinner = new Spinner(this);
        String[] labels = new String[boards.length];
        for (int i = 0; i < boards.length; i++) labels[i] = boards[i].label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        boardSpinner.setAdapter(adapter);
        root.addView(boardSpinner, new LinearLayout.LayoutParams(-1, -2));

        deviceText = new TextView(this);
        deviceText.setText("USB device: not connected");
        deviceText.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(deviceText);

        Button detectButton = new Button(this);
        detectButton.setText("Detect USB Arduino");
        detectButton.setOnClickListener(v -> detectDevice());
        root.addView(detectButton);

        tokenEdit = new EditText(this);
        tokenEdit.setHint("GitHub token (Actions read/write)");
        tokenEdit.setSingleLine(true);
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenEdit, new LinearLayout.LayoutParams(-1, -2));

        sketchEdit = new EditText(this);
        sketchEdit.setText("void setup() {\n  pinMode(LED_BUILTIN, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(LED_BUILTIN, HIGH);\n  delay(500);\n  digitalWrite(LED_BUILTIN, LOW);\n  delay(500);\n}\n");
        sketchEdit.setTextSize(14);
        sketchEdit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        sketchEdit.setHorizontallyScrolling(true);
        sketchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        editorParams.topMargin = pad;
        root.addView(sketchEdit, editorParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button compileButton = new Button(this);
        compileButton.setText("Compile");
        compileButton.setOnClickListener(v -> compileSketch());
        actions.addView(compileButton, new LinearLayout.LayoutParams(0, -2, 1f));

        flashButton = new Button(this);
        flashButton.setText("Flash");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> flashFirmware());
        actions.addView(flashButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        statusText = new TextView(this);
        statusText.setText("Ready.");
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, pad, 0, 0);
        root.addView(statusText);

        setContentView(root);
    }

    private void compileSketch() {
        compiledHex = null;
        flashButton.setEnabled(false);
        BoardChoice board = selectedBoard();
        String sketch = sketchEdit.getText().toString();
        String token = tokenEdit.getText().toString();
        log("Submitting " + board.fqbn + " compile...");

        GitHubCompiler.compile(sketch, board.fqbn, token, new GitHubCompiler.Callback() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> log(message));
            }

            @Override
            public void onSuccess(String hexText) {
                compiledHex = hexText;
                runOnUiThread(() -> {
                    log("Compile successful. Firmware ready to flash.");
                    flashButton.setEnabled(true);
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> log("Compile failed: " + error.getMessage()));
            }
        });
    }

    private void detectDevice() {
        closePort();
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            deviceText.setText("USB device: no supported serial device detected");
            log("Connect the board through a USB OTG adapter, then tap Detect.");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        pendingDriver = driver;
        deviceText.setText("USB device: VID " + String.format("%04X", device.getVendorId()) +
                " / PID " + String.format("%04X", device.getProductId()));

        if (usbManager.hasPermission(device)) {
            openDriver(driver);
        } else {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(device, permissionIntent);
            log("Requesting USB permission...");
        }
    }

    private void openDriver(UsbSerialDriver driver) {
        closePort();
        try {
            UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
            if (connection == null) throw new IllegalStateException("Android could not open the USB device.");
            serialPort = driver.getPorts().get(0);
            serialPort.open(connection);
            deviceText.setText(deviceText.getText() + " - connected");
            log("USB serial connection opened.");
        } catch (Exception e) {
            serialPort = null;
            log("USB open failed: " + e.getMessage());
        }
    }

    private void flashFirmware() {
        if (compiledHex == null) {
            log("Compile the sketch first.");
            return;
        }
        if (serialPort == null) {
            log("No Arduino USB serial connection. Detect the board first.");
            detectDevice();
            return;
        }

        BoardChoice board = selectedBoard();
        String hex = compiledHex;
        flashButton.setEnabled(false);
        new Thread(() -> {
            try {
                Stk500Uploader.flash(serialPort, hex, board.baud,
                        (message, percent) -> runOnUiThread(() -> log("[" + percent + "%] " + message)));
                runOnUiThread(() -> log("Flash finished successfully."));
            } catch (Exception e) {
                runOnUiThread(() -> log("Flash failed: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> flashButton.setEnabled(compiledHex != null));
            }
        }, "stk500-uploader").start();
    }

    private BoardChoice selectedBoard() {
        int position = boardSpinner.getSelectedItemPosition();
        if (position < 0 || position >= boards.length) position = 0;
        return boards[position];
    }

    private void log(String message) {
        statusText.setText(message);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void closePort() {
        if (serialPort != null) {
            try { serialPort.close(); } catch (Exception ignored) { }
            serialPort = null;
        }
    }

    @Override
    protected void onDestroy() {
        closePort();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private static final class BoardChoice {
        final String label;
        final String fqbn;
        final int baud;

        BoardChoice(String label, String fqbn, int baud) {
            this.label = label;
            this.fqbn = fqbn;
            this.baud = baud;
        }
    }
}
