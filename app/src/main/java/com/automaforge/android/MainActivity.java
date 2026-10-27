package com.automaforge.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/** Android is the controller and the HTTP/WS server. Other copies of the APK act as satellites. */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private LanServer server;
    private WebView web;
    private TextToSpeech tts;
    private String host = "127.0.0.1";
    private ValueCallback<Uri[]> selectedPhotos;
    private static final int PICK_IMAGES = 720;
    private static final int CAMERA_PERMISSION = 721;
    private String pendingCameraRole, pendingCameraRoom;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tts = new TextToSpeech(this, this);
        showStart();
    }
    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("pt","BR"));
    }
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private TextView title(String value,int sp) {
        TextView text=new TextView(this);text.setText(value);text.setTextSize(sp);text.setTextColor(Color.rgb(236,240,248));
        return text;
    }
    private Button button(String label) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);return b;
    }
    private void showStart() {
        web=null;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(15,23,35));
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(24),dp(54),dp(24),dp(32));
        layout.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(layout);
        TextView heading=title("AutomaForge",32);layout.addView(heading);
        TextView description=title("Seu controlador pode hospedar toda a partida na rede local.",16);
        description.setPadding(0,dp(12),0,dp(28));layout.addView(description);
        Button hostButton=button("Iniciar controlador + servidor");layout.addView(hostButton);
        hostButton.setOnClickListener(v -> startController());
        TextView second=title("Outro celular nesta mesma rede?",18);
        second.setPadding(0,dp(40),0,dp(8));layout.addView(second);
        TextView notice=title("Use o mesmo APK como câmera do tripé ou leitor privado. Também é possível acessar pelo navegador, mas sem captura automática em HTTP.",13);
        notice.setTextColor(0xffafbdce);layout.addView(notice);
        EditText address=new EditText(this);address.setSingleLine(true);address.setHint("IP do controlador (ex.: 192.168.1.50)");
        address.setTextColor(Color.WHITE);address.setHintTextColor(0xffa6b4c6);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        layout.addView(address);
        Button connect=button("Conectar a servidor existente");layout.addView(connect);
        connect.setOnClickListener(v -> {
            String value=address.getText().toString().trim();
            if(!value.matches("(\\d{1,3}\\.){3}\\d{1,3}")) { toast("Digite um IPv4 válido da rede local.");return; }
            String[] octets=value.split("\\.");for(String octet:octets)if(Integer.parseInt(octet)>255) {toast("IP inválido.");return;}
            if (!isLocal(value)) { toast("Conecte somente a um IP de rede privada."); return; }
            host=value;showWeb("http://"+host+":"+LanServer.PORT, false);
        });
        TextView footer=title("Versão Android 0.2 alpha · Hospedagem no controlador · Sem nuvem para sincronização",12);
        footer.setTextColor(0xffa3aec2);footer.setPadding(0,dp(36),0,0);layout.addView(footer);
        setContentView(scroll);
    }
    private static boolean isLocal(String ip) {
        String[] parts=ip.split("\\."); int a=Integer.parseInt(parts[0]),b=Integer.parseInt(parts[1]);
        return a==10 || a==127 || (a==172&&b>=16&&b<=31) || (a==192&&b==168);
    }
    private void startController() {
        try {
            if(server==null)server=new LanServer(this);
            server.start();host="127.0.0.1";
            showWeb("http://127.0.0.1:"+LanServer.PORT, true);
        } catch(Exception e) { toast("Não foi possível iniciar servidor: "+e.getMessage()); }
    }
    private void showWeb(String base, boolean controller) {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16,25,37));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8),dp(4),dp(8),dp(4));root.addView(toolbar);
        if(controller) {
            TextView address=title("LAN: "+localIp()+":"+LanServer.PORT,13);
            toolbar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
            Button settings=button("IA");toolbar.addView(settings);
            settings.setOnClickListener(v -> configureAi());
        } else {
            TextView address=title("Cliente: "+host,13);toolbar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        Button back=button("Sair");toolbar.addView(back);
        back.setOnClickListener(v -> {if(server!=null && server.isRunning()) server.stop();if(web!=null){web.destroy();web=null;}showStart();});
        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new AndroidBridge(base),"AutomaAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri=request.getUrl();
                if(uri==null || !"http".equals(uri.getScheme()) || !host.equals(uri.getHost()) || uri.getPort()!=LanServer.PORT) {
                    toast("A navegação externa está bloqueada.");return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(selectedPhotos!=null)selectedPhotos.onReceiveValue(null);
                selectedPhotos=callback;
                try {
                    Intent pick=new Intent(Intent.ACTION_GET_CONTENT);pick.setType("image/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
                    startActivityForResult(Intent.createChooser(pick,"Selecionar fotos do manual"),PICK_IMAGES);
                    return true;
                } catch(Exception e) {selectedPhotos=null;callback.onReceiveValue(null);return true;}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        web.loadUrl(base+"/");
        if(controller)toast("Servidor ativo. Conecte os outros celulares usando o IP exibido.");
    }
    @SuppressWarnings("deprecation") @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_IMAGES || selectedPhotos==null)return;
        Uri[] uris=null;
        if(result==RESULT_OK && data!=null) {
            if(data.getClipData()!=null) {
                int n=data.getClipData().getItemCount();uris=new Uri[n];
                for(int i=0;i<n;i++)uris[i]=data.getClipData().getItemAt(i).getUri();
            } else if(data.getData()!=null)uris=new Uri[]{data.getData()};
        }
        selectedPhotos.onReceiveValue(uris);selectedPhotos=null;
    }
    private void configureAi() {
        EditText key=new EditText(this);key.setHint("sk-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setPadding(dp(24),dp(18),dp(24),dp(12));
        new AlertDialog.Builder(this).setTitle("Chave OpenAI (opcional)")
            .setMessage("A chave permanece nas preferências privadas do controlador. Ao usar IA, textos e imagens são enviados à API OpenAI; isso não funciona offline.")
            .setView(key).setNegativeButton("Cancelar",null)
            .setNeutralButton("Apagar chave",(d,w)->{
                getSharedPreferences("private_settings",0).edit().remove("api_key").apply();toast("Chave apagada.");})
            .setPositiveButton("Salvar",(d,w)->{
                getSharedPreferences("private_settings",0).edit().putString("api_key",key.getText().toString().trim()).apply();
                toast("Configuração de IA salva no controlador.");if(web!=null)web.reload();
            }).show();
    }
    public final class AndroidBridge {
        final String base;
        AndroidBridge(String base){this.base=base;}
        @JavascriptInterface public String lanAddress() {
            return "http://" + ("127.0.0.1".equals(host) ? localIp() : host) + ":" + LanServer.PORT;
        }
        @JavascriptInterface public void speak(String text) {
            if(text==null || text.isEmpty())return;
            runOnUiThread(()->{if(tts!=null)tts.speak(GameStore.clip(text,2500),TextToSpeech.QUEUE_FLUSH,null,"automa");});
        }
        @JavascriptInterface public void stopSpeech() {runOnUiThread(()->{if(tts!=null)tts.stop();});}
        @JavascriptInterface public void launchCamera(String role,String room) {
            if(!"board".equals(role)&&!"cards".equals(role))return;
            if(room==null||!room.matches("[A-HJ-NP-Z2-9]{8}"))return;
            runOnUiThread(()->{
                pendingCameraRole=role;pendingCameraRoom=room;
                if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
                else openCamera();
            });
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CAMERA_PERMISSION) {
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)openCamera();
            else toast("Permissão de câmera necessária para fotografar.");
        }
    }
    private void openCamera(){
        Intent intent=new Intent(this,CameraActivity.class);intent.putExtra("role",pendingCameraRole);
        intent.putExtra("room",pendingCameraRoom);intent.putExtra("host",host);
        startActivity(intent);
    }
    private static String localIp() {
        try {
            java.util.List<NetworkInterface> interfaces=Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prefer Wi-Fi/hotspot interfaces over cellular/VPN interfaces.
            for(int pass=0;pass<2;pass++)for(NetworkInterface item:interfaces) {
                String n=item.getName().toLowerCase(Locale.ROOT);
                if(pass==0 && !(n.startsWith("wlan")||n.startsWith("ap")||n.startsWith("swlan")))continue;
                for(java.net.InetAddress address:Collections.list(item.getInetAddresses()))
                    if(!address.isLoopbackAddress() && address instanceof Inet4Address && address.isSiteLocalAddress())
                        return address.getHostAddress();
            }
        }catch(Exception ignored){}
        return "IP indisponível";
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() {
        if(web!=null && web.canGoBack())web.goBack();else new AlertDialog.Builder(this).setTitle("Encerrar AutomaForge?")
            .setMessage("Ao sair, o servidor da partida será interrompido.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Encerrar",(d,w)->finish()).show();
    }
    @Override protected void onDestroy() {
        if(server!=null)server.stop();
        if(web!=null){web.destroy();web=null;}
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}
