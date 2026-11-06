package br.app.conectatudo.v1;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;


/** Tela nativa; protocolo: HTTP local porta 8765, PIN de seis dígitos. */
public class MainActivity extends Activity {
    private static final int PICK=200, SCREEN=201, QR_SCAN=202;
    private LinearLayout page;
    private TextView status, serviceState, ipLabel, pinLabel, netHint, receivedLabel, messagesLabel, transferText;
    private ProgressBar transferBar;
    private volatile boolean sending = false;
    private EditText remoteIp, remotePin, sendText;
    private Spinner screenQuality;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable periodic = new Runnable(){@Override public void run(){updateLocal();}};
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newCachedThreadPool();
    private int accent=Color.rgb(110,178,255), bg=Color.rgb(13,23,40), card=Color.rgb(27,40,62);
    private android.content.SharedPreferences prefs;

    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("config",MODE_PRIVATE);build();readDeeplink(getIntent());
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
        // Inicia somente quando o usuário abre o aplicativo; a notificação avisa que o servidor está ativo.
        launchService(null);ui.postDelayed(periodic,800);
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);readDeeplink(i);}
    @Override protected void onDestroy(){ui.removeCallbacks(periodic);worker.shutdownNow();super.onDestroy();}

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(15));return g;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private void addTitle(String s){TextView t=text(s,18,accent);t.setTypeface(null,1);t.setPadding(0,dp(6),0,dp(10));page.addView(t);}
    private LinearLayout box(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(14),dp(16),dp(14));l.setBackground(rounded(card));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(13);page.addView(l,p);return l;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(14);
        GradientDrawable normal=rounded(Color.rgb(42,109,205));
        GradientDrawable pressed=rounded(Color.rgb(20,77,155));
        android.graphics.drawable.RippleDrawable ripple=new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(120,210,234,255)),normal,null);
        b.setBackground(ripple);b.setElevation(dp(2));b.setHapticFeedbackEnabled(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(7);b.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);action.run();});b.setLayoutParams(p);return b;}
    private EditText input(String hint,String initial){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(160,174,199));e.setHint(hint);e.setText(initial);e.setPadding(dp(11),dp(9),dp(11),dp(9));
        e.setBackground(rounded(Color.rgb(12,25,43)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(51));p.topMargin=dp(5);p.bottomMargin=dp(6);e.setLayoutParams(p);return e;}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(15),dp(14),dp(15),dp(35));scroll.addView(page);setContentView(scroll);
        TextView title=text("🔗 ConectaTudo 1.6",26,accent);title.setTypeface(null,1);page.addView(title);
        page.addView(text("POCO ↔ Tablet ↔ Legion Go  •  Wi-Fi local sem internet",13,Color.rgb(174,190,210)));
        serviceState=text("Preparando servidor...",13,Color.rgb(135,230,168));page.addView(serviceState);
        status=text("Aguardando ações.",13,Color.rgb(174,190,210));page.addView(status);
        addTitle("📡 Este aparelho • Receber");
        LinearLayout own=box();own.addView(text("Conecte os aparelhos à mesma rede Wi-Fi ou hotspot.",14,Color.WHITE));
        ipLabel=text("Endereço local: detectando...",15,Color.WHITE);ipLabel.setTextIsSelectable(true);own.addView(ipLabel);
        pinLabel=text("PIN de acesso: ...",21,Color.rgb(143,229,171));pinLabel.setTypeface(null,1);pinLabel.setTextIsSelectable(true);own.addView(pinLabel);
        netHint=text("📶 Para receber do computador, use o mesmo Wi-Fi/hotspot ou uma VPN privada. IP da rede móvel sozinho pode não aceitar conexões.",12,Color.rgb(255,213,143));own.addView(netHint);
        own.addView(button("🔎 Diagnosticar meus endereços e a conexão",this::diagnoseNetwork));
        own.addView(button("📷 Mostrar QR para outro celular",this::showQr));
        own.addView(button("📋 Copiar meu endereço",this::copyAddress));
        own.addView(button("🌐 Abrir meu painel no navegador",this::ownPanel));
        own.addView(button("📶 Configurar Wi-Fi / Hotspot",()->startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS))));
        own.addView(button("▶ Iniciar meu servidor",()->launchService(null)));
        own.addView(button("⏹️ Parar meu servidor",()->{launchService(LanService.ACTION_STOP);status.setText("Servidor encerrado. Abra novamente o app para iniciar.");}));
        addTitle("🔗 Conectar a outro dispositivo");
        LinearLayout link=box();link.addView(text("Para conectar: use o IP e PIN mostrados no outro aparelho. A busca automática funciona na mesma rede local; no 5G use VPN privada nos dois.",14,Color.WHITE));
        remoteIp=input("IP do outro aparelho (ex.: 192.168.43.25)",prefs.getString("peer_ip",""));link.addView(remoteIp);
        remotePin=input("PIN de 6 números",prefs.getString("peer_pin",""));remotePin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);link.addView(remotePin);
        link.addView(button("🔎 Procurar dispositivos próximos",this::search));
        link.addView(button("📷 Ler QR de outro aparelho",this::scanQr));
        link.addView(button("✅ Testar conexão",this::connect));
        link.addView(button("🌐 Abrir painel do outro aparelho",()->openRemote("/")));
        addTitle("📤 Compartilhar arquivos e textos");
        LinearLayout send=box();send.addView(button("📂 Escolher e enviar arquivos",this::pickFiles));
        sendText=input("Digite uma mensagem de texto...","");send.addView(sendText);
        send.addView(button("💬 Enviar texto",this::sendMessage));
        transferText=text("📦 Nenhuma transferência ativa.",13,Color.rgb(132,220,172));send.addView(transferText);
        transferBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        transferBar.setMax(1000);transferBar.setProgress(0);send.addView(transferBar,new LinearLayout.LayoutParams(-1,dp(12)));
        send.addView(text("💬 Mensagens recebidas • atualização automática",14,accent));
        messagesLabel=text("Nenhuma mensagem recebida ainda.",13,Color.rgb(197,211,230));send.addView(messagesLabel);
        send.addView(text("📥 Arquivos recebidos • atualização automática",14,accent));
        receivedLabel=text("Arquivos recebidos são salvos em Downloads/ConectaTudo.",13,Color.rgb(197,211,230));send.addView(receivedLabel);
        addTitle("🖥️ Compartilhamento de tela");
        LinearLayout screen=box();screen.addView(text("Compartilhar minha tela exige confirmação do Android. Para ver a tela do Windows, habilite a opção no EXE.",14,Color.WHITE));
        screen.addView(text("Priorize nitidez ou velocidade. A transmissão é MJPEG otimizado, não vídeo H.264.",13,Color.rgb(180,198,219)));
        screenQuality=new Spinner(this);String[] qualities={"Equilibrado", "Baixa latência", "Econômico", "Nítido"};
        ArrayAdapter<String> qa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,qualities);
        qa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);screenQuality.setAdapter(qa);
        String oldQuality=prefs.getString("screen_quality","Equilibrado");
        for(int i=0;i<qualities.length;i++)if(qualities[i].equals(oldQuality))screenQuality.setSelection(i);
        screen.addView(screenQuality);
        screen.addView(button("▶ Compartilhar minha tela",()->{
            prefs.edit().putString("screen_quality",screenQuality.getSelectedItem().toString()).apply();
            askProjection();
        }));
        screen.addView(button("⏹ Parar compartilhamento",()->launchService(LanService.ACTION_STOP_SCREEN)));
        screen.addView(button("👁️ Ver tela do outro dispositivo",()->openRemote("/screen")));
        screen.addView(button("📺 Modo tablet: espelhar tela do Legion Go",()->openRemote("/tablet")));
        screen.addView(text("MODO TABLET: no Legion Go ative Compartilhar tela; conecte o Redmi Pad 2 ao mesmo Wi-Fi/hotspot, informe o IP/PIN do Windows e toque no botão acima. Espelha a tela existente, NÃO cria monitor estendido.",12,Color.rgb(201,223,247)));
        screen.addView(button("🖱️ Controlar aparelho remoto (Windows ou Android)",()->openRemote("/control")));
        screen.addView(text("📱 PARA CONTROLAR ESTE ANDROID: ative o serviço de acessibilidade nas configurações, compartilhe sua tela e permita o controle temporário. O código extra será exibido somente aqui.",13,Color.rgb(200,222,248)));
        screen.addView(button("♿ Habilitar controle Android (acessibilidade)",()->{
            dialog("Permissão especial","Ative ConectaTudo — controle remoto autorizado nas configurações de Acessibilidade. O serviço só recebe comandos após você ativar o compartilhamento de tela e permitir o controle por 15 minutos. Não use em redes públicas sem VPN privada.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        screen.addView(button("✅ Permitir controle deste Android por 15 minutos",this::enableAndroidControl));
        screen.addView(button("⛔ Bloquear controle deste Android agora",()->{
            LanService s=LanService.active;if(s!=null)s.revokeControl();
            launchService(LanService.ACTION_STOP_CONTROL);toast("Controle do Android bloqueado.");
        }));
        addTitle("🧭 Como conectar e opções");
        LinearLayout help=box();help.addView(text("📶 OFFLINE: mesmo Wi-Fi/hotspot → Procurar → digitar PIN.\n\n🌐 ONLINE (VPN privada): instale e conecte uma VPN de malha como Tailscale nos dois aparelhos. Informe o IP privado da VPN (normalmente 100.x.x.x) e o PIN. A busca automática só encontra dispositivos na mesma rede local. Não abra a porta 8765 no roteador.\n\n🖱️ CONTROLE NOS DOIS SENTIDOS: para controlar Windows, habilite Compartilhar tela no Legion Go e autorize por 15 minutos. Para controlar Android, habilite Acessibilidade no aparelho Android, Compartilhar minha tela e depois Permitir controle por 15 minutos. Digite o código extra de 12 números no painel remoto do aparelho controlador.",13,Color.rgb(214,229,247)));
        help.addView(button("🔐 Abrir configurações de VPN",()->startActivity(new Intent(Settings.ACTION_VPN_SETTINGS))));
        addTitle("ℹ️ Recursos e segurança");
        LinearLayout info=box();info.addView(text("✅ Transferência local, mensagens automáticas, QR, visualização MJPEG e controle temporário de Windows/Android mediante autorização e código adicional.\n\n📱 Controle Android só funciona se a Acessibilidade tiver sido ativada e a tela compartilhada estiver autorizada. O código expira em 15 minutos.\n\n🌐 Acesso entre redes funciona quando uma VPN privada externa (ex.: Tailscale) já conecta os dispositivos. Não há servidor público integrado.\n\n🔒 HTTP não possui criptografia própria: use hotspot confiável ou VPN privada, nunca encaminhe a porta 8765 para a internet. Revogue o controle remoto no computador ao terminar.\n\nℹ️ Controle Android é experimental e depende da autorização de acessibilidade e da captura de tela; algumas ações são bloqueadas pelo sistema.",12,Color.rgb(194,204,222)));
    }
    private void updateLocal(){
        LanService s=LanService.active;
        // IP stays visible even when the server is stopped, so users can diagnose routing.
        List<String> ips=Net.ownIps();
        ipLabel.setText("📍 IP(s) deste aparelho: "+(ips.isEmpty()?"sem endereço; ative Wi-Fi/hotspot":android.text.TextUtils.join("  •  ",ips)));
        if(s!=null) pinLabel.setText("🔑 PIN deste aparelho: "+s.getPin());
        else pinLabel.setText("🔑 PIN: inicie o servidor para gerar o código");
        if(s!=null&&s.isRunning()){
            serviceState.setText("🟢 Servidor ativo • Porta 8765"+(s.isSharing()?" • Tela ativa":""));
            String messages=s.recentMessages();if(!messages.toString().contentEquals(messagesLabel.getText()))messagesLabel.setText(messages);
            String files=s.recentFiles();if(!files.contentEquals(receivedLabel.getText()))receivedLabel.setText(files);
            if(!sending&&s.isReceiving()){
                updateProgressDisplay("📥 Recebendo: "+s.incomingName(),s.incomingDone(),s.incomingSize(),0);
            } else if(!sending && transferText.getText().toString().startsWith("📥")) {
                transferText.setText("✅ Arquivo recebido. Confira a lista abaixo.");transferBar.setProgress(1000);
            }
        }else{serviceState.setText("🟠 Servidor parado / iniciando • Não é possível receber até iniciar.");}
        ui.postDelayed(periodic,1300);
    }
    private void launchService(String action){
        Intent i=new Intent(this,LanService.class);if(action!=null)i.setAction(action);
        if(LanService.ACTION_STOP.equals(action)||LanService.ACTION_STOP_SCREEN.equals(action))startService(i);
        else startForegroundService(i);
    }
    private String peerIp(){return Net.cleanIp(remoteIp.getText().toString());}
    private String peerPin(){String p=remotePin.getText().toString().trim();if(!p.matches("\\d{6}"))throw new IllegalArgumentException("Informe PIN de 6 números");return p;}
    private void savePeer(){prefs.edit().putString("peer_ip",peerIp()).putString("peer_pin",peerPin()).apply();}
    private void job(String action,Task task){status.setText("⏳ "+action+"...");worker.execute(()->{try{String msg=task.call();ui.post(()->{status.setText("✅ "+msg);toast(msg);});}
        catch(Exception ex){ui.post(()->{status.setText("❌ "+action+": "+ex.getMessage());dialog("Erro",ex.getMessage());});}});}
    interface Task{String call()throws Exception;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void dialog(String title,String msg){new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
    private void enableAndroidControl(){
        LanService s=LanService.active;
        if(s==null||!s.isSharing()){dialog("Compartilhamento desligado","Primeiro toque em Compartilhar minha tela e aprove a janela do Android.");return;}
        if(RemoteAccessibilityService.instance==null){
            dialog("Acessibilidade desligada","Ative ConectaTudo — controle remoto autorizado nas configurações de Acessibilidade e volte aqui.");return;
        }
        String code=s.allowControl();if(code==null){toast("Não foi possível autorizar o controle.");return;}
        new AlertDialog.Builder(this).setTitle("Controle autorizado por 15 minutos")
            .setMessage("Código extra para digitar no Legion Go: "+code+"\n\nUse também o PIN de conexão. Para bloquear, toque em Bloquear controle ou na notificação do ConectaTudo.")
            .setPositiveButton("Entendi",null).show();
    }
    private void diagnoseNetwork(){
        LanService s=LanService.active;
        String state=s!=null&&s.isRunning()?"ATIVO, porta 8765, pronto para receber":"PARADO ou iniciando; toque Iniciar meu servidor";
        String message="Servidor: "+state+"\n\nInterfaces encontradas:\n"+Net.interfaceInfo()
            +"\nATENÇÃO: IP da operadora 5G/4G geralmente não pode ser acessado de outro aparelho por causa de CGNAT."
            +"\n\nSem internet: ligue hotspot no celular e conecte o Legion Go à rede dele; use o IP marcado Wi-Fi/hotspot."
            +"\n\nCom internet, em redes diferentes: ative a mesma VPN privada nos dois e use o endereço da VPN."
            +"\n\nSe conectar mas não controlar: ative compartilhamento de tela, acessibilidade (no Android) e controle por 15 minutos no receptor.";
        new AlertDialog.Builder(this).setTitle("Diagnóstico de rede • ConectaTudo 1.6")
            .setMessage(message).setPositiveButton("OK",null).setNeutralButton("Copiar diagnóstico",(d,w)->{
                ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Diagnóstico ConectaTudo",message));
                toast("Diagnóstico copiado");
            }).show();
    }
    private void copyAddress(){LanService s=LanService.active;if(s==null)return;
        List<String> ips=Net.ownIps();if(ips.isEmpty()){dialog("Sem endereço","Ative Wi-Fi/hotspot antes de copiar um endereço de conexão.");return;}
        String[] opts=ips.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Escolha o IP que o outro aparelho consegue acessar")
            .setItems(opts,(d,i)->{
                String detail="IP: "+opts[i]+"\nPIN: "+s.getPin()+"\nPorta: 8765";
                ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ConectaTudo",detail));
                toast("IP e PIN copiados. Use Wi-Fi/hotspot ou VPN privada.");
            }).show();}
    private void ownPanel(){LanService s=LanService.active;if(s==null){toast("Inicie o servidor");return;}
        visit(Net.url("127.0.0.1","/",s.getPin()));}
    private void visit(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){dialog("Navegador",e.toString());}}
    private void showQr(){LanService s=LanService.active;if(s==null){toast("Servidor ainda não iniciado");return;}
        List<String> ips=Net.ownIps();if(ips.isEmpty()){dialog("Sem rede","Conecte-se ao Wi-Fi ou ative o hotspot.");return;}
        String[] opts=ips.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("IP para o outro aparelho (Wi-Fi/hotspot ou VPN)")
            .setMessage("Um IP da rede móvel pode não funcionar para receber conexões. Consulte Diagnosticar meus endereços.")
            .setItems(opts,(d,which)->drawQr(opts[which],s.getPin())).show();
    }
    private void drawQr(String ip,String pin){
        try{
            String uri="conectatudo://connect?ip="+Uri.encode(ip)+"&pin="+Uri.encode(pin);
            BitMatrix matrix=new MultiFormatWriter().encode(uri,BarcodeFormat.QR_CODE,540,540);
            int w=matrix.getWidth(),h=matrix.getHeight();Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)b.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
            ImageView img=new ImageView(this);img.setImageBitmap(b);img.setPadding(dp(12),dp(12),dp(12),dp(12));
            new AlertDialog.Builder(this).setTitle("Escaneie com outro Android").setMessage("Ou digite IP: "+ip+" • PIN: "+pin)
                .setView(img).setPositiveButton("Fechar",null).show();
        }catch(Exception e){dialog("QR Code",e.getMessage());}
    }
    private void scanQr(){
        try { startActivityForResult(new Intent(this,QrScannerActivity.class),QR_SCAN); }
        catch(Exception e){dialog("Ler QR", "Não foi possível abrir o leitor: "+e.getMessage()+". Use o IP e o PIN.");}
    }
    private void readDeeplink(Intent i){if(i==null||i.getData()==null)return;Uri data=i.getData();
        if(data.getScheme()!=null&&data.getScheme().matches("conectatudo(\\d+)?")&&"connect".equals(data.getHost())){
            String ip=data.getQueryParameter("ip"),pin=data.getQueryParameter("pin");
            if(ip!=null)remoteIp.setText(ip);if(pin!=null)remotePin.setText(pin);
            toast("Dados do QR preenchidos. Toque em Testar conexão.");
        }
    }
    private void connect(){final String ip,pin;try{ip=peerIp();pin=peerPin();savePeer();}catch(Exception e){dialog("Conexão",e.getMessage());return;}
        job("Conectando",()->{JSONObject obj=new JSONObject(Net.get(Net.url(ip,"/api/info",pin)));
            return "Conectado a "+obj.optString("name",ip)+" • "+obj.optString("platform");});}
    private void search(){job("Procurando na rede",()->{
        List<JSONObject> devices=Net.discover(getApplicationContext());
        ui.post(()->{
            if(devices.isEmpty()){dialog("Busca","Nenhum aparelho encontrado. Informe o IP manualmente. Ambos devem estar no mesmo Wi-Fi/hotspot.");return;}
            String[] names=new String[devices.size()];
            for(int n=0;n<devices.size();n++)names[n]=devices.get(n).optString("name")+" — "+devices.get(n).optString("ip");
            new AlertDialog.Builder(this).setTitle("Selecione um dispositivo").setItems(names,(d,idx)->{
                remoteIp.setText(devices.get(idx).optString("ip"));toast("Digite o PIN mostrado no aparelho escolhido.");
            }).show();
        });return devices.size()+" dispositivo(s) encontrado(s)";
    });}
    private void openRemote(String path){try{String ip=peerIp(),pin=peerPin();savePeer();visit(Net.url(ip,path,pin));}
        catch(Exception e){dialog("Dispositivo remoto",e.getMessage());}}
    private void pickFiles(){try{peerIp();peerPin();savePeer();}catch(Exception e){dialog("Antes de enviar",e.getMessage());return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);
    }
    private static final class SelectedFile {
        final Uri uri; final String name; final long size;
        SelectedFile(Uri u,String n,long s){uri=u;name=n;size=s;}
    }
    private String readableSize(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024L*1024)return String.format(Locale.getDefault(),"%.1f KB",bytes/1024d);
        if(bytes<1024L*1024*1024)return String.format(Locale.getDefault(),"%.1f MB",bytes/1048576d);
        return String.format(Locale.getDefault(),"%.2f GB",bytes/1073741824d);
    }
    private void updateProgressDisplay(String name,long done,long total,double speed){
        if(transferBar==null||transferText==null)return;
        int fraction=total<=0?0:(int)Math.min(1000,(done*1000d)/total);
        transferBar.setProgress(fraction);
        String txt=name+"\n"+readableSize(done)+" / "+readableSize(total)
            +" • "+(int)(fraction/10d)+"%";
        if(speed>0){txt+=" • "+readableSize((long)speed)+"/s";
            if(total>done)txt+=" • "+Math.max(1,(int)((total-done)/speed))+" s restantes";}
        transferText.setText(txt);
    }
    private void sendFiles(Intent data){
        ArrayList<Uri> sources=new ArrayList<>();
        if(data.getClipData()!=null){
            ClipData clips=data.getClipData();
            for(int k=0;k<clips.getItemCount();k++)sources.add(clips.getItemAt(k).getUri());
        } else if(data.getData()!=null)sources.add(data.getData());
        if(sources.isEmpty())return;
        final String ip=peerIp(),pin=peerPin();
        if(sending){toast("Aguarde a transferência atual terminar.");return;}
        sending=true;
        transferText.setText("⏳ Preparando "+sources.size()+" arquivo(s)...");transferBar.setProgress(0);
        worker.execute(()->{
            try{
                ArrayList<SelectedFile> selected=new ArrayList<>();long total=0;
                for(Uri uri:sources){
                    String name="arquivo";long size=-1;
                    try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
                        if(c!=null&&c.moveToFirst()){
                            int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),z=c.getColumnIndex(OpenableColumns.SIZE);
                            if(n>=0&&c.getString(n)!=null)name=c.getString(n);
                            if(z>=0&&!c.isNull(z))size=c.getLong(z);
                        }
                    }
                    if(size<0){try(InputStream stream=getContentResolver().openInputStream(uri)){
                        if(stream==null)throw new IOException("Arquivo indisponível");
                        byte[] tmp=new byte[131072];long n=0;int z;
                        while((z=stream.read(tmp))!=-1)n+=z;size=n;
                    }}
                    selected.add(new SelectedFile(uri,name,size));total+=size;
                }
                final long groupTotal=total;
                long completed=0;
                long started=SystemClock.elapsedRealtime();
                long lastUi=0;
                int number=0;
                for(SelectedFile file:selected){
                    number++;
                    final int fileIndex=number;
                    final long already=completed;
                    ui.post(()->updateProgressDisplay("📤 Enviando "+fileIndex+"/"+selected.size()+": "+file.name,already,groupTotal,0));
                    String url=Net.url(ip,"/api/send?name="+Uri.encode(file.name),pin);
                    HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
                    conn.setRequestMethod("POST");conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);conn.setReadTimeout(240000);
                    conn.setRequestProperty("Content-Type","application/octet-stream");
                    conn.setFixedLengthStreamingMode(file.size);
                    try{
                        try(InputStream in=getContentResolver().openInputStream(file.uri);OutputStream out=conn.getOutputStream()){
                            if(in==null)throw new IOException("Arquivo não encontrado");
                            byte[] buf=new byte[131072];int n;
                            while((n=in.read(buf))!=-1){
                                out.write(buf,0,n);completed+=n;
                                long now=SystemClock.elapsedRealtime();
                                if(now-lastUi>170 || completed==groupTotal){
                                    long done=completed;
                                    double speed=done/Math.max(.25,(now-started)/1000d);
                                    String label="📤 Enviando "+fileIndex+"/"+selected.size()+": "+file.name;
                                    ui.post(()->updateProgressDisplay(label,done,groupTotal,speed));
                                    lastUi=now;
                                }
                            }
                        }
                        Net.read(conn);
                    }finally{conn.disconnect();}
                }
                ui.post(()->{updateProgressDisplay("✅ Arquivos enviados ("+selected.size()+")",groupTotal,groupTotal,0);
                    status.setText("✅ Transferência concluída.");});
            }catch(Exception e){ui.post(()->{status.setText("❌ Falha ao transferir");dialog("Transferência de arquivos",e.getMessage());});}
            finally{sending=false;}
        });
    }
    private void sendMessage(){final String msg=sendText.getText().toString().trim();if(msg.isEmpty())return;
        try{String ip=peerIp(),pin=peerPin();savePeer();job("Enviando mensagem",()->{
            Net.post(Net.url(ip,"/api/message",pin),msg.getBytes(StandardCharsets.UTF_8));ui.post(()->sendText.setText(""));return "Mensagem entregue";
        });}catch(Exception e){dialog("Mensagem",e.getMessage());}}
    private void askProjection(){
        LanService s=LanService.active;if(s==null){dialog("Compartilhamento","Inicie o servidor antes.");return;}
        MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(pm.createScreenCaptureIntent(),SCREEN);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SCREEN){
            if(result==RESULT_OK&&data!=null){
                Intent i=new Intent(this,LanService.class);i.setAction(LanService.ACTION_PROJECTION);
                i.putExtra(LanService.EXTRA_RESULT_CODE,result);i.putExtra(LanService.EXTRA_RESULT_DATA,data);
                startForegroundService(i);toast("Tela autorizada. Pode abrir 'Ver tela' no outro dispositivo.");
            }return;
        }
        if(request==PICK){if(result==RESULT_OK&&data!=null){try{sendFiles(data);}catch(Exception e){dialog("Arquivo",e.getMessage());}}return;}
        if(request==QR_SCAN && result==RESULT_OK && data!=null){
            String raw=data.getStringExtra("qr_content");
            if(raw==null)return;
            Uri u=Uri.parse(raw);
            if(u.getScheme()!=null&&u.getScheme().matches("conectatudo(\\d+)?")){
                readDeeplink(new Intent(Intent.ACTION_VIEW,u));connect();
            }else dialog("QR Code","Este código não é do ConectaTudo.");
        }
    }
}
