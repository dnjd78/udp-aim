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

/** 本地 HTTP 服务：输出雷达 JSON，页面在电脑端运行。 */
final class RadarServer {
    static final int PORT = 8899;
    private static final double FRESH_SEC = 12.0;   // 实体超过这个时间没更新就不显示

    private volatile boolean run = false;
    private ServerSocket server = null;

    void start() {
        if (run) return;
        run = true;
        System.out.println("[ESPRADAR] start() port=" + PORT);
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
            server.bind(new InetSocketAddress("0.0.0.0", PORT));
            System.out.println("[ESPRADAR] listening 0.0.0.0:" + PORT);
            while (run) {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(s); }
                });
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception e) {
            System.out.println("[ESPRADAR] bind failed " + e);
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
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) { }

            byte[] data = state().getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
            s.close();
        } catch (Exception ignored) {
            try { s.close(); } catch (Exception e) { }
        }
    }

    private static String state() {
        JSONObject snap = LiveRuntime.snapshot;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"running\":").append(LiveRuntime.running).append(",\"players\":[");

        JSONObject local = null;
        JSONArray all = new JSONArray();
        JSONArray out = new JSONArray();
        double camYaw = 0, rotYaw = -999;

        if (snap != null) {
            double now = snap.optDouble("now", 0);

            JSONArray ents = snap.optJSONArray("entities");
            if (ents != null) {
                for (int i = 0; i < ents.length(); i++) {
                    JSONObject e = ents.optJSONObject(i);
                    if (e == null) continue;
                    if (e.optBoolean("is_local")) local = e;
                    else all.put(e);
                }
            }

            // 只保留同一条连接 + 近 12 秒内还在更新的实体
            String localStream = (local == null) ? null : local.optString("stream", "");
            for (int i = 0; i < all.length(); i++) {
                JSONObject e = all.optJSONObject(i);
                if (e == null) continue;
                if (localStream != null && !localStream.isEmpty()
                        && !localStream.equals(e.optString("stream", ""))) continue;
                double ts = e.optDouble("timestamp", 0);
                if (now > 0 && ts > 0 && (now - ts) > FRESH_SEC) continue;
                out.put(e);
            }

            // yaw 来源1：上行 3/851
            JSONArray cams = snap.optJSONArray("camera_streams");
            if (cams != null) {
                double bestTs = -1;
                for (int i = 0; i < cams.length(); i++) {
                    JSONObject c = cams.optJSONObject(i);
                    if (c == null) continue;
                    if (!"decoded".equals(c.optString("status"))) continue;
                    if (localStream != null && !localStream.isEmpty()
                            && !localStream.equals(c.optString("stream", ""))) continue;
                    double ts = c.optDouble("timestamp", 0);
                    if (ts >= bestTs) {
                        bestTs = ts;
                        camYaw = c.optDouble("yaw_deg", 0);
                    }
                }
            }

            // yaw 来源2：下行自身 Rotation 动作
            JSONObject acts = snap.optJSONObject("actor_action_state");
            JSONArray recs = acts == null ? null : acts.optJSONArray("local_records");
            if (recs != null) {
                double bestTs = -1;
                for (int i = 0; i < recs.length(); i++) {
                    JSONObject r = recs.optJSONObject(i);
                    if (r == null || r.optInt("kind", -1) != 2) continue;
                    if (localStream != null && !localStream.isEmpty()
                            && !localStream.equals(r.optString("stream", ""))) continue;
                    double ts = r.optDouble("timestamp", 0);
                    if (ts < bestTs) continue;
                    JSONObject input = r.optJSONObject("reported_input");
                    if (input == null) continue;
                    JSONObject q = input.optJSONObject("reported_actor_rotation");
                    if (q == null) continue;
                    double qx = q.optDouble("x", 0);
                    double qy = q.optDouble("y", 0);
                    double qz = q.optDouble("z", 0);
                    double qw = q.optDouble("w", 1);
                    double y = Math.atan2(2 * (qw * qy + qx * qz),
                                          1 - 2 * (qy * qy + qz * qz));
                    double deg = Math.toDegrees(y);
                    if (deg < 0) deg += 360;
                    bestTs = ts;
                    rotYaw = deg;
                }
            }
        }

        for (int i = 0; i < out.length(); i++) {
            if (i > 0) sb.append(',');
            sb.append(point(out.optJSONObject(i)));
        }

        sb.append("],\"yaw\":").append(round(camYaw))
          .append(",\"rotYaw\":").append(round(rotYaw))
          .append(",\"local\":");
        if (local == null) sb.append("null");
        else sb.append(point(local));
        sb.append('}');
        return sb.toString();
    }

    private static String point(JSONObject e) {
        if (e == null) return "{\"x\":0,\"z\":0,\"hp\":-1,\"name\":\"\"}";
        return "{\"x\":" + round(e.optDouble("x", 0))
                + ",\"z\":" + round(e.optDouble("z", 0))
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
}
