/* Generic experimental Android libretro bridge. No game code or ROMs. */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "libretro.h"

static void *core_handle; static char *game_path,*system_path; static void *rom_bytes; static size_t rom_size;
static bool loaded,initialized; static unsigned pixel_format=RETRO_PIXEL_FORMAT_0RGB1555;
static uint32_t *pixels;static unsigned width,height; static int16_t *samples;static size_t sample_count;static uint32_t input_mask;static double fps=60,sample_rate=44100;
#define AUDIO_MAX 262144
static void (*p_deinit)(void),(*p_unload)(void),(*p_run)(void),(*p_reset_cheats)(void);
static size_t (*p_state_size)(void);static bool (*p_serialize)(void*,size_t),(*p_unserialize)(const void*,size_t);
static bool env_cb(unsigned cmd,void *data){
 switch(cmd){
 case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:if(data){*(const char**)data=system_path;return true;}return false;
 case RETRO_ENVIRONMENT_GET_CAN_DUPE:if(data){*(bool*)data=true;return true;}return false;
 case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:if(!data)return false;pixel_format=*(unsigned*)data;return pixel_format<=RETRO_PIXEL_FORMAT_RGB565;
 case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:if(data){*(bool*)data=false;return true;}return false;
 case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:case RETRO_ENVIRONMENT_SET_MESSAGE:case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:return true;
 default:return false;
 }
}
static void video_cb(const void *data,unsigned w,unsigned h,size_t pitch){
 if(!data||data==(const void*)-1||!w||!h||w>4096||h>4096)return;
 size_t bpp=pixel_format==RETRO_PIXEL_FORMAT_XRGB8888?4:2;if(pitch<w*bpp||pitch>65536)return;
 size_t n=(size_t)w*h;uint32_t *out=realloc(pixels,n*sizeof(uint32_t));if(!out)return;pixels=out;width=w;height=h;
 for(unsigned y=0;y<h;y++)for(unsigned x=0;x<w;x++){
  uint32_t r,g,b;if(bpp==4){uint32_t v;memcpy(&v,(const uint8_t*)data+y*pitch+x*4,4);r=(v>>16)&255;g=(v>>8)&255;b=v&255;}
  else {uint16_t v;memcpy(&v,(const uint8_t*)data+y*pitch+x*2,2);if(pixel_format==RETRO_PIXEL_FORMAT_RGB565){r=((v>>11)&31)*255/31;g=((v>>5)&63)*255/63;b=(v&31)*255/31;}else{r=((v>>10)&31)*255/31;g=((v>>5)&31)*255/31;b=(v&31)*255/31;}}
  pixels[(size_t)y*w+x]=0xff000000u|(r<<16)|(g<<8)|b;
 }
}
static void audio_sample(int16_t l,int16_t r){if(samples&&sample_count+2<=AUDIO_MAX){samples[sample_count++]=l;samples[sample_count++]=r;}}
static size_t audio_batch(const int16_t *data,size_t frames){size_t n=frames*2;if(!data||frames>AUDIO_MAX/2)return 0;if(samples&&sample_count+n<=AUDIO_MAX){memcpy(samples+sample_count,data,n*sizeof(int16_t));sample_count+=n;}return frames;}
static void input_poll(void){}
static int16_t input_state(unsigned port,unsigned device,unsigned index,unsigned id){(void)index;return port==0&&device==RETRO_DEVICE_JOYPAD&&id<16&&((input_mask>>id)&1);}
static void cleanup(void){if(loaded&&p_unload)p_unload();loaded=false;if(initialized&&p_deinit)p_deinit();initialized=false;if(core_handle)dlclose(core_handle);core_handle=NULL;free(game_path);game_path=NULL;free(system_path);system_path=NULL;free(rom_bytes);rom_bytes=NULL;rom_size=0;free(pixels);pixels=NULL;width=height=0;free(samples);samples=NULL;sample_count=0;input_mask=0;}
#define LOAD(var,name) do { *(void **)(&(var))=dlsym(core_handle,(name)); if(!(var)){cleanup();return JNI_FALSE;} } while(0)
JNIEXPORT jboolean JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_open(JNIEnv *env,jclass cls,jstring core,jstring game,jstring directory){
 (void)cls;cleanup();if(!core||!game||!directory)return JNI_FALSE;
 const char *cp=(*env)->GetStringUTFChars(env,core,NULL),*gp=(*env)->GetStringUTFChars(env,game,NULL),*dp=(*env)->GetStringUTFChars(env,directory,NULL);
 if(!cp||!gp||!dp){if(cp)(*env)->ReleaseStringUTFChars(env,core,cp);if(gp)(*env)->ReleaseStringUTFChars(env,game,gp);if(dp)(*env)->ReleaseStringUTFChars(env,directory,dp);return JNI_FALSE;}
 core_handle=dlopen(cp,RTLD_NOW|RTLD_LOCAL);game_path=strdup(gp);system_path=strdup(dp);
 (*env)->ReleaseStringUTFChars(env,core,cp);(*env)->ReleaseStringUTFChars(env,game,gp);(*env)->ReleaseStringUTFChars(env,directory,dp);
 if(!core_handle||!game_path||!system_path){cleanup();return JNI_FALSE;}
 void (*set_env)(retro_environment_t),(*set_video)(retro_video_refresh_t),(*set_sample)(retro_audio_sample_t),(*set_batch)(retro_audio_sample_batch_t),(*set_poll)(retro_input_poll_t),(*set_input)(retro_input_state_t),(*init)(void);
 unsigned (*api_version)(void);void (*sysinfo)(struct retro_system_info*),(*avinfo)(struct retro_system_av_info*);bool (*load)(const struct retro_game_info*);
 LOAD(set_env,"retro_set_environment");LOAD(set_video,"retro_set_video_refresh");LOAD(set_sample,"retro_set_audio_sample");LOAD(set_batch,"retro_set_audio_sample_batch");LOAD(set_poll,"retro_set_input_poll");LOAD(set_input,"retro_set_input_state");LOAD(api_version,"retro_api_version");LOAD(init,"retro_init");LOAD(p_deinit,"retro_deinit");LOAD(sysinfo,"retro_get_system_info");LOAD(avinfo,"retro_get_system_av_info");LOAD(load,"retro_load_game");LOAD(p_unload,"retro_unload_game");LOAD(p_run,"retro_run");LOAD(p_state_size,"retro_serialize_size");LOAD(p_serialize,"retro_serialize");LOAD(p_unserialize,"retro_unserialize");LOAD(p_reset_cheats,"retro_cheat_reset");
 if(api_version()!=1){cleanup();return JNI_FALSE;}samples=malloc(AUDIO_MAX*sizeof(int16_t));if(!samples){cleanup();return JNI_FALSE;}
 set_env(env_cb);set_video(video_cb);set_sample(audio_sample);set_batch(audio_batch);set_poll(input_poll);set_input(input_state);init();initialized=true;
 struct retro_system_info info={0};sysinfo(&info);struct retro_game_info gi={0};gi.path=game_path;
 if(!info.need_fullpath){FILE *f=fopen(game_path,"rb");if(!f){cleanup();return JNI_FALSE;}if(fseek(f,0,SEEK_END)||((rom_size=(size_t)ftell(f))==0)||rom_size>10000000||fseek(f,0,SEEK_SET)){fclose(f);cleanup();return JNI_FALSE;}rom_bytes=malloc(rom_size);if(!rom_bytes||fread(rom_bytes,1,rom_size,f)!=rom_size){fclose(f);cleanup();return JNI_FALSE;}fclose(f);gi.data=rom_bytes;gi.size=rom_size;}
 if(!load(&gi)){cleanup();return JNI_FALSE;}loaded=true;struct retro_system_av_info av={0};avinfo(&av);fps=av.timing.fps;sample_rate=av.timing.sample_rate;return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_step(JNIEnv *e,jclass c,jint mask){(void)e;(void)c;if(loaded){input_mask=(uint32_t)mask;p_run();}}
JNIEXPORT jint JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_width(JNIEnv*e,jclass c){(void)e;(void)c;return (jint)width;}
JNIEXPORT jint JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_height(JNIEnv*e,jclass c){(void)e;(void)c;return (jint)height;}
JNIEXPORT jdouble JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_rate(JNIEnv*e,jclass c){(void)e;(void)c;return sample_rate;}
JNIEXPORT jdouble JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_fps(JNIEnv*e,jclass c){(void)e;(void)c;return fps;}
JNIEXPORT jintArray JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_video(JNIEnv*e,jclass c){(void)c;if(!pixels||!width||!height)return NULL;jsize n=(jsize)(width*height);jintArray a=(*e)->NewIntArray(e,n);if(a)(*e)->SetIntArrayRegion(e,a,0,n,(const jint*)pixels);return a;}
JNIEXPORT jshortArray JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_audio(JNIEnv*e,jclass c){(void)c;if(!sample_count)return NULL;jshortArray a=(*e)->NewShortArray(e,(jsize)sample_count);if(a)(*e)->SetShortArrayRegion(e,a,0,(jsize)sample_count,(const jshort*)samples);sample_count=0;return a;}
JNIEXPORT jbyteArray JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_save(JNIEnv*e,jclass c){(void)c;if(!loaded)return NULL;size_t n=p_state_size();if(!n||n>100000000)return NULL;void *buf=malloc(n);if(!buf)return NULL;if(!p_serialize(buf,n)){free(buf);return NULL;}jbyteArray a=(*e)->NewByteArray(e,(jsize)n);if(a)(*e)->SetByteArrayRegion(e,a,0,(jsize)n,(jbyte*)buf);free(buf);return a;}
JNIEXPORT jboolean JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_restore(JNIEnv*e,jclass c,jbyteArray a){(void)c;if(!loaded||!a)return JNI_FALSE;jsize n=(*e)->GetArrayLength(e,a);if(n<=0||(size_t)n!=p_state_size())return JNI_FALSE;jbyte *b=(*e)->GetByteArrayElements(e,a,NULL);if(!b)return JNI_FALSE;bool ok=p_unserialize(b,(size_t)n);(*e)->ReleaseByteArrayElements(e,a,b,JNI_ABORT);return ok?JNI_TRUE:JNI_FALSE;}
JNIEXPORT void JNICALL Java_com_bitvaultengine_retrocollection_NativeBridge_close(JNIEnv*e,jclass c){(void)e;(void)c;cleanup();}
