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

/** 本地 HTTP 服务：/ 返回雷达页面，/api/state 返回 JSON。 */
final class RadarServer {
    static final int PORT = 8899;
    private static final double FRESH_SEC = 12.0;

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
                body = PAGE;
                type = "text/html; charset=utf-8";
            }

            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type + "\r\n"
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
                    if (ts >= bestTs) { bestTs = ts; camYaw = c.optDouble("yaw_deg", 0); }
                }
            }
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
        if (e == null) return "{\"x\":0,\"z\":0,\"hp\":-1,\"id\":\"\",\"name\":\"\"}";
        return "{\"x\":" + round(e.optDouble("x", 0))
                + ",\"z\":" + round(e.optDouble("z", 0))
                + ",\"hp\":" + e.optInt("hp", -1)
                + ",\"id\":\"" + esc(e.optString("id1", "")) + "\""
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

    private static final String PAGE =
        "<!doctype html><html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>" +
        "<title>Radar</title><style>" +
        "html,body{margin:0;height:100%;background:#0a0e13;color:#dde;font:12px monospace;overflow:hidden}" +
        "canvas{display:block}" +
        "</style></head><body><canvas id='c'></canvas>" +
        "<div id='h' style='position:fixed;left:8px;top:8px;color:#8fa;text-shadow:0 0 4px #000'>waiting</div>" +
        "<script>" +
        "var c=document.getElementById('c'),x=c.getContext('2d'),h=document.getElementById('h'),st=null;" +
        "function rs(){c.width=innerWidth;c.height=innerHeight;}window.onresize=rs;rs();" +
        "var D=['北','东北','东','东南','南','西南','西','西北'];" +
        "var hist={},smooth={};" +
        "function br(a,b){var t=Math.atan2(a,b)*180/Math.PI;if(t<0)t+=360;return D[Math.round(t/45)%8];}" +
        "function tick(){var q=new XMLHttpRequest();q.open('GET','/api/state?t='+Date.now(),true);" +
        "q.onload=function(){try{st=JSON.parse(q.responseText)}catch(e){}};q.send();draw();setTimeout(tick,100);}" +
        "function pushHist(l){" +
        "var seen={};" +
        "for(var i=0;i<l.length;i++){" +
        "var p=l[i],id=p.id||p.name||('u'+i);seen[id]=1;" +
        "var a=hist[id]||(hist[id]=[]);" +
        "var last=a[a.length-1];" +
        "if(!last||Math.abs(p.x-last.x)+Math.abs(p.z-last.z)>1.5){" +
        "a.push({x:p.x,z:p.z});if(a.length>14)a.shift();}" +
        "var s=smooth[id];if(!s){smooth[id]={x:p.x,z:p.z};}" +
        "else{s.x+=(p.x-s.x)*0.45;s.z+=(p.z-s.z)*0.45;}" +
        "}" +
        "for(var k in hist){if(!seen[k]){delete hist[k];delete smooth[k];}}" +
        "}" +
        "function draw(){" +
        "var W=c.width,H=c.height,cx=W/2,cy=H/2,SC=Math.min(W,H)/800+0.45;" +
        "x.clearRect(0,0,W,H);" +
        "x.strokeStyle='#2e4459';x.lineWidth=1.2;" +
        "for(var r=100;r<5000;r+=100){var rr=r*SC;if(rr>Math.max(W,H))break;" +
        "x.beginPath();x.arc(cx,cy,rr,0,7);x.stroke();" +
        "x.fillStyle='#3d5a73';x.font='9px monospace';x.fillText(r,cx+rr-16,cy-3);}" +
        "x.strokeStyle='#3a5470';x.beginPath();" +
        "x.moveTo(cx,0);x.lineTo(cx,H);x.moveTo(0,cy);x.lineTo(W,cy);x.stroke();" +
        "if(!st||!st.local){h.textContent='waiting';return;}" +
        "var l=st.local;pushHist(st.players);" +
        "for(var i=0;i<st.players.length;i++){" +
        "var p=st.players[i],id=p.id||p.name||('u'+i);" +
        "var s=smooth[id]||p;" +
        "var dx=s.x-l.x,dz=s.z-l.z,d=Math.sqrt(dx*dx+dz*dz);" +
        "var px=cx+dx*SC,py=cy-dz*SC;" +
        "if(px<-80||px>W+80||py<-80||py>H+80)continue;" +
        "var a=hist[id]||[];" +
        "for(var t=1;t<a.length;t++){" +
        "var a1=cx+(a[t-1].x-l.x)*SC,a2=cy-(a[t-1].z-l.z)*SC;" +
        "var b1=cx+(a[t].x-l.x)*SC,b2=cy-(a[t].z-l.z)*SC;" +
        "x.strokeStyle='rgba(255,70,70,'+(0.05+0.28*t/a.length)+')';x.lineWidth=2;" +
        "x.beginPath();x.moveTo(a1,a2);x.lineTo(b1,b2);x.stroke();}" +
        "x.fillStyle='#ff3b3b';x.beginPath();x.arc(px,py,5,0,7);x.fill();" +
        "x.strokeStyle='#ff8080';x.lineWidth=1;x.beginPath();x.arc(px,py,9,0,7);x.stroke();" +
        "x.font='12px monospace';x.fillStyle='#e8eef5';x.fillText(p.name||'?',px+12,py-2);" +
        "x.fillStyle='#8fa8bd';x.fillText(d.toFixed(0)+' '+br(dx,dz),px+12,py+13);}" +
        "x.fillStyle='#22f5a0';x.beginPath();x.arc(cx,cy,6,0,7);x.fill();" +
        "x.strokeStyle='#22f5a0';x.beginPath();x.arc(cx,cy,14,0,7);x.stroke();" +
        "h.textContent='players='+st.players.length;}" +
        "tick();" +
        "</script></body></html>";
}
