package com.automaforge.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Camera2 avoids getUserMedia restrictions on an HTTP local-network address. */
public class CameraActivity extends Activity {
    private TextureView preview;
    private TextView status;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final ExecutorService uploader=Executors.newSingleThreadExecutor();
    private String role,room,host;
    private boolean active;
    private int photoWidth=1280, photoHeight=720;
    private int uploaded=0;
    private Runnable autoCapture;

    private int dp(float n) {return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        role=getIntent().getStringExtra("role");room=getIntent().getStringExtra("room");host=getIntent().getStringExtra("host");
        if((!"board".equals(role)&&!"cards".equals(role))||room==null||!room.matches("[A-HJ-NP-Z2-9]{8}")||host==null) {finish();return;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff0f1928);
        TextView title=new TextView(this);title.setText("board".equals(role)?"Câmera do tabuleiro":"Leitor privado de cartas");
        title.setTextColor(Color.WHITE);title.setTextSize(21);title.setPadding(dp(18),dp(20),dp(18),dp(12));layout.addView(title);
        TextView subtitle=new TextView(this);
        subtitle.setText("Sala "+room+("board".equals(role)?" · Foto automática a cada 5 segundos":" · Só capture quando a carta estiver na caixa"));
        subtitle.setTextColor(0xffb7c4d8);subtitle.setPadding(dp(18),0,dp(18),dp(18));layout.addView(subtitle);
        preview=new TextureView(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setText("Preparando câmera…");status.setTextColor(Color.WHITE);
        status.setPadding(dp(18),dp(20),dp(18),dp(16));layout.addView(status);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER);layout.addView(buttons);
        Button shutter=new Button(this);shutter.setAllCaps(false);shutter.setText("board".equals(role)?"Enviar foto agora":"Ler carta privada");
        buttons.addView(shutter,new LinearLayout.LayoutParams(0,dp(60),1));shutter.setOnClickListener(v->capture());
        Button close=new Button(this);close.setText("Voltar");close.setAllCaps(false);buttons.addView(close);
        close.setOnClickListener(v->finish());
        setContentView(layout);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){open();}
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){closeCamera();return true;}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
        });
    }
    private void update(String text){runOnUiThread(()->{if(status!=null)status.setText(text);});}
    @Override protected void onResume(){super.onResume();cameraThread=new HandlerThread("automa-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());active=true;if(preview!=null&&preview.isAvailable())open();}
    @Override protected void onPause(){active=false;closeCamera();super.onPause();}
    private void open(){
        if(!active||camera!=null||cameraHandler==null)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED){update("Câmera sem permissão.");return;}
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            String id=null;for(String candidate:manager.getCameraIdList()){
                Integer facing=manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
                if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){id=candidate;break;}
            }
            if(id==null)throw new IllegalStateException("Nenhuma câmera traseira encontrada.");
            final String cameraId=id;
            StreamConfigurationMap configurations=manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(configurations==null || configurations.getOutputSizes(ImageFormat.JPEG)==null)
                throw new IllegalStateException("A câmera não suporta fotos JPEG.");
            android.util.Size chosen=null;
            boolean withinBudget=false;
            for(android.util.Size option:configurations.getOutputSizes(ImageFormat.JPEG)) {
                long area=(long)option.getWidth()*option.getHeight();
                boolean candidateWithinBudget=area<=2400000;
                if(chosen==null || (candidateWithinBudget&&!withinBudget) ||
                   (candidateWithinBudget==withinBudget &&
                      (withinBudget ? area>(long)chosen.getWidth()*chosen.getHeight() :
                       area<(long)chosen.getWidth()*chosen.getHeight()))) {
                    chosen=option;withinBudget=candidateWithinBudget;
                }
            }
            photoWidth=chosen.getWidth();photoHeight=chosen.getHeight();
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice device){if(!active){device.close();return;}camera=device;configure();}
                @Override public void onDisconnected(CameraDevice device){device.close();camera=null;update("Câmera desconectada.");}
                @Override public void onError(CameraDevice device,int error){device.close();camera=null;update("Falha na câmera: "+error);}
            },cameraHandler);
        }catch(Exception e){update("Não foi possível abrir a câmera: "+e.getMessage());}
    }
    private void configure(){
        try{
            if(camera==null||!preview.isAvailable())return;
            SurfaceTexture texture=preview.getSurfaceTexture();texture.setDefaultBufferSize(1280,720);
            Surface surface=new Surface(texture);
            reader=ImageReader.newInstance(photoWidth,photoHeight,ImageFormat.JPEG,3);
            reader.setOnImageAvailableListener(r->{
                try(Image image=r.acquireLatestImage()){
                    if(image==null)return;
                    ByteBuffer buffer=image.getPlanes()[0].getBuffer();byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
                    uploader.execute(()->upload(bytes));
                }catch(Exception e){update("Falha ao capturar: "+e.getMessage());}
            },cameraHandler);
            camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession created){
                    if(camera==null){created.close();return;}session=created;
                    try{
                        CaptureRequest.Builder ongoing=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        ongoing.addTarget(surface);ongoing.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        created.setRepeatingRequest(ongoing.build(),null,cameraHandler);
                        update("Câmera ativa. "+("board".equals(role)?"Capturas a cada 5 segundos.":"Aguardando carta na caixa de leitura."));
                        if("board".equals(role)){
                            autoCapture=new Runnable(){@Override public void run(){if(active&&session!=null){capture();cameraHandler.postDelayed(this,5000);}}};
                            cameraHandler.postDelayed(autoCapture,1200);
                        }
                    }catch(Exception e){update("Erro ao iniciar prévia: "+e.getMessage());}
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed){update("Configuração da câmera falhou.");}
            },cameraHandler);
        }catch(Exception e){update("Falha na câmera: "+e.getMessage());}
    }
    private void capture(){
        if(cameraHandler==null)return;
        cameraHandler.post(()->{
            if(camera==null||session==null||reader==null){update("A câmera ainda está iniciando.");return;}
            try{
                CaptureRequest.Builder shot=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                shot.addTarget(reader.getSurface());shot.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                session.capture(shot.build(),null,cameraHandler);
            }catch(Exception e){update("Captura falhou: "+e.getMessage());}
        });
    }
    private void upload(byte[] jpeg){
        HttpURLConnection conn=null;
        try{
            String suffix="board".equals(role)?"/board/photo":"/cards/private";
            URL url=new URL("http://"+host+":"+LanServer.PORT+"/api/rooms/"+room+suffix);
            conn=(HttpURLConnection)url.openConnection();conn.setConnectTimeout(6000);conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","image/jpeg");
            conn.setFixedLengthStreamingMode(jpeg.length);
            try(OutputStream out=conn.getOutputStream()){out.write(jpeg);}
            int statusCode=conn.getResponseCode();
            InputStream response=statusCode<400?conn.getInputStream():conn.getErrorStream();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            if(response!=null){try(InputStream in=response){byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)output.write(buf,0,n);}}
            JSONObject result=new JSONObject(output.toString("UTF-8"));
            if(statusCode>=400)throw new Exception(result.optString("detail","Erro HTTP "+statusCode));
            uploaded++;
            update("board".equals(role)?"Foto "+result.optInt("board_version")+" enviada ao controlador.":
                    "Carta lida privadamente. Total: "+result.optInt("private_card_count")+". A face não é exibida ao controlador.");
        }catch(Exception e){update("Erro de envio: "+e.getMessage());}
        finally{if(conn!=null)conn.disconnect();}
    }
    private void closeCamera(){
        if(cameraHandler!=null&&autoCapture!=null)cameraHandler.removeCallbacks(autoCapture);
        autoCapture=null;
        if(session!=null){session.close();session=null;}
        if(camera!=null){camera.close();camera=null;}
        if(reader!=null){reader.close();reader=null;}
        if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}
    }
    @Override protected void onDestroy(){uploader.shutdown();super.onDestroy();}
}
