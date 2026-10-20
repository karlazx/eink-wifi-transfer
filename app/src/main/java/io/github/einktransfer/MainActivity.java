package io.github.einktransfer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private TransferServer server;
    private TextView state, address, summary;
    private ImageView qr;
    private Button toggle;
    private boolean visible, wanted = true;
    private String servingIp;
    private File directory;
    private final BroadcastReceiver network = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (visible && !Objects.equals(servingIp, wifiIp())) { stop(); if (wanted) start(); }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        directory = new File(Environment.getExternalStorageDirectory(), "EInkTransfer");
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24)); body.setBackgroundColor(Color.WHITE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        TextView title = text("墨屏传文件", 30); title.setTypeface(null, Typeface.BOLD); body.addView(title);
        body.addView(text("同一 Wi-Fi · 浏览器直传 · 所有文件类型", 17));
        state = text("正在准备服务…", 21); body.addView(state);
        address = text("", 23); address.setTextIsSelectable(true); body.addView(address);
        qr = new ImageView(this); qr.setContentDescription("用手机扫描此二维码打开传输网页");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(230), dp(230));
        imageParams.gravity = Gravity.CENTER_HORIZONTAL; imageParams.setMargins(0, dp(12), 0, dp(12)); body.addView(qr, imageParams);
        toggle = button("停止服务", () -> { if (server == null) { wanted = true; start(); } else { wanted = false; stop(); } }); body.addView(toggle);
        body.addView(button("查看接收文件", this::showFiles));
        body.addView(button("刷新连接状态", () -> { stop(); wanted = true; start(); }));
        body.addView(button("开源许可", this::showLicenses));
        summary = text("", 17); body.addView(summary);
        body.addView(text("保存位置：共享存储 / EInkTransfer\n单文件上限 2 GiB；同名文件不覆盖。\n\n请保持此界面打开，切换应用会停止传输。\n仅在可信局域网使用：服务开启期间，同网用户可上传、下载和删除接收目录中的文件。\n\n只负责传输，不自动打开文件或安装应用。", 16));
        registerReceiver(network, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int sp) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextColor(Color.BLACK); b.setTextSize(18);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private String wifiIp() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (n == null || !n.isConnected()) return null;
        WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress(); if (ip == 0) return null;
        return String.format(Locale.ROOT, "%d.%d.%d.%d", ip & 255, (ip >> 8) & 255, (ip >> 16) & 255, (ip >> 24) & 255);
    }
    private void start() {
        if (!visible || server != null) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            state.setText("需要存储权限；点击启动服务授权"); toggle.setText("授权并启动服务");
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1); return;
        }
        String ip = wifiIp();
        if (ip == null) { state.setText("请连接 Wi-Fi 后重试"); toggle.setText("重试启动"); return; }
        try {
            server = new TransferServer(directory, ip, 8080, path -> getAssets().open("web/" + path),
                () -> runOnUiThread(this::updateSummary));
            servingIp = ip; String url = "http://" + ip + ":8080";
            address.setText(url); qr.setImageBitmap(makeQr(url)); qr.setVisibility(View.VISIBLE);
            state.setText("服务已开启，请在同一 Wi-Fi 的浏览器访问"); toggle.setText("停止服务");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateSummary();
        } catch (Exception e) {
            stop(); state.setText("启动失败：请检查端口占用和存储权限\n" + e.getMessage()); toggle.setText("重试启动");
        }
    }
    private Bitmap makeQr(String url) throws WriterException {
        BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 460, 460);
        Bitmap b = Bitmap.createBitmap(460, 460, Bitmap.Config.RGB_565);
        int[] pixels = new int[460 * 460];
        for (int y = 0; y < 460; y++) for (int x = 0; x < 460; x++) pixels[y * 460 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        b.setPixels(pixels, 0, 460, 0, 0, 460, 460); return b;
    }
    private void stop() {
        if (server != null) { server.close(); server = null; }
        servingIp = null;
        if (state != null) {
            state.setText("服务已停止"); address.setText(""); qr.setImageDrawable(null); qr.setVisibility(View.GONE); toggle.setText("启动服务");
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void updateSummary() {
        if (server == null) return;
        try { summary.setText("已接收 " + new JSONArray(server.fileList()).length() + " 个文件"); }
        catch (Exception e) { summary.setText("目录读取失败，请检查存储空间"); }
    }
    private void showLicenses() {
        TextView content = text("", 13); content.setTextIsSelectable(true); content.setPadding(dp(16), dp(8), dp(16), dp(8));
        StringBuilder b = new StringBuilder("墨屏传文件 · MIT\n二维码组件：ZXing 3.5.3 · ZXing Authors · Apache License 2.0\n\n");
        try {
            for (String file : new String[]{"MIT.txt", "ZXing-LICENSE.txt", "ZXing-NOTICE.txt"}) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("licenses/" + file), "UTF-8"))) {
                    String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
                }
                b.append("\n\n");
            }
        } catch (IOException e) { b.append("许可证读取失败，请查看项目源码中的 LICENSE 与 third_party 目录。"); }
        content.setText(b); ScrollView view = new ScrollView(this); view.addView(content);
        new AlertDialog.Builder(this).setTitle("开源许可").setView(view).setPositiveButton("返回", null).show();
    }
    private void showFiles() {
        final TextView content = text("", 17);
        Runnable refresh = () -> {
            try {
                if (server == null) { content.setText("请先启动服务，再查看接收文件。\n保存位置：共享存储 / EInkTransfer"); return; }
                JSONArray files = new JSONArray(server.fileList()); StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(files.length(), 500); i++) {
                    JSONObject f = files.getJSONObject(i); b.append(f.getString("name")).append("\n").append(f.getLong("size")).append(" 字节\n\n");
                }
                if (files.length() > 500) b.append("仅显示前 500 项，完整列表请在浏览器查看。");
                content.setText(files.length() == 0 ? "还没有接收文件，打开浏览器开始上传。" : b.toString());
            } catch (Exception e) { content.setText("读取失败，请检查存储后重试。"); }
        };
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView list = new ScrollView(this); list.addView(content); refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("接收文件 · EInkTransfer").setView(list)
            .setPositiveButton("返回传输", null).setNeutralButton("刷新", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh.run())); dialog.show();
    }
    @Override protected void onStart() { super.onStart(); visible = true; if (wanted) start(); }
    @Override protected void onStop() { visible = false; stop(); super.onStop(); }
    @Override protected void onDestroy() { unregisterReceiver(network); stop(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length > 0 && results[results.length - 1] == PackageManager.PERMISSION_GRANTED) start();
        else { state.setText("未获得存储权限，请点击授权重试；若已永久拒绝，请到系统设置授权。"); toggle.setText("授权并启动服务"); }
    }
    @Override public void onBackPressed() {
        if (server == null) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle("关闭服务并退出？").setMessage("未完成的传输将中断，已完成文件保留。")
            .setNegativeButton("取消", null).setPositiveButton("关闭并退出", (d, w) -> { stop(); finish(); }).show();
    }
}
