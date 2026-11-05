package com.bitvaultengine.retrocollection;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Experimental libretro player: game thread owns native state; touches provide live input. */
public final class PlayerActivity extends Activity {
    private GameView game; private String sourceId,sha,system; private boolean smoke;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        sourceId=getIntent().getStringExtra("game_id");smoke=getIntent().getBooleanExtra("akg_ci_smoke",false);
        if(sourceId==null){finish();return;}
        try{byte[] data;try(InputStream in=getAssets().open("cheats/index.json")){data=readAll(in,5_000_000);}JSONArray games=new JSONObject(new String(data,StandardCharsets.UTF_8)).getJSONArray("games");
            for(int i=0;i<games.length();i++){JSONObject r=games.getJSONObject(i);if(sourceId.equals(r.getString("source_id"))){sha=r.getString("rom_sha256");system=r.getString("system");break;}}
            if(sha==null||!sha.matches("[a-f0-9]{64}")||!("NES".equals(system)||"SNES".equals(system)))throw new IllegalArgumentException("Unknown game");
            game=new GameView();setContentView(game);
        }catch(Exception e){Log.e("AKG_ANDROID","Catalog error",e);Toast.makeText(this,"Game catalog unavailable",Toast.LENGTH_LONG).show();finish();}
    }
    static byte[] readAll(InputStream in,int max)throws Exception{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[16384];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>max)throw new IllegalArgumentException("File too large");out.write(buf,0,n);}return out.toByteArray();}
    private File extractRom()throws Exception{String ext="NES".equals(system)?".nes":".smc";byte[] bytes;try(InputStream in=getAssets().open("roms/"+sourceId+ext)){bytes=readAll(in,10_000_000);}
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:hash)b.append(String.format(Locale.ROOT,"%02x",v&255));if(!sha.equals(b.toString()))throw new SecurityException("Game identity mismatch");
        File file=new File(getCacheDir(),sha+ext);try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}return file;}
    private void settings(){if(game==null)return;game.paused=true;
        String[] options={"Resume","Move controls","Reset control positions","Save stage / position","Load stage / position","Full screen: "+(game.stretch?"stretch":"fit"),"Exit to game list"};
        new AlertDialog.Builder(this).setTitle("Game settings").setItems(options,(d,which)->{
            if(which==1){game.edit=true;Toast.makeText(this,"Drag the joystick and buttons; tap DONE to save",Toast.LENGTH_LONG).show();}
            else if(which==2){game.resetPositions();game.savePositions();}
            else if(which==3){chooseStateSlot(true);return;}
            else if(which==4){chooseStateSlot(false);return;}
            else if(which==5){game.stretch=!game.stretch;game.prefs.edit().putBoolean("stretch",game.stretch).apply();}
            else if(which==6){finish();return;}
            game.paused=false;
        }).setOnCancelListener(d->game.paused=false).show();}
    private void chooseStateSlot(boolean save){
        String[] slots={"Slot 1", "Slot 2", "Slot 3", "Slot 4"};
        new AlertDialog.Builder(this).setTitle(save?"Save stage / position":"Load stage / position")
            .setItems(slots,(d,index)->{game.stateSlot=index+1;game.request=save?1:2;game.paused=false;})
            .setOnCancelListener(d->game.paused=false).show();
    }
    @Override protected void onPause(){super.onPause();if(game!=null)game.paused=true;}
    @Override protected void onResume(){super.onResume();if(game!=null)game.paused=false;}
    @Override public void onBackPressed(){settings();}
    final class GameView extends SurfaceView implements SurfaceHolder.Callback,Runnable {
        final SharedPreferences prefs=getSharedPreferences("controls",MODE_PRIVATE);final Paint paint=new Paint(3);final float[] px=new float[5],py=new float[5];
        final int[] ids={0,8,0,9,1};final String[] labels={"", "A","B","X","Y"};
        volatile boolean alive=false,paused=false,edit=false,stretch=prefs.getBoolean("stretch",false);volatile int buttons=0,request=0,stateSlot=1;Thread thread;int moving=-1,stickPointer=-1;
        GameView(){super(PlayerActivity.this);getHolder().addCallback(this);setFocusable(true);resetPositions();for(int i=0;i<5;i++){px[i]=prefs.getFloat("x"+i,px[i]);py[i]=prefs.getFloat("y"+i,py[i]);}}
        void resetPositions(){px[0]=.15f;py[0]=.72f;px[1]=.90f;py[1]=.70f;px[2]=.80f;py[2]=.80f;px[3]=.80f;py[3]=.60f;px[4]=.70f;py[4]=.70f;}
        void savePositions(){SharedPreferences.Editor e=prefs.edit();for(int i=0;i<5;i++){e.putFloat("x"+i,px[i]);e.putFloat("y"+i,py[i]);}e.apply();}
        @Override public void surfaceCreated(SurfaceHolder h){if(thread==null){alive=true;thread=new Thread(this,"akg-game-thread");thread.start();}}
        @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
        @Override public void surfaceDestroyed(SurfaceHolder h){alive=false;Thread t=thread;if(t!=null){try{t.join(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}if(!t.isAlive())thread=null;}}
        File stateFile(int slot){return new File(getFilesDir(),sha+(slot==1?".state":".slot"+slot+".state"));}
        @Override public void run(){AudioTrack audio=null;Bitmap bitmap=null;int frames=0;boolean sawAudio=false,sawVideo=false;
            try{File rom=extractRom();String coreName="NES".equals(system)?"libfceumm_libretro.so":"libsnes9x_libretro.so";File core=new File(getApplicationInfo().nativeLibraryDir,coreName);
                if(!NativeBridge.open(core.getAbsolutePath(),rom.getAbsolutePath(),getFilesDir().getAbsolutePath()))throw new IllegalStateException("Could not open emulator core");
                int rate=(int)NativeBridge.rate();if(rate<8000||rate>192000)rate=44100;int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                if(min>0){audio=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),Math.max(min*2,8192),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);audio.play();}
                Log.i("AKG_ANDROID","PLAYER_READY");
                double fps=NativeBridge.fps();long delay=(long)(1000/(fps>=20&&fps<=120?fps:60));
                while(alive){long started=android.os.SystemClock.uptimeMillis();if(!paused&&!edit){NativeBridge.step(smoke&&frames>20&&frames<24?1<<3:buttons);frames++;int w=NativeBridge.width(),h=NativeBridge.height();int[] video=NativeBridge.video();if(video!=null&&w>0&&h>0&&video.length==w*h){sawVideo=true;if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h){if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}bitmap.setPixels(video,0,w,0,0,w,h);}short[] pcm=NativeBridge.audio();if(pcm!=null&&pcm.length>0){sawAudio=true;if(audio!=null)audio.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);}
                    try {
                    if(request==1){request=0;byte[] saved=NativeBridge.save();if(saved==null)throw new IllegalStateException("Save unavailable");File file=stateFile(stateSlot),tmp=new File(getFilesDir(),file.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(saved);out.getFD().sync();}if(!tmp.renameTo(file))throw new IllegalStateException("Save failed");Log.i("AKG_ANDROID_STATE","Saved slot "+stateSlot);runOnUiThread(()->Toast.makeText(PlayerActivity.this,"State saved",Toast.LENGTH_SHORT).show());}
                    if(request==2){request=0;byte[] saved;try(FileInputStream in=new FileInputStream(stateFile(stateSlot))){saved=readAll(in,100_000_000);}if(!NativeBridge.restore(saved))throw new IllegalStateException("Load failed");Log.i("AKG_ANDROID_STATE","Loaded slot "+stateSlot);runOnUiThread(()->Toast.makeText(PlayerActivity.this,"State loaded",Toast.LENGTH_SHORT).show());}
                    }catch(Exception stateError){request=0;runOnUiThread(()->Toast.makeText(PlayerActivity.this,"Save/load failed: "+stateError.getMessage(),Toast.LENGTH_LONG).show());}
                    if(smoke&&frames>=600){byte[] s=NativeBridge.save();if(!sawVideo||!sawAudio||s==null||!NativeBridge.restore(s))throw new IllegalStateException("Smoke failed");Log.i("AKG_ANDROID_SMOKE","PASS "+system+" video audio state input");alive=false;runOnUiThread(()->finish());}
                }
                Canvas canvas=null;try{canvas=getHolder().lockCanvas();if(canvas!=null)drawFrame(canvas,bitmap);}finally{if(canvas!=null)getHolder().unlockCanvasAndPost(canvas);}
                long wait=delay-(android.os.SystemClock.uptimeMillis()-started);if(wait>0)android.os.SystemClock.sleep(wait);
                }
            }catch(Exception|LinkageError e){Log.e("AKG_ANDROID","Player failed",e);runOnUiThread(()->{Toast.makeText(PlayerActivity.this,"Game error: "+e.getMessage(),Toast.LENGTH_LONG).show();finish();});}
            finally{if(audio!=null){try{audio.stop();}catch(Exception ignored){}audio.release();}try{NativeBridge.close();}catch(LinkageError ignored){}if(bitmap!=null)bitmap.recycle();}
        }
        void drawFrame(Canvas c,Bitmap b){int w=c.getWidth(),h=c.getHeight();c.drawColor(Color.BLACK);if(b!=null){float dw=w,dh=h;if(!stretch){dw=Math.min(w,h*4f/3f);dh=dw*3f/4f;}paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setFilterBitmap(false);c.drawBitmap(b,null,new RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),paint);}
            float base=Math.min(w,h);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(base*.045f);
            for(int i=0;i<5;i++){float r=base*(i==0?.15f:.065f);paint.setColor(Color.argb(115,15,45,75));c.drawCircle(px[i]*w,py[i]*h,r,paint);paint.setColor(Color.argb(205,100,225,255));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(2,base*.004f));c.drawCircle(px[i]*w,py[i]*h,r,paint);paint.setStyle(Paint.Style.FILL);if(i>0)c.drawText(labels[i],px[i]*w,py[i]*h+base*.015f,paint);}
            paint.setColor(Color.WHITE);paint.setTextSize(base*.045f);paint.setTextAlign(Paint.Align.LEFT);c.drawText(edit?"DONE":"SETTINGS",w*.43f,h*.08f,paint);c.drawText("SELECT",w*.34f,h*.93f,paint);c.drawText("START",w*.52f,h*.93f,paint);}
        @Override public boolean onTouchEvent(MotionEvent e){int w=getWidth(),h=getHeight();if(w<=0||h<=0)return true;int a=e.getActionMasked(),index=e.getActionIndex();float x=e.getX(index)/w,y=e.getY(index)/h;
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){if(x>.40f&&x<.62f&&y<.14f){if(edit){edit=false;savePositions();}else settings();return true;}if(edit){float best=100;moving=-1;for(int i=0;i<5;i++){float dx=x-px[i],dy=y-py[i],d=dx*dx+dy*dy;if(d<best){best=d;moving=i;}}return true;}float dx=(x-px[0])*w,dy=(y-py[0])*h,rad=Math.min(w,h)*.17f;if(stickPointer<0&&dx*dx+dy*dy<=rad*rad)stickPointer=e.getPointerId(index);}
            if(edit){if(a==MotionEvent.ACTION_MOVE&&moving>=0){px[moving]=Math.max(.05f,Math.min(.95f,x));py[moving]=Math.max(.08f,Math.min(.92f,y));}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)moving=-1;return true;}
            int mask=0;for(int i=0;i<e.getPointerCount();i++){if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP)&&i==index)continue;float tx=e.getX(i)/w,ty=e.getY(i)/h;float dx=(tx-px[0])*w,dy=(ty-py[0])*h,rad=Math.min(w,h)*.17f;if(e.getPointerId(i)==stickPointer){if(dx<-rad*.22)mask|=1<<6;if(dx>rad*.22)mask|=1<<7;if(dy<-rad*.22)mask|=1<<4;if(dy>rad*.22)mask|=1<<5;}
                for(int k=1;k<5;k++){dx=(tx-px[k])*w;dy=(ty-py[k])*h;float r=Math.min(w,h)*.075f;if(dx*dx+dy*dy<r*r)mask|=1<<ids[k];}
                if(ty>.86f&&tx>.32f&&tx<.48f)mask|=1<<2;if(ty>.86f&&tx>.50f&&tx<.66f)mask|=1<<3;}
            if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(index)==stickPointer)stickPointer=-1;if(a==MotionEvent.ACTION_CANCEL){mask=0;stickPointer=-1;}buttons=mask;if(smoke&&mask!=0)Log.i("AKG_ANDROID_TOUCH","mask="+mask);return true;}
    }
}
