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
    private GameView game; private String sourceId,sha,system; private boolean smoke,layoutOnly;
    private final java.util.ArrayList<String> cheatNames=new java.util.ArrayList<>(),cheatCodes=new java.util.ArrayList<>();
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        sourceId=getIntent().getStringExtra("game_id");smoke=getIntent().getBooleanExtra("akg_ci_smoke",false);layoutOnly=getIntent().getBooleanExtra("akg_edit_controls",false);
        if(sourceId==null){finish();return;}
        try{byte[] data;try(InputStream in=getAssets().open("cheats/index.json")){data=readAll(in,5_000_000);}JSONArray games=new JSONObject(new String(data,StandardCharsets.UTF_8)).getJSONArray("games");
            for(int i=0;i<games.length();i++){JSONObject r=games.getJSONObject(i);if(sourceId.equals(r.getString("source_id"))){sha=r.getString("rom_sha256");system=r.getString("system");JSONArray cheats=r.optJSONArray("verified_cheats");if(cheats!=null){for(int j=0;j<cheats.length();j++){JSONObject c=cheats.getJSONObject(j);String code=c.optString("code","");if(c.optBoolean("verified",false)&&sha.equals(c.optString("rom_sha256"))&&code.matches("[A-Za-z0-9+:-]{1,128}")){cheatNames.add(c.optString("name","Cheat"));cheatCodes.add(code);}}}break;}}
            if(sha==null||!sha.matches("[a-f0-9]{64}")||!("NES".equals(system)||"SNES".equals(system)))throw new IllegalArgumentException("Unknown game");
            game=new GameView();game.edit=layoutOnly;setContentView(game);if(layoutOnly)Toast.makeText(this,"Drag the joystick and all four buttons, then tap DONE",Toast.LENGTH_LONG).show();
        }catch(Exception e){Log.e("AKG_ANDROID","Catalog error",e);Toast.makeText(this,"Game catalog unavailable",Toast.LENGTH_LONG).show();finish();}
    }
    static byte[] readAll(InputStream in,int max)throws Exception{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[16384];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>max)throw new IllegalArgumentException("File too large");out.write(buf,0,n);}return out.toByteArray();}
    private static String sha256(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:hash)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private File extractRom()throws Exception{String ext="NES".equals(system)?".nes":".smc";File file=new File(getCacheDir(),sha+ext);
        if(file.isFile()){try(FileInputStream in=new FileInputStream(file)){if(sha.equals(sha256(readAll(in,10_000_000))))return file;}catch(Exception ignored){/* Re-extract a damaged cache entry. */}}
        byte[] bytes;try(InputStream in=getAssets().open("roms/"+sourceId+ext)){bytes=readAll(in,10_000_000);}if(!sha.equals(sha256(bytes)))throw new SecurityException("Game identity mismatch");
        File tmp=new File(getCacheDir(),sha+ext+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);}if(!tmp.renameTo(file))throw new IllegalStateException("Unable to prepare game");return file;}
    private void settings(){if(game==null)return;game.paused=true;
        String[] options={"Resume","Move controls","Reset control positions","Save stage / position","Load stage / position","Full screen: "+(game.stretch?"stretch":"fit"),"Exit to game list","Cheats","Button mapping","Button size"};
        new AlertDialog.Builder(this).setTitle("Game settings").setItems(options,(d,which)->{
            if(which==1){game.edit=true;Toast.makeText(this,"Drag the joystick and buttons; tap DONE to save",Toast.LENGTH_LONG).show();}
            else if(which==2){game.resetPositions();game.savePositions();}
            else if(which==3){chooseStateSlot(true);return;}
            else if(which==4){chooseStateSlot(false);return;}
            else if(which==5){game.stretch=!game.stretch;game.prefs.edit().putBoolean("stretch",game.stretch).apply();}
            else if(which==6){finish();return;}
            else if(which==7){chooseCheats();return;}
            else if(which==8){showMapping(this,system,()->{game.reloadControls();game.paused=false;});return;}
            else if(which==9){showSize(this,()->{game.reloadControls();game.paused=false;});return;}
            game.paused=false;
        }).setOnCancelListener(d->game.paused=false).show();}

    // Four on-screen buttons can be assigned independently. NES has only two native
    // face buttons; the defaults make the extra buttons repeat A and B instead of
    // sending SNES-only X/Y inputs that those games cannot receive.
    static final String[] MAP_NAMES={"A","B","X","Y","Turbo A","Turbo B","L","R"};
    static final int[] MAP_CODES={8,0,9,1,0x108,0x100,10,11};
    static int defaultMapping(String system,int button){return "NES".equals(system)?new int[]{0,1,4,5}[button]:new int[]{0,1,2,3}[button];}
    static String key(String system,int button){return "map."+system+"."+button;}
    static int mapping(SharedPreferences p,String system,int button){int value=p.getInt(key(system,button),defaultMapping(system,button));return value>=0&&value<MAP_CODES.length?value:defaultMapping(system,button);}
    static void showMapping(Activity activity,String system,Runnable done){
        SharedPreferences p=activity.getSharedPreferences("controls",MODE_PRIVATE);String[] physical={"A", "B", "X", "Y"};String[] items=new String[4];
        for(int i=0;i<4;i++)items[i]=physical[i]+" button: "+MAP_NAMES[mapping(p,system,i)];
        new AlertDialog.Builder(activity).setTitle("Button mapping").setItems(items,(d,index)->{
            String[] choices="NES".equals(system)?new String[]{"A","B","Turbo A","Turbo B"}:MAP_NAMES;
            int[] indexes="NES".equals(system)?new int[]{0,1,4,5}:new int[]{0,1,2,3,4,5,6,7};
            new AlertDialog.Builder(activity).setTitle("Assign "+physical[index]).setItems(choices,(dialog,choice)->{p.edit().putInt(key(system,index),indexes[choice]).apply();showMapping(activity,system,done);})
                .setNegativeButton("Back",(dialog,w)->showMapping(activity,system,done)).setOnCancelListener(dialog->showMapping(activity,system,done)).show();
        }).setPositiveButton("Done",(d,w)->done.run()).setOnCancelListener(d->done.run()).show();
    }
    static void showSize(Activity activity,Runnable done){
        SharedPreferences p=activity.getSharedPreferences("controls",MODE_PRIVATE);String[] sizes={"Small","Normal","Large","Extra large"};float[] values={.052f,.065f,.082f,.10f};
        new AlertDialog.Builder(activity).setTitle("Four button size").setItems(sizes,(d,index)->{p.edit().putFloat("button_size",values[index]).apply();done.run();})
          .setNegativeButton("Cancel",(d,w)->done.run()).setOnCancelListener(d->done.run()).show();
    }
    public static void libraryControls(Activity activity,String firstGame){
        String[] items={"Move joystick and four buttons","Button mapping: classic games","Button mapping: 16-bit games","Button size","Reset control positions","Reset entire app"};
        new AlertDialog.Builder(activity).setTitle("Controls settings").setItems(items,(d,which)->{
          if(which==0){if(firstGame==null){Toast.makeText(activity,"No games available",Toast.LENGTH_SHORT).show();return;}android.content.Intent intent=new android.content.Intent(activity,PlayerActivity.class);intent.putExtra("game_id",firstGame);intent.putExtra("akg_edit_controls",true);activity.startActivity(intent);}
          else if(which==1||which==2)showMapping(activity,which==1?"NES":"SNES",()->libraryControls(activity,firstGame));
          else if(which==3)showSize(activity,()->libraryControls(activity,firstGame));
          else if(which==4){SharedPreferences.Editor e=activity.getSharedPreferences("controls",MODE_PRIVATE).edit();for(int i=0;i<5;i++){e.remove("x"+i);e.remove("y"+i);}e.apply();Toast.makeText(activity,"Control positions reset",Toast.LENGTH_SHORT).show();}
          else if(which==5){new AlertDialog.Builder(activity).setTitle("Reset entire app?").setMessage("This permanently deletes ALL local game saves, control mappings, settings and cached files. Bundled games remain. This cannot be undone.").setNegativeButton("Cancel",(dialog,w)->libraryControls(activity,firstGame)).setPositiveButton("Delete all app data",(dialog,w)->{android.app.ActivityManager manager=(android.app.ActivityManager)activity.getSystemService(ACTIVITY_SERVICE);if(manager==null||!manager.clearApplicationUserData())Toast.makeText(activity,"Unable to reset app data",Toast.LENGTH_LONG).show();}).show();}
        }).setNegativeButton("Close",null).show();
    }
    private void chooseCheats(){
        if(cheatCodes.isEmpty()){new AlertDialog.Builder(this).setTitle("Cheats").setMessage("No verified cheats are available for this game yet.").setPositiveButton("Close",(d,w)->game.paused=false).setOnCancelListener(d->game.paused=false).show();return;}
        boolean[] selected=game.enabledCheats.clone();String[] names=cheatNames.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Cheats").setMultiChoiceItems(names,selected,(d,i,on)->selected[i]=on)
            .setPositiveButton("Apply",(d,w)->{game.enabledCheats=selected.clone();SharedPreferences.Editor editor=game.prefs.edit();for(int i=0;i<selected.length;i++)editor.putBoolean("cheat."+sha+"."+i,selected[i]);editor.apply();game.cheatsDirty=true;game.paused=false;})
            .setNegativeButton("Cancel",(d,w)->game.paused=false).setOnCancelListener(d->game.paused=false).show();
    }
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
        final int[] mapped=new int[4];final String[] labels={"", "A","B","X","Y"};volatile float buttonSize;volatile int heldFaces;
        volatile boolean alive=false,paused=false,edit=false,stretch=prefs.getBoolean("stretch",false);volatile int buttons=0,request=0,stateSlot=1;volatile boolean cheatsDirty=true;volatile boolean[] enabledCheats;Thread thread;int moving=-1;volatile int stickPointer=-1;volatile float stickOriginX,stickOriginY,stickCurrentX,stickCurrentY;
        GameView(){super(PlayerActivity.this);getHolder().addCallback(this);setFocusable(true);enabledCheats=new boolean[cheatCodes.size()];for(int i=0;i<enabledCheats.length;i++)enabledCheats[i]=prefs.getBoolean("cheat."+sha+"."+i,false);resetPositions();for(int i=0;i<5;i++){px[i]=prefs.getFloat("x"+i,px[i]);py[i]=prefs.getFloat("y"+i,py[i]);}reloadControls();}
        void reloadControls(){for(int i=0;i<4;i++)mapped[i]=MAP_CODES[mapping(prefs,system,i)];buttonSize=Math.max(.04f,Math.min(.11f,prefs.getFloat("button_size",.065f)));}
        int faceMask(int frame){int mask=0,held=heldFaces;for(int i=0;i<4;i++)if((held&(1<<i))!=0){int code=mapped[i];if((code&0x100)==0||frame%6<3)mask|=1<<(code&255);}return mask;}
        void resetPositions(){px[0]=.15f;py[0]=.72f;px[1]=.90f;py[1]=.70f;px[2]=.80f;py[2]=.80f;px[3]=.80f;py[3]=.60f;px[4]=.70f;py[4]=.70f;}
        void savePositions(){SharedPreferences.Editor e=prefs.edit();for(int i=0;i<5;i++){e.putFloat("x"+i,px[i]);e.putFloat("y"+i,py[i]);}e.apply();}
        @Override public void surfaceCreated(SurfaceHolder h){if(thread==null){alive=true;thread=new Thread(this,"akg-game-thread");thread.start();}}
        @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
        @Override public void surfaceDestroyed(SurfaceHolder h){alive=false;Thread t=thread;if(t!=null){try{t.join(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}if(!t.isAlive())thread=null;}}
        File stateFile(int slot){return new File(getFilesDir(),sha+(slot==1?".state":".slot"+slot+".state"));}
        @Override public void run(){AudioTrack audio=null;Bitmap bitmap=null;int frames=0,cheatProbe=0;boolean sawAudio=false,sawVideo=false;int[] videoBuffer=null;boolean hardwareCanvas=true;
            try{File rom=extractRom();String coreName="NES".equals(system)?"libfceumm_libretro.so":"libsnes9x_libretro.so";File core=new File(getApplicationInfo().nativeLibraryDir,coreName);
                if(!NativeBridge.open(core.getAbsolutePath(),rom.getAbsolutePath(),getFilesDir().getAbsolutePath()))throw new IllegalStateException("Could not open emulator core");
                int rate=(int)NativeBridge.rate();if(rate<8000||rate>192000)rate=44100;int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                if(min>0){audio=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),Math.max(min*2,8192),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);audio.play();}
                Log.i("AKG_ANDROID","PLAYER_READY");
                double fps=NativeBridge.fps();long period=(long)(1_000_000_000d/(fps>=20&&fps<=120?fps:60));long deadline=System.nanoTime();long activeStart=deadline;boolean audioPaused=false;
                while(alive){if(!paused&&!edit){
                    if(audioPaused&&audio!=null){audio.play();audioPaused=false;deadline=System.nanoTime();}
                    if(cheatsDirty){cheatsDirty=false;NativeBridge.resetCheats();boolean[] enabled=enabledCheats;for(int i=0;i<enabled.length;i++)if(enabled[i])NativeBridge.setCheat(i,cheatCodes.get(i));
                        if(smoke&&"SNES".equals(system)&&cheatCodes.size()==1&&"7E0DBE63".equals(cheatCodes.get(0))&&NativeBridge.testSetRamByte(0x0dbe,1))cheatProbe=enabled[0]?1:2;}
                    int input=buttons|faceMask(frames);NativeBridge.step(smoke&&frames>20&&frames<24?1<<3:input);frames++;
                    if(smoke&&input!=0){int seen=NativeBridge.inputReadMask();if(seen!=0)Log.i("AKG_ANDROID_INPUT","core="+seen);}
                    if(cheatProbe!=0){int value=NativeBridge.ramByte(0x0dbe);if((cheatProbe==1&&value==0x63)||(cheatProbe==2&&value!=0x63)){Log.i("AKG_ANDROID_CHEAT",cheatProbe==1?"SMW cheat enforced":"SMW cheat disabled");cheatProbe=0;}}int w=NativeBridge.width(),h=NativeBridge.height();if(w>0&&h>0&&w<=4096&&h<=4096){if(videoBuffer==null||videoBuffer.length!=w*h)videoBuffer=new int[w*h];if(NativeBridge.copyVideo(videoBuffer)){sawVideo=true;if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h){if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}bitmap.setPixels(videoBuffer,0,w,0,0,w,h);}}short[] pcm=NativeBridge.audio();if(pcm!=null&&pcm.length>0){sawAudio=true;if(audio!=null){int offset=0;while(offset<pcm.length&&alive&&!paused&&!edit){int wrote=audio.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_BLOCKING);if(wrote<=0){Log.w("AKG_ANDROID_AUDIO","Audio write failed: "+wrote);break;}offset+=wrote;}}}
                    try {
                    if(request==1){request=0;byte[] saved=NativeBridge.save();if(saved==null)throw new IllegalStateException("Save unavailable");File file=stateFile(stateSlot),tmp=new File(getFilesDir(),file.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(saved);out.getFD().sync();}if(!tmp.renameTo(file))throw new IllegalStateException("Save failed");Log.i("AKG_ANDROID_STATE","Saved slot "+stateSlot);runOnUiThread(()->Toast.makeText(PlayerActivity.this,"State saved",Toast.LENGTH_SHORT).show());}
                    if(request==2){request=0;byte[] saved;try(FileInputStream in=new FileInputStream(stateFile(stateSlot))){saved=readAll(in,100_000_000);}if(!NativeBridge.restore(saved))throw new IllegalStateException("Load failed");Log.i("AKG_ANDROID_STATE","Loaded slot "+stateSlot);runOnUiThread(()->Toast.makeText(PlayerActivity.this,"State loaded",Toast.LENGTH_SHORT).show());}
                    }catch(Exception stateError){request=0;runOnUiThread(()->Toast.makeText(PlayerActivity.this,"Save/load failed: "+stateError.getMessage(),Toast.LENGTH_LONG).show());}
                    if(smoke&&frames>=1200){byte[] s=NativeBridge.save();if(!sawVideo||!sawAudio||s==null||!NativeBridge.restore(s))throw new IllegalStateException("Smoke failed");Log.i("AKG_ANDROID_SMOKE","PASS "+system+" video audio state input; elapsed_ms="+((System.nanoTime()-activeStart)/1_000_000));alive=false;runOnUiThread(()->finish());}
                }else if(!audioPaused&&audio!=null){audio.pause();audio.flush();audioPaused=true;}
                Canvas canvas=null;try{if(hardwareCanvas){try{canvas=getHolder().lockHardwareCanvas();}catch(IllegalStateException|UnsupportedOperationException e){hardwareCanvas=false;Log.w("AKG_ANDROID","Hardware canvas unavailable, using software",e);}}if(!hardwareCanvas)canvas=getHolder().lockCanvas();if(canvas!=null)drawFrame(canvas,bitmap);}finally{if(canvas!=null)getHolder().unlockCanvasAndPost(canvas);}
                long now=System.nanoTime();if(paused||edit){deadline=now+33_000_000L;}else{deadline+=period;if(deadline<now-period*3)deadline=now;}
                long wait=deadline-System.nanoTime();if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(wait);
                }
            }catch(Exception|LinkageError e){Log.e("AKG_ANDROID","Player failed",e);runOnUiThread(()->{Toast.makeText(PlayerActivity.this,"Game error: "+e.getMessage(),Toast.LENGTH_LONG).show();finish();});}
            finally{if(audio!=null){try{audio.stop();}catch(Exception ignored){}audio.release();}try{NativeBridge.close();}catch(LinkageError ignored){}if(bitmap!=null)bitmap.recycle();}
        }
        void drawFrame(Canvas c,Bitmap b){int w=c.getWidth(),h=c.getHeight();c.drawColor(Color.BLACK);if(b!=null){float dw=w,dh=h;if(!stretch){dw=Math.min(w,h*4f/3f);dh=dw*3f/4f;}paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setFilterBitmap(false);c.drawBitmap(b,null,new RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),paint);}
            float base=Math.min(w,h);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(base*.045f);
            // Free-floating stick: only a small handle and shaft; never draw an outer ring.
            float ox=(stickPointer>=0?stickOriginX:px[0])*w,oy=(stickPointer>=0?stickOriginY:py[0])*h;
            float hx=(stickPointer>=0?stickCurrentX:px[0])*w,hy=(stickPointer>=0?stickCurrentY:py[0])*h;
            paint.setColor(Color.argb(190,100,225,255));paint.setStrokeWidth(Math.max(3,base*.012f));c.drawLine(ox,oy+base*.05f,hx,hy,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(200,42,155,195));c.drawCircle(hx,hy,base*.045f,paint);
            for(int i=1;i<5;i++){float cx=px[i],cy=py[i],r=base*buttonSize;paint.setColor(Color.argb(115,15,45,75));c.drawCircle(cx*w,cy*h,r,paint);paint.setColor(Color.argb(205,100,225,255));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(2,base*.004f));c.drawCircle(cx*w,cy*h,r,paint);paint.setStyle(Paint.Style.FILL);String text=labels[i];if("NES".equals(system)&&i>2&&(mapped[i-1]&0x100)!=0)text=(mapped[i-1]&255)==8?"A+":"B+";c.drawText(text,px[i]*w,py[i]*h+base*.015f,paint);}
            paint.setColor(Color.WHITE);paint.setTextSize(base*.045f);paint.setTextAlign(Paint.Align.LEFT);c.drawText(edit?"DONE":"SETTINGS",w*.43f,h*.08f,paint);c.drawText("SELECT",w*.34f,h*.93f,paint);c.drawText("START",w*.52f,h*.93f,paint);}
        @Override public boolean onTouchEvent(MotionEvent e){int w=getWidth(),h=getHeight();if(w<=0||h<=0)return true;int a=e.getActionMasked(),index=e.getActionIndex();float x=e.getX(index)/w,y=e.getY(index)/h;
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){if(x>.40f&&x<.62f&&y<.14f){if(edit){edit=false;savePositions();if(layoutOnly)finish();}else settings();return true;}if(edit){float best=100;moving=-1;for(int i=0;i<5;i++){float dx=x-px[i],dy=y-py[i],d=dx*dx+dy*dy;if(d<best){best=d;moving=i;}}return true;}if(stickPointer<0&&x<.45f&&y>.14f&&y<.86f){stickPointer=e.getPointerId(index);stickOriginX=x;stickOriginY=y;stickCurrentX=x;stickCurrentY=y;}}
            if(edit){if(a==MotionEvent.ACTION_MOVE&&moving>=0){px[moving]=Math.max(.05f,Math.min(.95f,x));py[moving]=Math.max(.08f,Math.min(.92f,y));}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)moving=-1;return true;}
            int mask=0,faces=0;for(int i=0;i<e.getPointerCount();i++){if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP)&&i==index)continue;float tx=e.getX(i)/w,ty=e.getY(i)/h;float dx=(tx-stickOriginX)*w,dy=(ty-stickOriginY)*h,rad=Math.min(w,h)*.17f;if(e.getPointerId(i)==stickPointer){stickCurrentX=tx;stickCurrentY=ty;if(dx<-rad*.22)mask|=1<<6;if(dx>rad*.22)mask|=1<<7;if(dy<-rad*.22)mask|=1<<4;if(dy>rad*.22)mask|=1<<5;}
                for(int k=1;k<5;k++){dx=(tx-px[k])*w;dy=(ty-py[k])*h;float r=Math.min(w,h)*Math.max(.075f,buttonSize);if(dx*dx+dy*dy<r*r)faces|=1<<(k-1);}
                if(ty>.86f&&tx>.32f&&tx<.48f)mask|=1<<2;if(ty>.86f&&tx>.50f&&tx<.66f)mask|=1<<3;}
            if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_POINTER_UP)&&e.getPointerId(index)==stickPointer)stickPointer=-1;if(a==MotionEvent.ACTION_CANCEL){mask=0;faces=0;stickPointer=-1;}heldFaces=faces;buttons=mask;if(smoke&&(mask!=0||faces!=0))Log.i("AKG_ANDROID_TOUCH","mask="+(mask|faceMask(0))+" faces="+faces);return true;}
    }
}
