package local.udp.esp.vpn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地 HTTP 雷达服务。
 * 把 LiveRuntime.snapshot 转成网页用的 JSON，挂在 8899 端口。
 * 只读，不修改任何游戏状态、不碰转发路径。
 */
final class RadarServer {
    static final int PORT = 8899;

    private volatile boolean run = false;
    private ServerSocket server = null;

    void start() {
        if (run) return;
        run = true;
        System.out.println("[ESPRADAR] start() called, port=" + PORT);
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "esp-radar");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        run = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        System.out.println("[ESPRADAR] stopped");
    }

    private void loop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            // 0.0.0.0 = 局域网/USB 共享可直连；只想本机用就换 127.0.0.1
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
            System.out.println("[ESPRADAR] listening on 0.0.0.0:" + PORT);
            while (run) {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(s); }
                });
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception e) {
            System.out.println("[ESPRADAR] bind failed: " + e);
        } finally {
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(2000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) { }

            String body;
            String type;
            if (path.startsWith("/api/state")) {
                body = state();
                type = "application/json; charset=utf-8";
            } else {
                body = HTML;
                type = "text/html; charset=utf-8";
            }

            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type
                    + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *"
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
            s.close();
        } catch (Exception ignored) {
            try { s.close(); } catch (Exception e) { }
        }
    }

    /** 抽雷达需要的字段：本地玩家、相机 yaw、其他玩家的 XZ 坐标。 */
    private static String state() {
        JSONObject snap = LiveRuntime.snapshot;
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"running\":").append(LiveRuntime.running)
          .append(",\"status\":\"").append(esc(LiveRuntime.status)).append("\"");

        if (snap == null) {
            sb.append(",\"local\":null,\"yaw\":0,\"players\":[]}");
            return sb.toString();
        }

        JSONArray ents = snap.optJSONArray("entities");
        JSONObject local = null;
        JSONArray players = new JSONArray();

        if (ents != null) {
            for (int i = 0; i < ents.length(); i++) {
                JSONObject e = ents.optJSONObject(i);
                if (e == null) continue;
                if (e.optBoolean("is_local")) { local = e; continue; }
                players.put(e);
            }
        }

        double yaw = 0;
        JSONArray cams = snap.optJSONArray("camera_streams");
        if (cams != null) {
            for (int i = 0; i < cams.length(); i++) {
                JSONObject c = cams.optJSONObject(i);
                if (c != null && "decoded".equals(c.optString("status"))) {
                    yaw = c.optDouble("yaw_deg", 0);
                    break;
                }
            }
        }

        sb.append(",\"yaw\":").append(round(yaw));
        sb.append(",\"local\":");
        if (local == null) sb.append("null");
        else sb.append(point(local));

        sb.append(",\"players\":[");
        for (int i = 0; i < players.length(); i++) {
            if (i > 0) sb.append(',');
            sb.append(point(players.optJSONObject(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String point(JSONObject e) {
        if (e == null) return "{\"x\":0,\"z\":0,\"y\":0,\"hp\":-1,\"name\":\"\"}";
        return "{\"x\":" + round(e.optDouble("x", 0))
                + ",\"z\":" + round(e.optDouble("z", 0))
                + ",\"y\":" + round(e.optDouble("y", 0))
                + ",\"hp\":" + e.optInt("hp", -1)
                + ",\"name\":\"" + esc(e.optString("name", "")) + "\"}";
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static final String HTML =
        "<!doctype html><html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>UDP Radar</title><style>" +
        "body{margin:0;background:#0b0f14;color:#dde;font:13px monospace;" +
        "display:flex;flex-direction:column;align-items:center;gap:8px;padding:10px}" +
        "#cv{background:#111820;border:1px solid #223}" +
        "</style></head><body>" +
        "<div id='hud'>waiting...</div>" +
        "<canvas id='cv' width='680' height='680'></canvas>" +
        "<script>" +
        "
