package com.bitvaultengine.retrocollection;
/** Single-session libretro bridge; all native methods are called by one game thread. */
public final class NativeBridge {
    static { System.loadLibrary("akg_android_host"); }
    public static native boolean open(String core,String game,String directory);
    public static native void step(int buttons);
    public static native int width(); public static native int height();
    public static native double rate(); public static native double fps();
    public static native int[] video(); public static native boolean copyVideo(int[] output); public static native int inputReadMask(); public static native short[] audio();
    public static native byte[] save(); public static native boolean restore(byte[] bytes);
    public static native void resetCheats(); public static native void setCheat(int index,String code);
    public static native int ramByte(int offset); public static native boolean testSetRamByte(int offset,int value);
    public static native void close();
    private NativeBridge(){}
}
