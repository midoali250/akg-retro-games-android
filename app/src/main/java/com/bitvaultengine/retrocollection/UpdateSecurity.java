package com.bitvaultengine.retrocollection;

import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

final class UpdateSecurity {
    static final String REPOSITORY="https://github.com/midoali250/akg-retro-games-android/releases/";
    static final long MAX_SIZE=2_000_000_000L;
    static final class Release {final long version,size;final String sha,url;Release(long v,long n,String s,String u){version=v;size=n;sha=s;url=u;}}
    static void keys(JSONObject value,String... names)throws Exception{Set<String> expected=new HashSet<>();for(String n:names)expected.add(n);Set<String> actual=new HashSet<>();Iterator<String> i=value.keys();while(i.hasNext())actual.add(i.next());if(!actual.equals(expected))throw new SecurityException("Unexpected manifest fields");}
    static long integer(JSONObject value,String key)throws Exception{Object x=value.get(key);if(!(x instanceof Integer)&&!(x instanceof Long))throw new SecurityException("Invalid integer");return ((Number)x).longValue();}
    static byte[] decode(String s,int limit)throws Exception{if(s==null||s.length()>2*limit+16)throw new SecurityException("Invalid encoding");byte[] b=Base64.decode(s,Base64.DEFAULT);if(b.length>limit)throw new SecurityException("Oversized field");return b;}
    static Release verify(byte[] envelope,byte[] publicKey,long current)throws Exception{
        if(envelope==null||envelope.length>131072||publicKey==null||publicKey.length!=32||current<0)throw new SecurityException("Invalid manifest");
        JSONObject wrapper=new JSONObject(new String(envelope,StandardCharsets.UTF_8));keys(wrapper,"payload","signature");byte[] payload=decode(wrapper.getString("payload"),131072),sig=decode(wrapper.getString("signature"),64);if(sig.length!=64)throw new SecurityException("Invalid signature");
        EdDSANamedCurveSpec curve=EdDSANamedCurveTable.getByName("Ed25519");EdDSAPublicKey key=new EdDSAPublicKey(new EdDSAPublicKeySpec(publicKey,curve));EdDSAEngine verifier=new EdDSAEngine(MessageDigest.getInstance(curve.getHashAlgorithm()));verifier.initVerify(key);verifier.update(payload);if(!verifier.verify(sig))throw new SecurityException("Signature verification failed");
        JSONObject p=new JSONObject(new String(payload,StandardCharsets.UTF_8));keys(p,"schema_version","product","platform","version_code","size_bytes","sha256","url");
        long schema=integer(p,"schema_version"),version=integer(p,"version_code"),size=integer(p,"size_bytes");String sha=p.getString("sha256"),url=p.getString("url");
        if(schema!=1||!"akg-retro-games".equals(p.getString("product"))||!"android".equals(p.getString("platform")))throw new SecurityException("Wrong product or platform");
        if(version<=current) return null;
        if(size<=0||size>MAX_SIZE||!sha.matches("[0-9a-f]{64}"))throw new SecurityException("Invalid package metadata");
        URI uri=new URI(url);String path=uri.getRawPath();if(!"https".equals(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getRawQuery()!=null||uri.getRawFragment()!=null||path==null||!path.startsWith("/midoali250/akg-retro-games-android/releases/download/")||!path.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))throw new SecurityException("Invalid Android release URL");
        return new Release(version,size,sha,url);
    }
    static void selfTest()throws Exception{
        byte[] seed=hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"),pub=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        String json="{\"schema_version\":1,\"product\":\"akg-retro-games\",\"platform\":\"android\",\"version_code\":3,\"size_bytes\":10,\"sha256\":\""+"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"+"\",\"url\":\"https://github.com/midoali250/akg-retro-games-android/releases/download/v3/game.apk\"}";
        byte[] payload=json.getBytes(StandardCharsets.UTF_8);EdDSANamedCurveSpec curve=EdDSANamedCurveTable.getByName("Ed25519");EdDSAPrivateKey k=new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed,curve));EdDSAEngine signer=new EdDSAEngine(MessageDigest.getInstance(curve.getHashAlgorithm()));signer.initSign(k);signer.update(payload);
        JSONObject envelope=new JSONObject();envelope.put("payload",Base64.encodeToString(payload,Base64.NO_WRAP));byte[] signature=signer.sign();envelope.put("signature",Base64.encodeToString(signature,Base64.NO_WRAP));byte[] data=envelope.toString().getBytes(StandardCharsets.UTF_8);
        if(verify(data,pub,2)==null||verify(data,pub,3)!=null)throw new AssertionError("Version verification failed");signature[0]^=1;envelope.put("signature",Base64.encodeToString(signature,Base64.NO_WRAP));boolean rejected=false;try{verify(envelope.toString().getBytes(StandardCharsets.UTF_8),pub,2);}catch(SecurityException e){rejected=true;}if(!rejected)throw new AssertionError("Bad signature accepted");
    }
    private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}
