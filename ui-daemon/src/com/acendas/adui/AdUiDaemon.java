package com.acendas.adui;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * android-debugger on-device UI daemon.
 *
 * Runs as the shell user through app_process (no APK install):
 *
 *   adb push ad-ui.jar /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/ad-ui.jar app_process / com.acendas.adui.AdUiDaemon
 *
 * Android allows one UiAutomation session per device (UiAutomationManager: "already
 * registered"). This daemon holds it for its whole lifetime, so the accessibility event
 * stream, layout reads and input injection never fight each other the way
 * `uiautomator events` + `uiautomator dump` (or `android layout`) do.
 *
 * It is deliberately thin: it streams raw events and serves requests. Settling, diffing
 * and tick suppression live in the host server, where they are unit-tested.
 *
 * Protocol: newline-delimited JSON over the abstract socket "ad-ui" (host side:
 * `adb forward tcp:N localabstract:ad-ui`). One request line gets one response line,
 * except {"cmd":"subscribe"}, which turns the connection into an event stream.
 */
public final class AdUiDaemon {

    /** Bumped on any protocol change; the host re-pushes the jar on mismatch. */
    static final String VERSION = "1";
    static final String SOCKET = "ad-ui";
    /** UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES (hidden constant before API 24). */
    static final int FLAG_DONT_SUPPRESS_A11Y = 1;
    /** Compose emits bursts of anonymous content-changed pings; forward at most one per window. */
    static final long CONTENT_COALESCE_MS = 100;

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<OutputStream> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private HandlerThread automationThread;
    private UiAutomation automation;
    private long lastContentForwardedAt = 0;

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        AdUiDaemon daemon = new AdUiDaemon();
        daemon.acquire();
        daemon.serve();
    }

    // ---- UiAutomation session ------------------------------------------------------------

    void acquire() throws Exception {
        synchronized (lock) {
            if (automation != null) return;
            automationThread = new HandlerThread("ad-ui-automation");
            automationThread.start();
            Class<?> connCls = Class.forName("android.app.UiAutomationConnection");
            Object conn = connCls.getDeclaredConstructor().newInstance();
            Class<?> iconn = Class.forName("android.app.IUiAutomationConnection");
            Constructor<UiAutomation> ctor =
                    UiAutomation.class.getDeclaredConstructor(Looper.class, iconn);
            ctor.setAccessible(true);
            UiAutomation ua = ctor.newInstance(automationThread.getLooper(), conn);
            Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            connect.invoke(ua, FLAG_DONT_SUPPRESS_A11Y);
            ua.setOnAccessibilityEventListener(this::onEvent);
            automation = ua;
        }
    }

    void release() throws Exception {
        synchronized (lock) {
            if (automation == null) return;
            Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(automation);
            automationThread.quitSafely();
            automation = null;
            automationThread = null;
        }
    }

    private UiAutomation automation() throws DaemonError {
        synchronized (lock) {
            if (automation == null) {
                throw new DaemonError("released", "UI session released; send acquire first");
            }
            return automation;
        }
    }

    // ---- Events ----------------------------------------------------------------------------

    private void onEvent(AccessibilityEvent e) {
        if (subscribers.isEmpty()) return;
        boolean content = e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        if (content) {
            long now = SystemClock.uptimeMillis();
            if (now - lastContentForwardedAt < CONTENT_COALESCE_MS) return;
            lastContentForwardedAt = now;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "a11y");
            o.put("type", AccessibilityEvent.eventTypeToString(e.getEventType()));
            o.put("wall_ms", System.currentTimeMillis());
            putIfNotNull(o, "package", e.getPackageName());
            putIfNotNull(o, "class", e.getClassName());
            if (!e.getText().isEmpty()) o.put("text", textArray(e.getText()));
            putIfNotNull(o, "content_desc", e.getContentDescription());
            if (!content) {
                // Content pings are high-volume; skip the extra binder round-trip for them.
                AccessibilityNodeInfo src = e.getSource();
                if (src != null) {
                    putIfNotNull(o, "resource_id", src.getViewIdResourceName());
                    src.recycle();
                }
            }
            broadcast(o);
        } catch (Exception ignored) {
            // A malformed event must never take the stream down.
        }
    }

    private void broadcast(JSONObject o) {
        byte[] bytes = (o.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream out : subscribers) {
            try {
                synchronized (out) {
                    out.write(bytes);
                    out.flush();
                }
            } catch (Exception dead) {
                subscribers.remove(out);
            }
        }
    }

    // ---- Server ----------------------------------------------------------------------------

    void serve() throws Exception {
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        System.out.println("ad-ui ready v" + VERSION + " sdk " + Build.VERSION.SDK_INT);
        System.out.flush();
        while (true) {
            LocalSocket client = server.accept();
            Thread t = new Thread(() -> handle(client), "ad-ui-client");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(LocalSocket client) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject resp;
                boolean quit = false;
                try {
                    JSONObject req = new JSONObject(line);
                    String cmd = req.optString("cmd");
                    if ("subscribe".equals(cmd)) {
                        subscribers.add(out);
                        write(out, ok().put("subscribed", true));
                        continue;
                    }
                    quit = "quit".equals(cmd);
                    resp = dispatch(cmd, req);
                } catch (DaemonError e) {
                    resp = error(e.code, e.getMessage());
                } catch (Exception e) {
                    resp = error("bad_request", String.valueOf(e));
                }
                write(out, resp);
                if (quit) System.exit(0);
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) { }
        }
    }

    private static void write(OutputStream out, JSONObject o) throws Exception {
        byte[] bytes = (o.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }

    private JSONObject dispatch(String cmd, JSONObject req) throws Exception {
        switch (cmd) {
            case "ping":
                synchronized (lock) {
                    return ok().put("version", VERSION).put("sdk", Build.VERSION.SDK_INT)
                            .put("acquired", automation != null);
                }
            case "layout":
                return layout(req.optLong("idle_ms", 0), req.optLong("timeout_ms", 3000),
                        req.optBoolean("full", false));
            case "tap":
                return press(req.getInt("x"), req.getInt("y"), 0);
            case "long_press":
                return press(req.getInt("x"), req.getInt("y"),
                        req.optLong("duration_ms", 800));
            case "swipe":
                return swipe(req.getInt("x1"), req.getInt("y1"), req.getInt("x2"),
                        req.getInt("y2"), req.optLong("duration_ms", 400));
            case "key":
                return key(req.getInt("code"));
            case "set_text":
                return setText(req.getString("text"));
            case "release":
                release();
                return ok().put("acquired", false);
            case "acquire":
                acquire();
                return ok().put("acquired", true);
            case "quit":
                release();
                return ok();
            default:
                throw new DaemonError("unknown_cmd", cmd);
        }
    }

    // ---- Layout ----------------------------------------------------------------------------

    private JSONObject layout(long idleMs, long timeoutMs, boolean full) throws Exception {
        UiAutomation ua = automation();
        long start = SystemClock.uptimeMillis();
        final String[] rootPkg = new String[1];
        Future<JSONArray> f = reader.submit(() -> {
            if (idleMs > 0) {
                try {
                    ua.waitForIdle(idleMs, timeoutMs);
                } catch (TimeoutException ignored) {
                    // Not idle is not fatal; read what is there.
                }
            }
            AccessibilityNodeInfo root = ua.getRootInActiveWindow();
            if (root == null) return null;
            if (root.getPackageName() != null) rootPkg[0] = root.getPackageName().toString();
            JSONArray nodes = new JSONArray();
            walk(root, nodes, full);
            root.recycle();
            return nodes;
        });
        try {
            JSONArray nodes = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (nodes == null) {
                throw new DaemonError("no_root",
                        "no active window root (window swapping, app busy, or secure window)");
            }
            JSONObject r = ok().put("elapsed_ms", SystemClock.uptimeMillis() - start);
            if (rootPkg[0] != null) r.put("package", rootPkg[0]);
            return r.put("nodes", nodes);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new DaemonError("timeout", "layout read exceeded " + timeoutMs
                    + "ms (UI thread frozen, e.g. the app is paused in the debugger)");
        }
    }

    private static void walk(AccessibilityNodeInfo n, JSONArray out, boolean full)
            throws Exception {
        if (n == null) return;
        boolean interactive = n.isClickable() || n.isLongClickable() || n.isCheckable()
                || n.isEditable() || n.isScrollable() || n.isFocusable();
        boolean hasText = n.getText() != null && n.getText().length() > 0
                || n.getContentDescription() != null && n.getContentDescription().length() > 0;
        if (n.isVisibleToUser() && (full || interactive || hasText)) {
            JSONObject o = new JSONObject();
            putIfNotNull(o, "text", n.getText());
            putIfNotNull(o, "content-desc", n.getContentDescription());
            putIfNotNull(o, "resource-id", n.getViewIdResourceName());
            putIfNotNull(o, "class", n.getClassName());
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            o.put("bounds", "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "]");
            o.put("center", "[" + r.centerX() + "," + r.centerY() + "]");
            JSONArray inter = new JSONArray();
            if (n.isClickable()) inter.put("clickable");
            if (n.isLongClickable()) inter.put("long-clickable");
            if (n.isCheckable()) inter.put("checkable");
            if (n.isEditable()) inter.put("editable");
            if (n.isScrollable()) inter.put("scrollable");
            if (n.isFocusable()) inter.put("focusable");
            if (inter.length() > 0) o.put("interactions", inter);
            JSONArray state = new JSONArray();
            if (n.isFocused()) state.put("focused");
            if (n.isChecked()) state.put("checked");
            if (n.isSelected()) state.put("selected");
            if (!n.isEnabled()) state.put("disabled");
            if (state.length() > 0) o.put("state", state);
            out.put(o);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                walk(c, out, full);
                c.recycle();
            }
        }
    }

    // ---- Input -----------------------------------------------------------------------------

    private JSONObject press(int x, int y, long holdMs) throws Exception {
        UiAutomation ua = automation();
        long down = SystemClock.uptimeMillis();
        boolean a = inject(ua, down, down, MotionEvent.ACTION_DOWN, x, y);
        if (holdMs > 0) SystemClock.sleep(holdMs);
        boolean b = inject(ua, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        if (!(a && b)) throw new DaemonError("inject_failed", "touch not injected");
        return ok();
    }

    private JSONObject swipe(int x1, int y1, int x2, int y2, long durationMs) throws Exception {
        UiAutomation ua = automation();
        int steps = (int) Math.max(5, durationMs / 16);
        long down = SystemClock.uptimeMillis();
        boolean okAll = inject(ua, down, down, MotionEvent.ACTION_DOWN, x1, y1);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            SystemClock.sleep(durationMs / steps);
            okAll &= inject(ua, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        }
        okAll &= inject(ua, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2);
        if (!okAll) throw new DaemonError("inject_failed", "swipe not fully injected");
        return ok();
    }

    private static boolean inject(UiAutomation ua, long down, long at, int action, float x,
            float y) {
        MotionEvent e = MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean done = ua.injectInputEvent(e, true);
        e.recycle();
        return done;
    }

    private JSONObject key(int code) throws Exception {
        UiAutomation ua = automation();
        long now = SystemClock.uptimeMillis();
        boolean a = ua.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true);
        boolean b = ua.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true);
        if (!(a && b)) throw new DaemonError("inject_failed", "key not injected");
        return ok();
    }

    private JSONObject setText(String text) throws Exception {
        UiAutomation ua = automation();
        AccessibilityNodeInfo root = ua.getRootInActiveWindow();
        if (root == null) throw new DaemonError("no_root", "no active window");
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            throw new DaemonError("no_focus", "no focused input field; tap the field first");
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw new DaemonError("action_failed", "the focused field refused ACTION_SET_TEXT");
        }
        return ok();
    }

    // ---- JSON helpers ----------------------------------------------------------------------

    private static final class DaemonError extends Exception {
        final String code;

        DaemonError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false).put("code", code).put("message", message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray textArray(List<CharSequence> text) {
        JSONArray a = new JSONArray();
        for (CharSequence t : text) a.put(t == null ? JSONObject.NULL : t.toString());
        return a;
    }

    private static void putIfNotNull(JSONObject o, String k, CharSequence v) throws Exception {
        if (v != null) o.put(k, v.toString());
    }
}
