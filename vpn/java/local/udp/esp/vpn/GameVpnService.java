package local.udp.esp.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Standard local Android VPN, restricted to the fixed game package. */
public final class GameVpnService extends VpnService {
    static final String START="local.udp.esp.vpn.VPN_START",STOP="local.udp.esp.vpn.VPN_STOP";
    static final String GAME="com.pi.czrxdfirst";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object nativeLock=new Object();
    private final ArrayBlockingQueue<Packet> queue=new ArrayBlockingQueue<>(1024);
    private final AtomicInteger queueBytes=new AtomicInteger();
    private volatile boolean stopping;
    private Thread forwarder,decoderWorker;
    private ParcelFileDescriptor tun;
    private long handle;
    private ViewRewrite viewRewrite;
    private RotationRewrite rotationRewrite;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final class Packet {
        final boolean outgoing;final String peer;final int local,remote;final byte[] body;final double ts;
        Packet(boolean out,String p,int local,int remote,byte[] b,double ts){outgoing=out;peer=p;this.local=local;this.remote=remote;body=b;this.ts=ts;}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction()))return START_NOT_STICKY;
        if(forwarder!=null)return START_NOT_STICKY;
        try{
            if(VpnService.prepare(this)!=null)throw new IllegalStateException("请在应用内确认 VPN 授权");
            getPackageManager().getApplicationInfo(GAME,0);
            foreground();stopping=false;queue.clear();queueBytes.set(0);
            LiveRuntime.resetView();viewRewrite=new ViewRewrite();rotationRewrite=new RotationRewrite();
            LiveRuntime.snapshot=null;LiveRuntime.running=false;
            Builder builder=new Builder().setSession("超自然 UDP ESP · 本地 VPN").setMtu(1500)
                .addAddress("10.111.0.2",32).addAddress("fd66:111::2",128)
                .addRoute("0.0.0.0",0).addRoute("::",0).addAllowedApplication(GAME);
            Set<String> dns=new HashSet<>();connectivity=getSystemService(ConnectivityManager.class);
            Network active=connectivity.getActiveNetwork();LinkProperties lp=active==null?null:connectivity.getLinkProperties(active);
            if(lp!=null)for(InetAddress address:lp.getDnsServers())if(dns.add(address.getHostAddress()))builder.addDnsServer(address);
            if(dns.isEmpty())builder.addDnsServer("223.5.5.5");
            builder.setBlocking(false);if(Build.VERSION.SDK_INT>=29)builder.setMetered(false);
            tun=builder.establish();if(tun==null)throw new IllegalStateException("系统没有建立 VPN");
            synchronized(nativeLock){handle=VpnNative.create();if(handle==0)throw new IllegalStateException("转发器初始化失败");}
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){setUnderlying(network);}
                @Override public void onLost(Network network){if(!stopping)setUnderlyingNetworks(null);}
            };
            connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);
            LiveRuntime.running=true;LiveRuntime.status="VPN 已建立 · 等待游戏登录 UDP";
            decoderWorker=new Thread(this::decodeLoop,"esp-vpn-decode");decoderWorker.start();
            final int fd=tun.getFd();final long current=handle;
            forwarder=new Thread(()->{
                int rc;
                try{rc=VpnNative.run(current,fd,this);}catch(Throwable error){rc=-100;}
                synchronized(nativeLock){if(handle==current){VpnNative.destroy(current);handle=0;}}
                if(!stopping){LiveRuntime.status="VPN 转发已退出 rc="+rc;main.post(this::stopSelf);}
            },"esp-vpn-forward");forwarder.start();
            if(Settings.canDrawOverlays(this))startForegroundService(new Intent(this,OverlayService.class).setAction(OverlayService.ACTION_START).putExtra("config","{}"));
        }catch(Exception|LinkageError error){LiveRuntime.status="启动失败："+error.getMessage();stopSelf();}
        return START_NOT_STICKY;
    }
    private void setUnderlying(Network network){
        if(stopping)return;NetworkCapabilities c=connectivity.getNetworkCapabilities(network);
        if(c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))setUnderlyingNetworks(new Network[]{network});
    }
    // Called only by the forwarding thread; ESP accounting continues to receive original bytes.
    public byte[] rewriteViewOutgoing(String peer,int local,int remote,byte[] body,double timestamp){
        return stopping||viewRewrite==null?null:viewRewrite.outgoing(peer,local,remote,body,timestamp);
    }
    public byte[] rewriteRotationIncoming(String peer,int local,int remote,byte[] body,double timestamp){
        return stopping||rotationRewrite==null?null:rotationRewrite.incoming(peer,local,remote,body,timestamp);
    }
    public void onRotationDelivered(boolean success){if(success)LiveRuntime.rotationDelivered++;else LiveRuntime.rotationDeliverFailures++;}
    public void onViewSent(boolean success){if(success)LiveRuntime.viewSent++;else LiveRuntime.viewSendFailures++;}
    public void forgetViewFlow(String peer,int local,int remote){if(viewRewrite!=null)viewRewrite.forget(peer,local,remote);if(rotationRewrite!=null)rotationRewrite.forget(peer,local,remote);}
    public boolean protectForwardSocket(int fd){return !stopping&&protect(fd);}
    public void onUdp(boolean outgoing,String peer,int local,int remote,byte[] body,double timestamp){
        if(stopping||body.length<2)return;
        // Non-game UDP is still forwarded, but never enters the protocol queue.
        int flag=body[1]&255;if((flag&15)!=4&&(flag&15)!=5)return;
        int used=queueBytes.addAndGet(body.length);
        if(used>4*1024*1024||!queue.offer(new Packet(outgoing,peer,local,remote,body,timestamp))){
            queueBytes.addAndGet(-body.length);
        }
    }
    private void decodeLoop(){
        GameDecoder decoder=new GameDecoder();GameState state=new GameState();long published=0;boolean dirty=false;
        try{
            while(!stopping){
                Packet packet=queue.poll(16,TimeUnit.MILLISECONDS);
                if(packet!=null){
                    queueBytes.addAndGet(-packet.body.length);
                    for(GameDecoder.Event event:decoder.feed(packet.outgoing,packet.peer,packet.local,packet.remote,packet.body,packet.ts)){
                        try{state.apply(event);dirty=true;}catch(Exception error){decoder.decodeErrors++;decoder.lastError=error.toString();}
                    }
                }
                long now=SystemClock.elapsedRealtime();
                if(dirty&&now-published>=16){
                    JSONObject snapshot=state.snapshot(System.currentTimeMillis()/1000.0);
                    if(stopping)break;
                    LiveRuntime.targets=NearestTarget.fromSnapshot(snapshot,System.currentTimeMillis()/1000.0);
                    LiveRuntime.snapshot=snapshot;
                                        LiveRuntime.status="VPN 运行中";
                    try {
                        org.json.JSONArray cs = snapshot.optJSONArray("camera_streams");
                        org.json.JSONArray ctx = snapshot.optJSONArray("camera_context");
                        org.json.JSONArray ents = snapshot.optJSONArray("entities");
                        int localCount = 0;
                        if (ents != null) for (int i = 0; i < ents.length(); i++) {
                            org.json.JSONObject e = ents.optJSONObject(i);
                            if (e != null && e.optBoolean("is_local")) localCount++;
                        }
                        String g = "";
                        if (ctx != null && ctx.length() > 0) {
                            org.json.JSONObject c0 = ctx.optJSONObject(0);
                            g = " gt=" + c0.optInt("gameplay_type", -1) + " gid=" + c0.optInt("gameplay_id", -1);
                        }
                        LiveRuntime.diag = "cam=" + decoder.cameraMessages
                            + " scene=" + decoder.sceneMessages
                            + " id=" + decoder.identityMessages
                            + " err=" + decoder.decodeErrors
                            + " cs=" + (cs == null ? 0 : cs.length())
                            + " ents=" + (ents == null ? 0 : ents.length())
                            + " local=" + localCount + g
                            + (decoder.lastError.isEmpty() ? "" : (" ERR:" + decoder.lastError));
                    } catch (Exception ignored) {}
                    dirty=false;published=now;
                }
            }
        }catch(InterruptedException ignored){}
        catch(Exception error){
            if(!stopping)LiveRuntime.status="解码异常："+error.getMessage();
        }
    }
    private void foreground(){
        NotificationManager nm=getSystemService(NotificationManager.class);String id="local_udp_vpn";
        nm.createNotificationChannel(new NotificationChannel(id,"本地 VPN 运行状态",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,10,new Intent(this,GameVpnService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,11,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("免 root UDP ESP · VPN")
            .setContentText("本机转发，仅超自然行动；点击停止结束 VPN 与悬浮层")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"停止 VPN",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(852,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(852,n);
    }
    @Override public void onRevoke(){stopSelf();}
    @Override public void onDestroy(){
        stopping=true;LiveRuntime.running=false;LiveRuntime.setViewEnabled(false);LiveRuntime.targets=NearestTarget.Table.EMPTY;LiveRuntime.snapshot=null;
        stopService(new Intent(this,OverlayService.class));
        synchronized(nativeLock){if(handle!=0)VpnNative.stop(handle);}
        if(decoderWorker!=null)decoderWorker.interrupt();
        if(networkCallback!=null&&connectivity!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        if(forwarder!=null){
            try{forwarder.join(1500);}catch(InterruptedException ignored){}
        }
        if(tun!=null){try{tun.close();}catch(Exception ignored){}tun=null;}
        if(forwarder==null)synchronized(nativeLock){if(handle!=0){VpnNative.destroy(handle);handle=0;}}
        queue.clear();queueBytes.set(0);stopForeground(STOP_FOREGROUND_REMOVE);
        if(!LiveRuntime.status.startsWith("启动失败")&&!LiveRuntime.status.contains("异常")&&!LiveRuntime.status.contains("rc="))LiveRuntime.status="VPN 已停止";
        super.onDestroy();
    }
}
