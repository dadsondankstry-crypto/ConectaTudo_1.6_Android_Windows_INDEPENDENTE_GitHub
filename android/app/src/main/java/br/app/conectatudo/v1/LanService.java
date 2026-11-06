package br.app.conectatudo.v1;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Environment;
import android.util.DisplayMetrics;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Servidor HTTP local. Não requer nuvem. Ativo enquanto a notificação de primeiro plano estiver presente. */
public class LanService extends Service {
    public static final String ACTION_STOP = "br.app.conectatudo.STOP";
    public static final String ACTION_PROJECTION = "br.app.conectatudo.PROJECTION";
    public static final String ACTION_STOP_SCREEN = "br.app.conectatudo.STOP_SCREEN";
    public static final String ACTION_STOP_CONTROL = "br.app.conectatudo.STOP_CONTROL";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static volatile LanService active;
    private static final String CH = "conectatudo-rede";
    private static final int NOTIF = 8107;
    private static final long MAX_FILE = 20L * 1024 * 1024 * 1024;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<JSONObject> messages = Collections.synchronizedList(new ArrayList<>());
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String,PartUpload> uploads = new ConcurrentHashMap<>();
    static class PartUpload {
        final String name;final long size;final Uri uri;long done=0;
        PartUpload(String n,long sz,Uri u){name=n;size=sz;uri=u;}
    }

    private String pin;
    private ServerSocket server;
    private DatagramSocket discoverySocket;
    private volatile boolean running = false;
    private volatile byte[] jpeg;
    private volatile boolean sharing = false;
    private volatile String controlCode;
    private volatile long controlUntil;
    private final ArrayDeque<Long> badCodes = new ArrayDeque<>();
    private volatile long frameSerial=0;
    private final Object frameLock=new Object();
    private volatile String incomingName = "";
    private volatile long incomingSize = 0L;
    private volatile long incomingDone = 0L;
    private volatile boolean incomingActive = false;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private Thread captureWorker;
    private boolean stoppingProjection;

    static class Received {
        final String name; final long size; final long time; final Uri uri;
        Received(String n,long s,long t,Uri u){name=n;size=s;time=t;uri=u;}
    }

    @Override public void onCreate() {
        super.onCreate(); active = this;
        pin = String.valueOf(100000 + random.nextInt(900000));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH,"ConectaTudo - rede local", NotificationManager.IMPORTANCE_LOW));
    }
    public String getPin(){return pin;}
    public boolean isSharing(){return sharing;}
    public boolean isRunning(){return running;}
    public synchronized String allowControl(){
        if(!sharing||RemoteAccessibilityService.instance==null)return null;
        StringBuilder b=new StringBuilder();for(int i=0;i<12;i++)b.append(random.nextInt(10));
        controlCode=b.toString();controlUntil=SystemClock.elapsedRealtime()+15*60*1000L;badCodes.clear();
        return controlCode;
    }
    public synchronized void revokeControl(){controlCode=null;controlUntil=0;badCodes.clear();}
    public boolean controlActive(){return sharing&&RemoteAccessibilityService.instance!=null&&controlCode!=null&&SystemClock.elapsedRealtime()<controlUntil;}
    private synchronized boolean checkControl(String code){
        long now=SystemClock.elapsedRealtime();
        while(!badCodes.isEmpty()&&now-badCodes.peekFirst()>60000)badCodes.removeFirst();
        if(badCodes.size()>=8||!controlActive()||code==null)return false;
        boolean good=MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),controlCode.getBytes(StandardCharsets.UTF_8));
        if(!good)badCodes.addLast(now);
        return good;
    }
    public String recentMessages(){
        StringBuilder out = new StringBuilder();
        synchronized(messages){
            for(int i=Math.max(0,messages.size()-20);i<messages.size();i++){
                JSONObject msg=messages.get(i);
                out.append("💬 ").append(msg.optString("who","Dispositivo"))
                    .append(": ").append(msg.optString("text", "")).append("\n\n");
            }
        }
        return out.length()==0?"Nenhuma mensagem recebida ainda.":out.toString();
    }
    public String recentFiles(){
        StringBuilder out=new StringBuilder("📁 Salvos em Downloads/ConectaTudo:\n");
        synchronized(received){for(int i=0;i<Math.min(18,received.size());i++){
            Received f=received.get(i);
            out.append("• ").append(f.name).append(" (").append(f.size/1024).append(" KB)\n");
        }}
        return out.toString();
    }
    public boolean isReceiving(){return incomingActive;}
    public long incomingSize(){return incomingSize;}
    public long incomingDone(){return incomingDone;}
    public String incomingName(){return incomingName;}


    private Notification notification(){
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent lock=new Intent(this,LanService.class).setAction(ACTION_STOP_CONTROL);
        PendingIntent lockPi=PendingIntent.getService(this,1,lock,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CH).addAction(new Notification.Action.Builder(
            android.R.drawable.ic_delete,"Bloquear controle",lockPi).build()).setContentTitle("ConectaTudo • rede local")
            .setContentText(sharing ? "Transferência e tela compartilhada" : "Pronto para receber arquivos no Wi-Fi")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pi).setOngoing(true).build();
    }
    private void foreground(boolean withScreen){
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if(withScreen)types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(NOTIF, notification(), types);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId){
        String action = intent == null ? "" : intent.getAction();
        if(ACTION_STOP.equals(action)){ stopScreen(); stopSelf(); return START_NOT_STICKY; }
        if(ACTION_STOP_SCREEN.equals(action)){stopScreen();return START_STICKY;}
        if(ACTION_STOP_CONTROL.equals(action)){revokeControl();return START_STICKY;}
        if(ACTION_PROJECTION.equals(action)) {
            try {
                foreground(true);
                int result = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (result != Activity.RESULT_OK || data == null) throw new IllegalArgumentException("Autorização inválida");
                startScreen(result,data);
            } catch(Exception ex) { stopScreen(); android.util.Log.e("ConectaTudo", "Tela",ex); }
        } else { foreground(sharing); }
        if(!running) startNetwork();
        return START_STICKY;
    }
    private void startNetwork(){
        running = true;
        pool.execute(()->{
            try(ServerSocket ss = new ServerSocket()){
                ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0", Net.PORT));server=ss;
                while(running){
                    Socket client=ss.accept();client.setTcpNoDelay(true);client.setSoTimeout(180000);
                    pool.execute(()->handle(client));
                }
            }catch(Exception ex){if(running)android.util.Log.e("ConectaTudo", "Porta 8765 indisponível",ex);}
            finally{server=null;running=false;}
        });
        pool.execute(this::discoveryLoop);
    }
    private void discoveryLoop(){
        try (DatagramSocket s = new DatagramSocket(null)){
            discoverySocket=s;
            s.setReuseAddress(true);s.bind(new InetSocketAddress("0.0.0.0",Net.DISCOVERY));s.setSoTimeout(1000);
            while(running){
                try{
                    byte[] b=new byte[1024];DatagramPacket p=new DatagramPacket(b,b.length);s.receive(p);
                    if(!"CT_DISCOVER_V1".equals(new String(p.getData(),0,p.getLength(),StandardCharsets.UTF_8)))continue;
                    byte[] reply=new JSONObject().put("name",android.os.Build.MODEL)
                        .put("platform","android").put("port",Net.PORT).toString().getBytes(StandardCharsets.UTF_8);
                    s.send(new DatagramPacket(reply,reply.length,p.getAddress(),p.getPort()));
                }catch(SocketTimeoutException ignored){}catch(Exception e){if(!running)break;}
            }
        }catch(Exception ignored){}finally{discoverySocket=null;}
    }
    @Override public void onDestroy(){
        running=false;revokeControl();stopScreen();
        try{if(server!=null)server.close();}catch(Exception ignored){}
        try{if(discoverySocket!=null)discoverySocket.close();}catch(Exception ignored){}
        for(PartUpload p:uploads.values())try{getContentResolver().delete(p.uri,null,null);}catch(Exception ignored){}
        uploads.clear();pool.shutdownNow();active=null;super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>8192)throw new IOException("Cabeçalho longo");}
        if(c==-1 && b.size()==0)return null;
        return b.toString("ISO-8859-1");
    }
    private static Map<String,String> params(String raw) throws UnsupportedEncodingException{
        Map<String,String> out=new HashMap<>();
        String query=raw.contains("?")?raw.substring(raw.indexOf('?')+1):"";
        for(String item:query.split("&")){
            if(item.isEmpty())continue;String[] p=item.split("=",2);
            out.put(URLDecoder.decode(p[0],"UTF-8"),URLDecoder.decode(p.length>1?p[1]:"","UTF-8"));
        }return out;
    }
    private static String safeName(String s){
        if(s==null)s="arquivo";s=s.replace('\\','/');s=s.substring(s.lastIndexOf('/')+1);
        s=s.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").replaceAll("^[. ]+|[. ]+$", "");
        return s.length()>150?s.substring(0,150):(s.isEmpty()?"arquivo":s);
    }
    private void reply(OutputStream out,int code,String type,byte[] body) throws IOException{
        String status=code==200?"OK":code==400?"Bad Request":code==403?"Forbidden":code==404?"Not Found":code==409?"Conflict":code==411?"Length Required":code==413?"Payload Too Large":"Error";
        out.write(("HTTP/1.1 "+code+" "+status+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);out.flush();
    }
    private void json(OutputStream out,int status,JSONObject j)throws IOException{
        reply(out,status,"application/json; charset=utf-8",j.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject error(String msg){try{return new JSONObject().put("error",msg);}catch(Exception e){return new JSONObject();}}
    private void handle(Socket client){
        try(Socket socket=client;InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=new BufferedOutputStream(socket.getOutputStream())){
            String request=line(in);if(request==null)return;
            String[] req=request.split(" ");if(req.length<2)return;
            String method=req[0], raw=req[1], path=raw.split("\\?",2)[0];
            long len=-1;String header;String suppliedCode="";
            while((header=line(in))!=null&&!header.isEmpty()){
                int x=header.indexOf(':');if(x<1)continue;
                if(header.substring(0,x).trim().equalsIgnoreCase("X-CT-Control"))suppliedCode=header.substring(x+1).trim();
                if(header.substring(0,x).trim().equalsIgnoreCase("Content-Length")){
                    try{len=Long.parseLong(header.substring(x+1).trim());}catch(Exception ignored){}
                }
            }
            Map<String,String> q=params(raw);
            if(method.equals("GET")&&(path.equals("/")||path.equals("/screen")||path.equals("/control"))){
                try(InputStream file=getAssets().open("painel.html")){
                    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
                    while((n=file.read(block))!=-1)b.write(block,0,n);
                    reply(out,200,"text/html; charset=utf-8",b.toByteArray());
                }return;
            }
            if(!pin.equals(q.get("pin"))){json(out,403,error("Código de acesso inválido"));return;}
            if(method.equals("GET")){
                switch(path){
                case "/api/info":json(out,200,new JSONObject().put("name",android.os.Build.MODEL).put("platform","android").put("version","1.6").put("screen",sharing).put("control",controlActive()));break;
                case "/api/messages":{
                    JSONArray a=new JSONArray();synchronized(messages){for(JSONObject m:messages)a.put(m);}json(out,200,new JSONObject().put("messages",a));break;
                }
                case "/api/files":{
                    JSONArray a=new JSONArray();synchronized(received){for(Received r:received)a.put(new JSONObject().put("name",r.name).put("size",r.size).put("time",r.time));}
                    json(out,200,new JSONObject().put("files",a));break;
                }
                case "/api/transfers":{
                    JSONArray arr=new JSONArray();
                    if(incomingActive)arr.put(new JSONObject().put("name",incomingName)
                        .put("size",incomingSize).put("done",incomingDone).put("status","receiving"));
                    for(PartUpload u:uploads.values())arr.put(new JSONObject().put("name",u.name)
                        .put("size",u.size).put("done",u.done).put("status","receiving"));
                    json(out,200,new JSONObject().put("transfers",arr));break;
                }
                case "/api/screen.mjpeg":{
                    if(!sharing){json(out,409,error("Tela não compartilhada"));break;}
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=ctframe\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.flush();
                    long lastSerial=-1;
                    while(sharing && running){
                        byte[] frame;
                        long serial;
                        synchronized(frameLock){
                          serial=frameSerial;frame=jpeg;
                          if(frame==null||serial==lastSerial){try{frameLock.wait(130);}catch(InterruptedException e){break;}continue;}
                        }
                        lastSerial=serial;
                        if(frame!=null){
                            out.write(("--ctframe\r\nContent-Type: image/jpeg\r\nContent-Length: "+frame.length+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.write(frame);out.write("\r\n".getBytes(StandardCharsets.UTF_8));out.flush();
                        }
                        
                    }break;
                }
                case "/api/control/status":{json(out,200,new JSONObject().put("enabled",controlActive()));break;}
                case "/api/upload/status":{
                    PartUpload up=uploads.get(q.get("id"));
                    if(up==null){json(out,404,error("Envio não encontrado"));break;}
                    json(out,200,new JSONObject().put("id",q.get("id")).put("offset",up.done).put("size",up.size));break;
                }
                case "/api/screen.jpg":{
                    byte[] frame=jpeg;if(!sharing||frame==null){json(out,409,error("Tela não está sendo compartilhada"));break;}
                    reply(out,200,"image/jpeg",frame);break;
                }
                case "/api/download":{
                    String name=safeName(q.get("name"));Received match=null;
                    synchronized(received){for(Received r:received)if(r.name.equals(name)){match=r;break;}}
                    if(match==null){json(out,404,error("Arquivo não encontrado"));break;}
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+match.size+"\r\nConnection: close\r\nContent-Disposition: attachment; filename*=UTF-8''"+Uri.encode(match.name)+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    try(InputStream file=getContentResolver().openInputStream(match.uri)){
                        if(file==null)throw new IOException("Sem acesso ao arquivo");byte[] buf=new byte[131072];int n;
                        while((n=file.read(buf))!=-1)out.write(buf,0,n);
                    }out.flush();break;
                }
                default:json(out,404,error("Rota desconhecida"));
                }
            }else if(method.equals("POST")){
                if(len<0){json(out,411,error("Content-Length é obrigatório"));return;}
                switch(path){
                case "/api/upload/init":{
                    if(len>4096){json(out,413,error("Metadados muito grandes"));break;}
                    byte[] b=new byte[(int)len];new DataInputStream(in).readFully(b);
                    JSONObject data=new JSONObject(new String(b,StandardCharsets.UTF_8));
                    String id=data.optString("id"), name=safeName(data.optString("name"));
                    long size=data.optLong("size",-1);
                    if(!id.matches("[A-Za-z0-9_-]{10,64}")||size<0||size>MAX_FILE){json(out,400,error("ID/tamanho inválido"));break;}
                    PartUpload up=uploads.get(id);
                    if(up!=null && (!up.name.equals(name)||up.size!=size)){json(out,409,error("ID duplicado"));break;}
                    if(up==null){
                        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ConectaTudo");
                        values.put(MediaStore.MediaColumns.IS_PENDING,1);
                        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                        if(uri==null){json(out,500,error("Não foi possível criar arquivo"));break;}
                        PartUpload fresh=new PartUpload(name,size,uri);
                        PartUpload prior=uploads.putIfAbsent(id,fresh);
                        if(prior!=null){getContentResolver().delete(uri,null,null);up=prior;}
                        else up=fresh;
                    }
                    json(out,200,new JSONObject().put("id",id).put("offset",up.done).put("size",up.size));break;
                }
                case "/api/upload/chunk":{
                    if(len<=0||len>1024*1024){json(out,413,error("Partes de até 1 MB"));break;}
                    PartUpload up=uploads.get(q.get("id"));
                    if(up==null){json(out,404,error("Envio não iniciado"));break;}
                    long offset=-1;try{offset=Long.parseLong(q.get("offset"));}catch(Exception ignored){}
                    synchronized(up){
                        if(offset!=up.done||offset+len>up.size){json(out,409,error("Posição incorreta; consulte status"));break;}
                        byte[] part=new byte[(int)len];new DataInputStream(in).readFully(part);
                        try(android.os.ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(up.uri,"rw")){
                            if(pfd==null)throw new IOException("Arquivo indisponível");
                            try(FileOutputStream dst=new FileOutputStream(pfd.getFileDescriptor())){
                                dst.getChannel().position(offset);dst.write(part);dst.flush();
                            }
                        }
                        up.done+=len;
                        json(out,200,new JSONObject().put("offset",up.done));
                    }break;
                }
                case "/api/upload/finish":{
                    String id=q.get("id");PartUpload up=uploads.get(id);
                    if(up==null){json(out,404,error("Envio não encontrado"));break;}
                    synchronized(up){
                        if(up.done!=up.size){json(out,409,error("Arquivo incompleto"));break;}
                        ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);
                        getContentResolver().update(up.uri,done,null,null);
                        synchronized(received){received.add(0,new Received(up.name,up.size,System.currentTimeMillis(),up.uri));while(received.size()>80)received.remove(received.size()-1);}
                        uploads.remove(id);json(out,200,new JSONObject().put("ok",true).put("name",up.name));
                    }break;
                }
                case "/api/upload/cancel":{
                    PartUpload up=uploads.remove(q.get("id"));
                    if(up!=null)getContentResolver().delete(up.uri,null,null);
                    json(out,200,new JSONObject().put("ok",true));break;
                }
                case "/api/control/input":{
                    if(len>2048){json(out,413,error("Comando muito grande"));break;}
                    if(!checkControl(suppliedCode)){json(out,403,error("Controle não autorizado. Ative por 15 min no Android e informe o código extra."));break;}
                    byte[] cmd=new byte[(int)len];new DataInputStream(in).readFully(cmd);
                    JSONObject requestCmd=new JSONObject(new String(cmd,StandardCharsets.UTF_8));
                    RemoteAccessibilityService accessibility=RemoteAccessibilityService.instance;
                    if(accessibility==null||!accessibility.queue(requestCmd)){json(out,400,error("Acessibilidade desativada ou ação inválida"));break;}
                    json(out,200,new JSONObject().put("ok",true));break;
                }
                case "/api/message":{
                    if(len>65536){json(out,413,error("Texto muito grande"));break;}
                    byte[] b=new byte[(int)len];DataInputStream di=new DataInputStream(in);di.readFully(b);
                    String text=new String(b,StandardCharsets.UTF_8).trim();
                    if(text.isEmpty()){json(out,400,error("Texto vazio"));break;}
                    synchronized(messages){messages.add(new JSONObject().put("who","Outro dispositivo").put("text",text).put("time",System.currentTimeMillis()));
                        while(messages.size()>150)messages.remove(0);}
                    json(out,200,new JSONObject().put("ok",true));break;
                }
                case "/api/send":{
                    if(len>MAX_FILE){json(out,413,error("Limite de 20 GB"));break;}
                    String name=safeName(q.get("name"));
                    Uri uri=null;boolean complete=false;
                    try{
                        ContentValues values=new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ConectaTudo");
                        values.put(MediaStore.MediaColumns.IS_PENDING,1);
                        uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                        if(uri==null)throw new IOException("Sem acesso ao armazenamento de downloads");
                        incomingName=name;incomingSize=len;incomingDone=0;incomingActive=true;
                        try(OutputStream file=getContentResolver().openOutputStream(uri)){
                            if(file==null)throw new IOException("Não foi possível criar o arquivo");
                            long left=len;byte[] b=new byte[131072];
                            while(left>0){int n=in.read(b,0,(int)Math.min((long)b.length,left));if(n<0)throw new EOFException("Transferência interrompida");file.write(b,0,n);left-=n;incomingDone+=n;}
                        }
                        ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
                        getContentResolver().update(uri,ready,null,null);
                        String actualName=name;
                        try(Cursor cur=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                            if(cur!=null&&cur.moveToFirst())actualName=cur.getString(0);
                        }catch(Exception ignored){}
                        synchronized(received){received.add(0,new Received(actualName,len,System.currentTimeMillis(),uri));while(received.size()>80)received.remove(received.size()-1);}
                        complete=true;
                        json(out,200,new JSONObject().put("ok",true).put("name",actualName));
                    }finally{incomingActive=false;if(!complete&&uri!=null)getContentResolver().delete(uri,null,null);}
                    break;
                }
                default:json(out,404,error("Rota desconhecida"));
                }
            }else json(out,400,error("Método não suportado"));
        }catch(Exception ex){android.util.Log.w("ConectaTudo","Conexão finalizada: "+ex.getMessage());}
    }

    /** A captura da tela somente começa após aprovação do diálogo do Android. */
    private synchronized void startScreen(int code,Intent data){
        stopScreen();stoppingProjection=false;
        foreground(true);
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=m.getMediaProjection(code,data);
        if(projection==null)return;
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){
            new Handler(Looper.getMainLooper()).post(()->stopScreen());
        }},new Handler(Looper.getMainLooper()));
        DisplayMetrics dm=getResources().getDisplayMetrics();
        String quality=getSharedPreferences("config",MODE_PRIVATE).getString("screen_quality","Equilibrado");
        int maxEdge=quality.equals("Econômico")?1080:quality.equals("Nítido")?1800:quality.equals("Baixa latência")?1120:1450;
        int jpegQuality=quality.equals("Econômico")?63:quality.equals("Nítido")?85:quality.equals("Baixa latência")?62:77;
        int delay=quality.equals("Econômico")?170:quality.equals("Nítido")?140:quality.equals("Baixa latência")?85:110;
        float ratio=Math.min(1f,((float)maxEdge)/Math.max(dm.widthPixels,dm.heightPixels));
        int w=Math.max(2,((int)(dm.widthPixels*ratio))&~1);
        int h=Math.max(2,((int)(dm.heightPixels*ratio))&~1);
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
        display=projection.createVirtualDisplay("ConectaTudo",w,h,dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
        sharing=true;foreground(true);
        final ImageReader target=reader;
        captureWorker=new Thread(()->{
            while(sharing&&!Thread.currentThread().isInterrupted()){
                Image img=null;
                try{
                    img=target.acquireLatestImage();
                    if(img!=null){
                        Image.Plane plane=img.getPlanes()[0];ByteBuffer buf=plane.getBuffer();
                        int pixel=plane.getPixelStride(),row=plane.getRowStride();
                        Bitmap padded=Bitmap.createBitmap(w+Math.max(0,(row-w*pixel)/pixel),h,Bitmap.Config.ARGB_8888);
                        padded.copyPixelsFromBuffer(buf);
                        Bitmap crop=Bitmap.createBitmap(padded,0,0,w,h);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        crop.compress(Bitmap.CompressFormat.JPEG,jpegQuality,bytes);
                        synchronized(frameLock){jpeg=bytes.toByteArray();frameSerial++;frameLock.notifyAll();}
                        crop.recycle();padded.recycle();
                    }
                    Thread.sleep(delay);
                }catch(Exception ignored){}finally{if(img!=null)img.close();}
            }
        },"ConectaTudo-screen");captureWorker.start();
    }
    public synchronized void stopScreen(){
        if(stoppingProjection)return;stoppingProjection=true;sharing=false;revokeControl();
        synchronized(frameLock){jpeg=null;frameLock.notifyAll();}
        if(captureWorker!=null){captureWorker.interrupt();captureWorker=null;}
        if(display!=null){display.release();display=null;}
        if(reader!=null){reader.close();reader=null;}
        if(projection!=null){MediaProjection p=projection;projection=null;try{p.stop();}catch(Exception ignored){}}
        if(running)try{foreground(false);}catch(Exception ignored){}
        stoppingProjection=false;
    }
}
