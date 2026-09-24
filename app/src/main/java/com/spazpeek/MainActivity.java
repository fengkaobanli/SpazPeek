package com.spazpeek;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * SpazPeek — 空间音频链路取证（Material 3 版）
 * 数据层：把 dumpsys 原文提炼成「结论 + 指标行 + track 行」，原文降级为可折叠附录；
 * 视图层：M3 语义色 + 卡片流 + 状态 chip。
 */
public class MainActivity extends AppCompatActivity {

    private static final String NL = "\n";
    private static final String KV = " :: ";
    private static final int REQ = ShizukuShell.REQUEST_CODE;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout reportContainer;
    private Chip chipStatus;
    private TextView tvTimestamp;
    private LinearProgressIndicator progress;
    private MaterialCardView cardEmpty;
    private MaterialButton btnGrant, btnRefresh;

    private String lastReport = "";
    private List<Kv> lastOverview = new ArrayList<>();
    private List<Tk> lastTracks = new ArrayList<>();
    private List<Kv> lastKvs = new ArrayList<>();
    private State lastState = State.IDLE;
    private boolean autoOn = false, busy = false;

    private enum State { OK, WARN, BAD, IDLE }

    private final Runnable autoLoop = new Runnable() {
        @Override public void run() {
            if (!autoOn) return;
            refresh();
            ui.postDelayed(this, 3000);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQ) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        cardEmpty.setVisibility(View.GONE);
                        setStatus("已授权 · 身份 = shell(uid 2000)", State.OK, false);
                        refresh();
                    } else {
                        setStatus(getString(R.string.status_denied), State.BAD, false);
                    }
                }
            };

    // ─────────────────────────────────────────────────────────── lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivitiesIfAvailable(getApplication());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        reportContainer = findViewById(R.id.reportContainer);
        chipStatus = findViewById(R.id.chipStatus);
        tvTimestamp = findViewById(R.id.tvTimestamp);
        progress = findViewById(R.id.progress);
        cardEmpty = findViewById(R.id.cardEmpty);
        btnGrant = findViewById(R.id.btnGrant);
        btnRefresh = findViewById(R.id.btnRefresh);
        MaterialSwitch swAuto = findViewById(R.id.swAuto);

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        btnGrant.setOnClickListener(v -> {
            if (!ShizukuShell.binderAlive()) {
                setStatus("Shizuku 服务未运行：先在 Shizuku App 里启动（root / adb 均可）", State.WARN, false);
                return;
            }
            if (ShizukuShell.hasPermission()) {
                setStatus("已授权，直接刷新", State.OK, false);
                refresh();
            } else {
                ShizukuShell.requestPermission();
            }
        });

        btnRefresh.setOnClickListener(v -> refresh());

        swAuto.setOnCheckedChangeListener((v, on) -> {
            autoOn = on;
            if (on) ui.post(autoLoop); else ui.removeCallbacks(autoLoop);
        });

        ((MaterialButton) findViewById(R.id.btnCopy)).setOnClickListener(v -> {
            if (lastReport.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("SpazPeek", lastReport));
            Snackbar.make(findViewById(android.R.id.content), R.string.toast_copied, Snackbar.LENGTH_SHORT).show();
        });

        ((MaterialButton) findViewById(R.id.btnExport)).setOnClickListener(v -> {
            if (lastReport.isEmpty()) return;
            final String data = lastReport;
            io.execute(() -> {
                String name = "spazpeek_" + new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                File f = new File(getExternalFilesDir(null), name);
                String msg;
                try {
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(data.getBytes("UTF-8"));
                    fos.close();
                    msg = getString(R.string.toast_exported, f.getAbsolutePath());
                } catch (Throwable t) {
                    msg = getString(R.string.toast_export_failed, String.valueOf(t.getMessage()));
                }
                final String m = msg;
                ui.post(() -> Snackbar.make(findViewById(android.R.id.content), m, Snackbar.LENGTH_LONG).show());
            });
        });

        Shizuku.addRequestPermissionResultListener(permListener);

        if (ShizukuShell.hasPermission()) {
            cardEmpty.setVisibility(View.GONE);
            refresh();
        } else {
            setStatus(getString(R.string.status_unauthorized) + " · 正在拉起授权弹窗…", State.WARN, false);
            // 服务端没给授权：直接把 Shizuku 授权弹窗拉起来
            ShizukuShell.requestPermission();
        }
    }

    @Override
    protected void onDestroy() {
        autoOn = false;
        ui.removeCallbacks(autoLoop);
        try { Shizuku.removeRequestPermissionResultListener(permListener); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────── status

    private void setStatus(String s, State state, boolean busyNow) {
        chipStatus.setText(s);
        applyChipState(chipStatus, state);
        tvTimestamp.setText(s);
        progress.setVisibility(busyNow ? View.VISIBLE : View.GONE);
        btnRefresh.setEnabled(!busyNow);
        btnGrant.setEnabled(!busyNow);
    }

    private int colorOf(State state) {
        switch (state) {
            case OK:   return R.color.state_ok;
            case WARN: return R.color.state_warn;
            case BAD:  return R.color.state_bad;
            default:   return R.color.state_idle;
        }
    }

    private void applyChipState(Chip chip, State state) {
        int fg, bg, icon;
        switch (state) {
            case OK:   fg = R.color.state_ok;   bg = R.color.state_ok_container;   icon = R.drawable.ic_ok;    break;
            case WARN: fg = R.color.state_warn; bg = R.color.state_warn_container; icon = R.drawable.ic_warn;  break;
            case BAD:  fg = R.color.state_bad;  bg = R.color.state_bad_container;  icon = R.drawable.ic_error; break;
            default:   fg = R.color.state_idle; bg = R.color.state_idle_container; icon = R.drawable.ic_auto;  break;
        }
        int fgColor = ContextCompat.getColor(this, fg);
        chip.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(this, bg)));
        chip.setTextColor(fgColor);
        chip.setChipIcon(ContextCompat.getDrawable(this, icon));
        chip.setChipIconTint(ColorStateList.valueOf(fgColor));
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    // ────────────────────────────────────────────────────────────── refresh

    private void refresh() {
        if (busy) return;
        busy = true;
        setStatus(getString(R.string.status_reading), State.IDLE, true);
        io.execute(() -> {
            Probe p;
            try {
                p = buildReport();
            } catch (Throwable t) {
                p = new Probe();
                p.text = "[!] 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                p.tracks = new ArrayList<>();
                p.kvs = new ArrayList<>();
                p.state = State.BAD;
            }
            final Probe r = p;
            writeDump(p.text);      // 每次都存档，方便外部读取原始结论
            ui.post(() -> {
                lastReport = r.text;
                lastOverview = r.overview;
                lastTracks = r.tracks;
                lastKvs = r.kvs;
                lastState = r.state;
                renderReport(r.text);
                busy = false;
                boolean ok = ShizukuShell.hasPermission();
                setStatus((ok ? getString(R.string.status_granted)
                              : getString(R.string.status_unauthorized)) + " · " + now(),
                        ok ? State.OK : State.IDLE, false);
            });
        });
    }
    // ───────────────────────────────────────────────────────── report render

    /** 每次刷新都把完整报告落到 files/last_report.txt，便于外部直接读取 */
    private void writeDump(String s) {
        try {
            File f = new File(getExternalFilesDir(null), "last_report.txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(s.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }


    private void renderReport(String report) {
        reportContainer.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);

        for (String rawBlock : report.split("\n(?=【\\d+】)")) {
            final String text = rawBlock.trim();
            if (text.isEmpty()) continue;

            String first = text.split("\n", 2)[0].trim();
            String rest = text.length() > first.length() ? text.substring(first.length()).trim() : "";

            MaterialCardView card = (MaterialCardView) inf.inflate(
                    R.layout.item_report_section, reportContainer, false);
            TextView title = card.findViewById(R.id.tvSectionTitle);
            TextView body = card.findViewById(R.id.tvSectionBody);
            LinearLayout rawHeader = card.findViewById(R.id.rawHeader);
            TextView rawTitle = card.findViewById(R.id.tvRawTitle);
            TextView rawBody = card.findViewById(R.id.tvRawBody);
            ImageView ivExpand = card.findViewById(R.id.ivExpand);

            title.setText(first);

            if (first.contains("【1】")) {
                // 总览卡片：标题按状态着色，一眼看出「双渲 / 正常」，正文为结构化总览行
                title.setTextColor(ContextCompat.getColor(this, colorOf(lastState)));
                body.setVisibility(View.GONE);
                renderKv(card, inf, lastOverview);
            } else if (first.contains("【2】")) {
                renderTracks(card, body, inf);
            } else if (first.contains("【3】")) {
                body.setVisibility(View.GONE);
                renderKv(card, inf, lastKvs);
            } else if (first.contains("【4】") || first.contains("【5】")) {
                rawTitle.setText(R.string.cd_expand);
                rawBody.setText(rest);
                body.setVisibility(View.GONE);
                rawHeader.setVisibility(View.VISIBLE);
                rawHeader.setOnClickListener(v -> toggleRaw(rawBody, ivExpand));
            } else {
                body.setText(rest);
            }

            reportContainer.addView(card);
        }
    }

    private void toggleRaw(TextView rawBody, ImageView ivExpand) {
        boolean show = rawBody.getVisibility() != View.VISIBLE;
        rawBody.setVisibility(show ? View.VISIBLE : View.GONE);
        RotateAnimation rot = new RotateAnimation(
                0f, show ? 180f : 0f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        rot.setDuration(200);
        rot.setFillAfter(true);
        ivExpand.startAnimation(rot);
    }

    /** 指标行：左标签 + 右值 + 左侧状态点 */
    private void renderKv(MaterialCardView card, LayoutInflater inf, List<Kv> data) {
        LinearLayout inner = card.findViewById(R.id.sectionInner);
        for (Kv kv : data) {
            View row = inf.inflate(R.layout.item_kv, inner, false);
            TextView label = row.findViewById(R.id.tvKvLabel);
            TextView value = row.findViewById(R.id.tvKvValue);
            label.setText(kv.label);
            value.setText(kv.value);
            value.setTextColor(ContextCompat.getColor(this, colorOf(kv.state)));
            inner.addView(row);
        }
    }

    private String kvToText(List<Kv> kvs) {
        StringBuilder sb = new StringBuilder();
        for (Kv kv : kvs) sb.append(kv.label).append(KV.trim()).append(kv.value).append(NL);
        return sb.toString().trim();
    }

    /** 【2】区块：每条 track 一行卡片 + 语义 chip */
    private void renderTracks(MaterialCardView card, TextView body, LayoutInflater inf) {
        LinearLayout inner = card.findViewById(R.id.sectionInner);
        if (lastTracks.isEmpty()) {
            body.setVisibility(View.VISIBLE);
            body.setText("(无 started/paused track —— 先随便放点声音再刷新)");
            return;
        }
        body.setVisibility(View.GONE);
        for (Tk t : lastTracks) {
            View row = inf.inflate(R.layout.item_track, inner, false);
            TextView name = row.findViewById(R.id.tvTrackName);
            TextView detail = row.findViewById(R.id.tvTrackDetail);
            Chip chip = row.findViewById(R.id.chipVerdict);

            name.setText(appLabel(t.uid));
            detail.setText(t.detailText());
            chip.setText(t.chipText());
            applyChipState(chip, t.chipState());
            inner.addView(row);
        }
    }

    // ─────────────────────────────────────────────────────────────── 报告本体

    private static final class Kv {
        final String label, value;
        final State state;
        Kv(String label, String value, State state) {
            this.label = label; this.value = value; this.state = state;
        }
    }

    private static final class Probe {
        String text = "";
        String headline = "";
        List<Kv> overview = new ArrayList<>();
        List<Tk> tracks = new ArrayList<>();
        List<Kv> kvs = new ArrayList<>();
        State state = State.IDLE;
    }

    private Probe buildReport() {
        Probe p = new Probe();
        StringBuilder sb = new StringBuilder();

        // ── 采集 ───────────────────────────────────────────────────────
        String rawTracks = ShizukuShell.sh(
                "dumpsys audio 2>/dev/null | grep -E 'piid:|state:|isSpatialized|Device' | head -160");
        List<Tk> tracks = parseTracks(rawTracks);

        String devRaw = ShizukuShell.oneLine("dumpsys audio 2>/dev/null | grep -m1 -E 'Devices:'");
        String spRaw = ShizukuShell.sh(
                "dumpsys media.audio_flinger 2>/dev/null | grep -A3 -E '\\(SPATIALIZER\\)|AUDIO_OUTPUT_FLAG_SPATIALIZER' | head -8");
        String htRaw = ShizukuShell.sh(
                "dumpsys media.audio_policy 2>/dev/null | grep -iE 'mHeadTrackingModes|mSupportsHeadTracking|mActualHeadTrackingMode|mDesiredHeadTrackingMode|headtracker available' | head -8");
        String idRaw = ShizukuShell.oneLine("id");
        // 输出线程采样率（判断是否重采样）＋ 音效引擎（Spatializer / Dolby 等）
        String srRaw = ShizukuShell.sh(
                "dumpsys media.audio_flinger 2>/dev/null | grep 'Sample rate' | head -10");
        String fxRaw = ShizukuShell.sh(
                "dumpsys media.audio_flinger 2>/dev/null | grep -iE 'Oplus Spatializer|dolby|dirac|dts' | head -8");

        // ── 判定 ─────────────────────────────────────────────────────
        boolean doubleRender = false, goodChain = false;
        for (Tk t : tracks) {
            if (t.spatial && t.mask >= 0 && t.mask <= 0x3) doubleRender = true;
            if (t.mask >= 0x3f && !t.spatial) goodChain = true;
        }
        boolean spOn = spRaw.contains("SPATIALIZER");
        String headline;
        State hs;
        if (doubleRender) {
            headline = "双渲";
            hs = State.WARN;
        } else if (goodChain) {
            headline = "正常";
            hs = State.OK;
        } else if (tracks.isEmpty()) {
            headline = "无会话";
            hs = State.IDLE;
        } else {
            headline = "普通立体声";
            hs = State.IDLE;
        }
        p.state = hs;
        p.headline = headline;

        // ── 【1】输出总览 ─────────────────────────────────────────────
        int srcSr = 0;
        for (Tk t : tracks) { if (t.sr > 0) { srcSr = t.sr; break; } }
        int outSr = firstInt(srRaw, "Sample rate:\\s*(\\d+)");
        boolean resampling = srcSr > 0 && outSr > 0 && srcSr != outSr;
        boolean oplusFx = fxRaw != null && fxRaw.contains("Oplus Spatializer");
        List<Kv> ov = p.overview;
        ov.add(new Kv("判定", headline, hs));
        ov.add(new Kv("App输出", appOutLine(tracks), State.IDLE));
        ov.add(new Kv("头部跟踪", headTrackingSummary(htRaw), headTrackingState(htRaw)));
        ov.add(new Kv("其他优化", fxSummary(fxRaw), oplusFx ? State.OK : State.IDLE));
        ov.add(new Kv("系统重渲", spOn ? "有 · SPATIALIZER" : "无", spOn ? State.OK : State.IDLE));
        ov.add(new Kv("输出链路", devFriendly(devRaw), State.IDLE));
        ov.add(new Kv("采样率", srcSr > 0 ? srcSr + " Hz" : "—", State.IDLE));
        ov.add(new Kv("重采样", resampleLine(srcSr, outSr), resampling ? State.WARN : State.IDLE));

        // ── 【3】系统与身份 ───────────────────────────────────────────
        List<Kv> kvs = p.kvs;
        kvs.add(new Kv("执行身份", idFriendly(idRaw), "uid=0".equals(idUid(idRaw)) ? State.WARN : State.OK));
        kvs.add(new Kv("Shizuku", ShizukuShell.hasPermission() ? "已授权" : "未授权",
                ShizukuShell.hasPermission() ? State.OK : State.BAD));
        kvs.add(new Kv("活跃 track", tracks.isEmpty() ? "无" : tracks.size() + " 条", tracks.isEmpty() ? State.IDLE : State.OK));

        // ── 文本报告（复制 / 导出 用） ───────────────────────────────
        sb.append("SpazPeek · 空间音频链路取证").append(NL);
        sb.append("时间     ").append(now()).append(NL);
        sb.append("判定     ").append(headline).append(NL);
        sb.append(NL).append("【1】输出总览").append(NL);
        sb.append(kvToText(ov)).append(NL);

        sb.append(NL).append(NL).append("【2】活跃 track").append(NL);
        if (tracks.isEmpty()) {
            sb.append("(无 started/paused track)").append(NL);
        } else {
            for (Tk t : tracks) {
                sb.append(appLabel(t.uid)).append(KV).append(t.detailText()).append(NL);
            }
        }

        sb.append(NL).append("【3】系统与身份").append(NL);
        sb.append(kvToText(kvs));

        sb.append(NL).append(NL).append("【4】原始片段（节选）").append(NL);
        sb.append(trim(rawTracks, 1400)).append(NL);

        sb.append(NL).append("【5】Shizuku 诊断").append(NL);
        sb.append(ShizukuShell.diag());

        p.tracks = tracks;
        p.text = sb.toString();
        return p;
    }

    // ───────────────────────────────────────────────────── 数据提炼小工具

    private static String devFriendly(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "—";
        String s = raw;
        int i = s.indexOf("Devices:");
        if (i >= 0) s = s.substring(i + 8).trim();
        String name = s;
        Matcher m = Pattern.compile("([a-z_0-9]+)").matcher(s);
        if (m.find()) name = m.group(1);
        switch (name) {
            case "bt_a2dp":    return "蓝牙 A2DP";
            case "bt_sco":     return "蓝牙通话(SCO)";
            case "speaker":    return "扬声器";
            case "earpiece":   return "听筒";
            case "wired_headphone": return "有线耳机";
            case "wired_headset":   return "有线耳机(麦)";
            case "usb_headset":return "USB 耳机";
            case "usb_device": return "USB 音频";
            case "hdmi":       return "HDMI";
            case "remote_submix": return "远程混音";
            case "default":    return "系统默认";
            default:           return name;
        }
    }

    private static String htActual(String raw) {
        if (raw == null) return null;
        // 真机格式： mHeadTrackingModes: [ DISABLED RELATIVE_WORLD], Desired: WORLD_RELATIVE, Actual RELATIVE_WORLD
        Matcher m = Pattern.compile("Actual[:\\s]+([A-Z_]+)").matcher(raw);
        if (m.find()) return m.group(1);
        // 部分 ROM 用全名
        m = Pattern.compile("mActualHeadTrackingMode:([A-Z_]+)").matcher(raw);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(DISABLED|STATIC|RELATIVE_WORLD|WORLD_RELATIVE)").matcher(raw);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String htDesired(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("Desired:\\s*([A-Z_]+)").matcher(raw);
        if (m.find()) return m.group(1);
        m = Pattern.compile("mDesiredHeadTrackingMode:([A-Z_]+)").matcher(raw);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String headTrackingSummary(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "—";
        boolean supported = raw.contains("mSupportsHeadTracking: true")
                || raw.contains("mIsHeadTrackingSupported:true")
                || raw.contains("RELATIVE_WORLD")
                || raw.contains("WORLD_RELATIVE");
        if (!supported) return "不支持";

        String actual = htActual(raw);
        if (actual == null) return "已支持 · 当前无空间会话";

        String t;
        if (actual.contains("DISABLED")) t = "已关闭";
        else if (actual.contains("STATIC")) t = "静态（未跟随头部）";
        else if (actual.contains("RELATIVE")) t = "开启 · 相对世界";
        else if (actual.contains("WORLD")) t = "开启 · 世界锁定";
        else t = actual;

        String desired = htDesired(raw);
        if (desired != null && !actual.contains("DISABLED") && !desired.contains(actual)) {
            t += "（目标 " + desired.replace("HEAD_TRACKING_MODE_", "") + "）";
        }
        return t;
    }

    private static State headTrackingState(String raw) {
        if (raw == null) return State.IDLE;
        String actual = htActual(raw);
        if (actual == null) return State.IDLE;
        if (actual.contains("DISABLED") || actual.contains("STATIC")) return State.IDLE;
        if (actual.contains("RELATIVE") || actual.contains("WORLD")) return State.OK;
        return State.IDLE;
    }

    private static String idUid(String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("uid=(\\d+)").matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    private static String idFriendly(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "—";
        Matcher m = Pattern.compile("uid=(\\d+)\\(([A-Za-z_]+)\\)").matcher(raw);
        if (m.find()) return m.group(2) + " · uid " + m.group(1);
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }

    // ─────────────────────────────────────────────────────────── track 解析

    private static final class Tk {
        int uid, pid;
        String state = "?";
        boolean spatial = false;
        long mask = -1;
        int sr = 0;

        String detailText() {
            String st = "started".equals(state) ? "播放中" : "paused".equals(state) ? "已暂停" : state;
            return (mask < 0 ? "未上报格式" : maskName(mask))
                    + " · " + (sr > 0 ? sr + " Hz" : "—")
                    + " · 空间化=" + (spatial ? "true" : "false")
                    + " · " + st;
        }

        State chipState() {
            if (mask < 0) return State.IDLE;
            if (mask >= 0x3f) return spatial ? State.WARN : State.OK;
            if (spatial && mask == 0x3) return State.WARN;
            if (mask == 0x3) return State.IDLE;
            return State.BAD;
        }

        String chipText() {
            switch (chipState()) {
                case OK:   return "正确链路";
                case WARN: return "双渲";
                case IDLE: return "普通立体声";
                default:   return "未知";
            }
        }
    }

    private static final Pattern P_MASK = Pattern.compile("channelMask=(0x[0-9a-fA-F]+)");
    private static final Pattern P_SR = Pattern.compile("sampleRate=(\\d+)");
    private static final Pattern P_SP = Pattern.compile("isSpatialized=(true|false)");
    private static final Pattern P_ST = Pattern.compile("state:([a-zA-Z]+)");

    private List<Tk> parseTracks(String raw) {
        List<Tk> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Tk cur = null;
        for (String line : raw.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("AudioPlaybackConfiguration") || l.contains("piid:")) {
                if (cur != null) out.add(cur);
                cur = new Tk();
                Matcher m = Pattern.compile("(?:uid|u)/pid:(\\d+)/(\\d+)").matcher(l);
                if (m.find()) {
                    cur.uid = Integer.parseInt(m.group(1));
                    cur.pid = Integer.parseInt(m.group(2));
                } else {
                    Matcher m2 = Pattern.compile("uid[:/ ](\\d+)").matcher(l);
                    if (m2.find()) cur.uid = Integer.parseInt(m2.group(1));
                }
            }
            if (cur == null) continue;
            Matcher ms = P_ST.matcher(l);
            if (ms.find()) cur.state = ms.group(1);
            Matcher mm = P_MASK.matcher(l);
            if (mm.find()) {
                try { cur.mask = Long.parseLong(mm.group(1).substring(2), 16); } catch (Throwable ignored) {}
            }
            Matcher mr = P_SR.matcher(l);
            if (mr.find()) { try { cur.sr = Integer.parseInt(mr.group(1)); } catch (Throwable ignored) {} }
            Matcher mp = P_SP.matcher(l);
            if (mp.find()) cur.spatial = "true".equals(mp.group(1));
        }
        if (cur != null) out.add(cur);

        // 同一 uid/pid 可能同时存在 paused / started 多条：优先保留 started
        java.util.Map<String, Tk> best = new java.util.LinkedHashMap<>();
        for (Tk t : out) {
            if (!"started".equals(t.state) && !"paused".equals(t.state)) continue;
            String key = t.uid + "/" + t.pid;
            Tk old = best.get(key);
            if (old == null || (!"started".equals(old.state) && "started".equals(t.state))) {
                best.put(key, t);
            }
        }
        List<Tk> alive = new ArrayList<>(best.values());
        // started 排前面
        java.util.Collections.sort(alive, (a, b) ->
                ("started".equals(b.state) ? 1 : 0) - ("started".equals(a.state) ? 1 : 0));
        return alive;
    }

    // ─────────────────────────────────────────────────────────────── helpers

    private String appName(int uid) {
        try {
            String[] ps = getPackageManager().getPackagesForUid(uid);
            if (ps != null && ps.length > 0) return ps[0];
        } catch (Throwable ignored) {}
        return "uid" + uid;
    }

    /** 常见 App 友好名（找不到返回包名） */
    private String appLabel(int uid) {
        String pkg = appName(uid);
        String cn = cnName(pkg);
        return cn == null ? pkg : cn;
    }

    private static String cnName(String pkg) {
        if (pkg == null) return null;
        switch (pkg) {
            case "com.ss.android.ugc.aweme":      return "抖音";
            case "com.ss.android.ugc.aweme.lite": return "抖音极速版";
            case "com.tencent.qqmusic":           return "QQ音乐";
            case "com.netease.cloudmusic":        return "网易云音乐";
            case "com.kugou.android":             return "酷狗音乐";
            case "cn.kuwo.player":                return "酷我音乐";
            case "com.ximalaya.ting.android":     return "喜马拉雅";
            case "tv.danmaku.bili":               return "哔哩哔哩";
            case "com.tencent.mm":                return "微信";
            case "com.google.android.youtube":    return "YouTube";
            case "com.spotify.music":             return "Spotify";
            default: return null;
        }
    }

    static String maskName(long mask) {
        switch ((int) mask) {
            case 0x1:
            case 0x2:
            case 0x4:     return "单声道";
            case 0x3:     return "立体声（2.0）";
            case 0x7:     return "3.0";
            case 0xb:     return "2.1";
            case 0x33:    return "4.0";
            case 0x37:    return "5.0";
            case 0x107:   return "3.0环绕";
            case 0x13f:   return "6.1";
            case 0x3f:    return "5.1环绕";
            case 0x603:   return "4.0（侧）";
            case 0x60f:   return "5.1环绕（侧）";
            case 0x63f:   return "7.1环绕";
            case 0x503f:  return "5.1.2";
            case 0x2d03f: return "5.1.4";
            case 0x2d63f: return "7.1.4";
            default:
                if (mask <= 0) return "—";
                return "0x" + Long.toHexString(mask) + "（" + Long.bitCount(mask) + "声道）";
        }
    }

    /** 「App 输出」汇总：各 track 的通道布局去重拼接 */
    private static String appOutLine(List<Tk> tracks) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Tk t : tracks) if (t.mask > 0) names.add(maskName(t.mask));
        if (names.isEmpty()) return "—";
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            if (b.length() > 0) b.append("、");
            b.append(n);
        }
        return b.toString();
    }

    /** 重采样判断：源采样率 vs 输出采样率 */
    private static String resampleLine(int src, int out) {
        if (src <= 0 || out <= 0) return "—";
        if (src == out) return "无";
        return "有（" + fmtKHz(src) + " → " + fmtKHz(out) + "）";
    }

    private static String fmtKHz(int sr) {
        if (sr % 1000 == 0) return (sr / 1000) + " kHz";
        return String.format(Locale.US, "%.1f kHz", sr / 1000.0);
    }

    /** 其他音频优化引擎（Spatializer / Dolby 等） */
    private static String fxSummary(String raw) {
        if (raw == null) return "无";
        String low = raw.toLowerCase(Locale.US);
        StringBuilder b = new StringBuilder();
        if (raw.contains("Oplus Spatializer")) b.append("Oplus Spatializer");
        if (low.contains("dolby")) { if (b.length() > 0) b.append(" / "); b.append("Dolby"); }
        if (low.contains("dirac")) { if (b.length() > 0) b.append(" / "); b.append("Dirac"); }
        if (low.contains("dts"))   { if (b.length() > 0) b.append(" / "); b.append("DTS"); }
        return b.length() == 0 ? "无" : "有 · " + b;
    }

    private static int firstInt(String raw, String regex) {
        if (raw == null) return 0;
        Matcher m = Pattern.compile(regex).matcher(raw);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static String trim(String s, int max) {
        if (s == null || s.trim().isEmpty()) {
            return "  (无数据 —— Shizuku 未授权或该服务不可读)" + NL;
        }
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\\r?\\n")) b.append("  ").append(line.trim()).append(NL);
        String r = b.toString();
        if (r.length() > max) r = r.substring(0, max) + "  …(截断)" + NL;
        return r;
    }
}