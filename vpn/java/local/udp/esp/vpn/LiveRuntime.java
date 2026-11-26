package local.udp.esp.vpn;

import org.json.JSONObject;

/** Shared state between the forwarding, decoding and overlay threads. */
final class LiveRuntime {
       static volatile JSONObject snapshot;
    static volatile String status = "尚未启动 VPN";
    static volatile String diag = "";
    static volatile boolean running;
    static volatile NearestTarget.Table targets = NearestTarget.Table.EMPTY;

    static final class ViewControl {
        final boolean enabled;
        final long epoch;
        ViewControl(boolean enabled, long epoch) { this.enabled = enabled; this.epoch = epoch; }
    }
    private static volatile ViewControl viewControl = new ViewControl(false, 0);
    static ViewControl viewControl() { return viewControl; }
    static synchronized void setViewEnabled(boolean enabled) {
        ViewControl previous = viewControl;
        if (previous.enabled != enabled) {
            viewControl = new ViewControl(enabled, previous.epoch + 1);
            viewStatus = enabled ? "等待方向更新" : "已关闭";
            rotationStatus = enabled ? "等待场景更新" : "已关闭";
        }
    }
    static synchronized void toggleView() { setViewEnabled(!viewControl.enabled); }

    // Forwarding callbacks and packet rewriters share these in-memory counters.
    // They are not displayed or written to storage.
    static volatile long viewRewrites, viewSent, viewSendFailures;
    static volatile long rotationScenes, rotationMatches, rotationRewrites;
    static volatile long rotationDelivered, rotationDeliverFailures, rotationAdded;
    static volatile String viewStatus = "等待登录与场景";
    static volatile String rotationStatus = "等待场景";

    static synchronized void resetView() {
        viewControl = new ViewControl(false, viewControl.epoch + 1);
        targets = NearestTarget.Table.EMPTY;
        viewRewrites = viewSent = viewSendFailures = 0;
        rotationScenes = rotationMatches = rotationRewrites = 0;
        rotationDelivered = rotationDeliverFailures = rotationAdded = 0;
        viewStatus = "等待登录与场景";
        rotationStatus = "等待场景";
    }
       static String viewSummary() { return "水平跟随：" + (viewControl.enabled ? "已开启" : "已关闭") + "\n" + diag; }
    private LiveRuntime() {}
}
