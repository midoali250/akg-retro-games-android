package com.bitvaultengine.retrocollection;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Independent, signed Android GitHub-release channel. No GitHub credential is embedded. */
final class UpdateClient {
    private static final String MANIFEST=UpdateSecurity.REPOSITORY+"latest/download/akg-update-manifest.json";
    private final Activity activity;private boolean busy;private File pending;
    UpdateClient(Activity a){activity=a;}
    private static boolean hostAllowed(URI u){String h=u.getHost();return "https".equalsIgnoreCase(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getRawFragment()==null&&h!=null&&(h.equalsIgnoreCase("github.com")||h.equalsIgnoreCase("release-assets.githubusercontent.com")||h.equalsIgnoreCase("objects.githubusercontent.com")||h.equalsIgnoreCase("github-releases.githubusercontent.com"));}
    private static HttpURLConnection open(String url)throws Exception{
        for(int redirects=0;redirects<=5;redirects++){
            URI u=new URI(url);if(!hostAllowed(u))throw new SecurityException("Untrusted download host");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(45000);c.setRequestProperty("User-Agent","AKG-Retro-Games-Android/1");c.setRequestProperty("Accept-Encoding","identity");int status=c.getResponseCode();
            if(status==301||status==302||status==303||status==307||status==308){String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new SecurityException("Invalid redirect");url=u.resolve(next).toString();continue;}
            return c;
        }throw new SecurityException("Too many redirects");
    }
    private static byte[] limited(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new SecurityException("Manifest too large");out.write(b,0,n);}return out.toByteArray();}
    private void onUi(Runnable r){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())r.run();});}
    private void message(String title,String text){new AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK",null).show();}
    void check(){check(false);}
    void check(boolean quiet){
        if(busy)return;busy=true;if(!quiet)android.widget.Toast.makeText(activity,"Checking Android updates…",android.widget.Toast.LENGTH_SHORT).show();
        new Thread(()->{try{
            JSONObject config;try(InputStream in=activity.getAssets().open("updates/public-key.json")){config=new JSONObject(new String(limited(in,4096),StandardCharsets.UTF_8));}
            if(!"android".equals(config.getString("platform"))||!"Ed25519".equals(config.getString("algorithm")))throw new SecurityException("Invalid update key");byte[] key=Base64.decode(config.getString("public_key_base64"),Base64.DEFAULT);
            HttpURLConnection c=open(MANIFEST);int status=c.getResponseCode();if(status==404){c.disconnect();onUi(()->{busy=false;if(!quiet)message("Updates","No Android update has been published yet.");});return;}if(status!=200){c.disconnect();throw new IllegalStateException("Update server returned "+status);}byte[] body;try(InputStream in=c.getInputStream()){body=limited(in,131072);}finally{c.disconnect();}
            PackageInfo installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);long version=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;UpdateSecurity.Release release=UpdateSecurity.verify(body,key,version);
            onUi(()->{busy=false;if(release==null){if(!quiet)message("Updates","This Android version is up to date.");return;}new AlertDialog.Builder(activity).setTitle("Android update available").setMessage("Version "+release.version+" is available. Download and verify it?").setPositiveButton("Download",(d,w)->download(release)).setNegativeButton("Later",null).show();});
        }catch(Exception e){onUi(()->{busy=false;if(!quiet)message("Update check failed","Could not verify the Android update: "+e.getClass().getSimpleName());});}},"akg-update-check").start();
    }
    private void download(UpdateSecurity.Release release){if(busy)return;busy=true;android.widget.Toast.makeText(activity,"Downloading verified update…",android.widget.Toast.LENGTH_LONG).show();new Thread(()->{
        File dir=new File(activity.getCacheDir(),"updates"),tmp=new File(dir,"download.part"),dest=new File(dir,"verified.apk");try{
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create update directory");HttpURLConnection c=open(release.url);if(c.getResponseCode()!=200){c.disconnect();throw new IllegalStateException("Download failed");}MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){size+=n;if(size>release.size)throw new SecurityException("Package too large");sha.update(b,0,n);out.write(b,0,n);}out.getFD().sync();}finally{c.disconnect();}
            StringBuilder digest=new StringBuilder();for(byte v:sha.digest())digest.append(String.format(java.util.Locale.ROOT,"%02x",v&255));if(size!=release.size||!release.sha.equals(digest.toString()))throw new SecurityException("Update hash mismatch");verifyApk(tmp,release.version);if(!tmp.renameTo(dest))throw new IllegalStateException("Cannot save update");pending=dest;
            onUi(()->{busy=false;new AlertDialog.Builder(activity).setTitle("Update verified").setMessage("The Android installer will ask for your confirmation.").setPositiveButton("Install",(d,w)->install()).setNegativeButton("Later",null).show();});
        }catch(Exception e){tmp.delete();onUi(()->{busy=false;message("Download failed","The update was not installed: "+e.getClass().getSimpleName());});}},"akg-update-download").start();}
    private void verifyApk(File apk,long version)throws Exception{
        PackageManager pm=activity.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo file=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags),installed=pm.getPackageInfo(activity.getPackageName(),flags);
        if(file==null||!activity.getPackageName().equals(file.packageName)||(Build.VERSION.SDK_INT>=28?file.getLongVersionCode():file.versionCode)!=version)throw new SecurityException("Update package identity mismatch");
        Signature[] a=Build.VERSION.SDK_INT>=28?file.signingInfo.getApkContentsSigners():file.signatures,b=Build.VERSION.SDK_INT>=28?installed.signingInfo.getApkContentsSigners():installed.signatures;
        if(a==null||b==null||!new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b))))throw new SecurityException("Update signing certificate differs");
    }
    private void install(){if(pending==null||!pending.isFile()){message("Updates","No verified update is available.");return;}
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(activity).setTitle("Allow installation").setMessage("Allow this app to request installation, then return to confirm the update.").setPositiveButton("Settings",(d,w)->{Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));activity.startActivityForResult(i,1947);}).setNegativeButton("Cancel",null).show();return;}
        Uri uri=Uri.parse("content://"+activity.getPackageName()+".updates/verified.apk");Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("verified update",uri));try{activity.startActivity(i);}catch(Exception e){message("Install failed","No Android package installer is available.");}}
    void onActivityResult(int request){if(request==1947&&pending!=null&&activity.getPackageManager().canRequestPackageInstalls())install();}
}
