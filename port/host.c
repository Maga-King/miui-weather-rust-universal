// ABI reconstructed from the supplied 18.0.0.27-R engine's interface table.
#include <android/dlext.h>
#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdarg.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <android/hardware_buffer.h>
#include <android/bitmap.h>
#include <media/NdkImageReader.h>
#include "xdl.h"

static int diagnostic_fd=-1;
static void host_log(const char *format, ...) {
    char line[4096];va_list args;va_start(args,format);
    int n=vsnprintf(line,sizeof(line)-1,format,args);va_end(args);
    if(n<0)return;if(n>(int)sizeof(line)-2)n=sizeof(line)-2;
    __android_log_write(ANDROID_LOG_INFO,"WeatherHost",line);
    if(diagnostic_fd>=0){line[n++]='\n';write(diagnostic_fd,line,n);}
}
#define LOG(...) host_log(__VA_ARGS__)
static uintptr_t engine_base;
static AAsset *text_font_asset_open(AAssetManager *manager,const char *name,int mode) {
    const char *alias="fonts/WeatherMiSansVF.ttf";
    size_t length=name?strlen(name):0,alias_length=strlen(alias);
    // Only this private Flutter engine's GOT is redirected. Keep the single
    // existing bundled VF file, and leave Android/widget asset access alone.
    if(length>=alias_length&&!strcmp(name+length-alias_length,alias))
        return AAssetManager_open(manager,"fonts/MiSansVF.ttf",mode);
    return AAssetManager_open(manager,name,mode);
}
static void locate_engine(void *interface) {
    Dl_info engine={0};
    if(!dladdr(interface,&engine)||!engine.dli_fbase){LOG("RENDER missing engine base");return;}
    engine_base=(uintptr_t)engine.dli_fbase;
}
static int bind_font_manifest(void) {
    uintptr_t *asset_open=(uintptr_t *)(engine_base+0x11033d8);
    if(!engine_base||!*asset_open)return 0;
    size_t asset_page=(size_t)sysconf(_SC_PAGESIZE);
    uintptr_t asset_start=(uintptr_t)asset_open&~(asset_page-1);
    if(mprotect((void*)asset_start,asset_page,PROT_READ|PROT_WRITE))return 0;
    *asset_open=(uintptr_t)text_font_asset_open;
    if(mprotect((void*)asset_start,asset_page,PROT_READ))return 0;
    const char *system=getenv("WEATHER_SYSTEM_FONT");
    if(!system||strcmp(system,"1")){LOG("FONT MiSans text and original symbol families");return 1;}
    static const char original[]="FontManifest.json",replacement[]="FontSystem__.json";
    char *literal=(char *)(engine_base+0x22e53);
    if(!engine_base||memcmp(literal,original,sizeof(original))){LOG("FONT unsupported manifest literal");return 0;}
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uintptr_t start=(uintptr_t)literal&~(page-1);
    size_t extent=(((uintptr_t)literal+sizeof(original)+page-1)&~(page-1))-start;
    if(mprotect((void *)start,extent,PROT_READ|PROT_WRITE))return 0;
    memcpy(literal,replacement,sizeof(replacement));
    if(mprotect((void *)start,extent,PROT_READ))return 0;
    LOG("FONT Android system text; original symbol families retained");return 1;
}
// Keep these descriptors alive: the engine opens the short /proc/self/fd paths
// lazily when its first smooth-corner or rounded-clip texture is requested.
static int corner_texture_fds[2]={-1,-1};
static int bind_corner_textures(const char *directory) {
    static const struct {const char *name,*system_path;size_t offset;uint64_t hash;} files[]={
        {"data_741236.bin","/product/etc/data_741236.bin",0x24a1f,UINT64_C(0xec8d6857ef9bd716)},
        {"data_741237.bin","/product/etc/data_741237.bin",0x24a02,UINT64_C(0x3077a1b7ad71b8f6)},
    };
    if(!engine_base)return 0;
    for(size_t i=0;i<2;i++){
        char *literal=(char *)(engine_base+files[i].offset);
        size_t original_size=strlen(files[i].system_path)+1;
        if(memcmp(literal,files[i].system_path,original_size)){
            LOG("CORNER unexpected engine resource path at %#lx",(unsigned long)files[i].offset);return 0;
        }
        char path[4096],descriptor_path[40];
        snprintf(path,sizeof(path),"%s/%s",directory,files[i].name);
        int fd=open(path,O_RDONLY|O_CLOEXEC);
        struct stat statbuf;
        if(fd<0||fstat(fd,&statbuf)||statbuf.st_size!=349525){
            LOG("CORNER missing/invalid %s errno=%d",path,errno);if(fd>=0)close(fd);return 0;
        }
        snprintf(descriptor_path,sizeof(descriptor_path),"/proc/self/fd/%d",fd);
        if(strlen(descriptor_path)>=original_size){close(fd);return 0;}
        // Verify the same open + mmap sequence used by fml::FileMapping succeeds
        // through /proc for this UID, and check every mapped byte before patching.
        int verify_fd=open(descriptor_path,O_RDONLY|O_CLOEXEC);
        const unsigned char *bytes=verify_fd>=0?mmap(NULL,349525,PROT_READ,MAP_PRIVATE,verify_fd,0):MAP_FAILED;
        uint64_t hash=UINT64_C(0xcbf29ce484222325);
        if(bytes!=MAP_FAILED){
            for(size_t j=0;j<349525;j++)hash=(hash^bytes[j])*UINT64_C(0x100000001b3);
            munmap((void *)bytes,349525);
        }
        if(verify_fd>=0)close(verify_fd);
        if(bytes==MAP_FAILED||hash!=files[i].hash){
            LOG("CORNER proc mapping/checksum failed %s hash=%016llx errno=%d",files[i].name,(unsigned long long)hash,errno);
            close(fd);return 0;
        }
        size_t page=(size_t)sysconf(_SC_PAGESIZE);
        uintptr_t start=(uintptr_t)literal&~(page-1);
        size_t extent=(((uintptr_t)literal+original_size+page-1)&~(page-1))-start;
        if(mprotect((void *)start,extent,PROT_READ|PROT_WRITE)){
            LOG("CORNER resource relocation failed errno=%d",errno);close(fd);return 0;
        }
        memset(literal,0,original_size);memcpy(literal,descriptor_path,strlen(descriptor_path));
        corner_texture_fds[i]=fd;
        if(mprotect((void *)start,extent,PROT_READ)){
            LOG("CORNER resource protection restore failed errno=%d",errno);return 0;
        }
        LOG("CORNER %s bytes=349525 fnv64=%016llx mapped-and-verified path=%s",files[i].name,(unsigned long long)hash,descriptor_path);
    }
    return 1;
}
typedef struct { const void *ptr; size_t len; } Span;
typedef struct { AAssetManager *assets; const uintptr_t *callbacks; } AssetSource;
static JavaVM *vm;
static jobject activity, engine_assets;
static pthread_mutex_t activity_lock=PTHREAD_MUTEX_INITIALIZER;
static int first_frame_seen;
static jmethodID message_method, frame_method;
static uintptr_t *api, callbacks[16];
static void *runtime, *holder;
static _Atomic float font_weight_scale=1.f;
static int font_weight_features, font_weight_initialized;
// SkParagraph's findTypefaces(vector<SkString>, SkFontStyle, variations, bool,
// float) returns a 24-byte vector in x8. Adjust the requested text style before
// font matching; Xiaomi's set_font_weight_scale only adjusts certain VF faces.
// This engine is private to the in-app Flutter surface. RemoteViews/Canvas
// widgets use Android's font stack and never pass through this hook.
typedef struct {void *begin,*end,*capacity;} TextFaces;
typedef struct {const uintptr_t *begin,*end,*capacity;} TextFamilies;
typedef struct {uint32_t tag;float value;} TextFontAxis;
typedef struct {
    int32_t collection_index,pad0;
    TextFontAxis *begin,*end,*capacity;
    int32_t palette_index,pad1;
    void *palette_begin,*palette_end,*palette_capacity;
    uint8_t present,padding[7];
} TextFontArguments;
_Static_assert(sizeof(TextFontArguments)==72,"FontArguments ABI");
typedef TextFaces (*FindTextFaces)(void*,const TextFamilies*,uint64_t,void*,uint32_t,float);
static FindTextFaces find_text_faces_original;
static int text_mi_sans;
static void *find_fallback_faces_original __attribute__((used));
static unsigned adjusted_text_style(unsigned style,float scale) {
    unsigned weight=(unsigned)((style&0xffffu)*scale+.5f);
    if(weight<100)weight=100;if(weight>900)weight=900;
    return (style&~0xffffu)|weight;
}
static unsigned __attribute__((used,noinline)) weight_fallback_style(unsigned style,int character) {
    // Preserve symbol/emoji glyph faces. This path resolves characters absent
    // from the primary face (notably Chinese text in Android system-font mode).
    if(character>=0xe000&&character<=0xf8ff)return style;
    float scale=atomic_load_explicit(&font_weight_scale,memory_order_relaxed);
    if(scale==1.f)return style;
    unsigned adjusted=adjusted_text_style(style,scale);
    static unsigned diagnostics;
    if(diagnostics++<12)LOG("FONT fallback U+%04X weight=%u -> %u",character,style&0xffff,adjusted&0xffff);
    return adjusted;
}
// C++ sk_sp<SkTypeface> has a nontrivial return ABI in x8, even though it holds
// just one pointer. Preserve that ABI, all input registers and the caller's LR.
__attribute__((naked)) static void weighted_fallback_faces(void) {
    __asm__ volatile(
        "stp x29,x30,[sp,#-96]!\n"
        "stp x0,x1,[sp,#16]\n"
        "stp x2,x3,[sp,#32]\n"
        "stp x4,x5,[sp,#48]\n"
        "stp x6,x7,[sp,#64]\n"
        "str x8,[sp,#80]\n"
        "mov w0,w2\n"
        "bl weight_fallback_style\n"
        "mov w9,w0\n"
        "ldp x0,x1,[sp,#16]\n"
        "ldp x2,x3,[sp,#32]\n"
        "ldp x4,x5,[sp,#48]\n"
        "ldp x6,x7,[sp,#64]\n"
        "ldr x8,[sp,#80]\n"
        "mov w2,w9\n"
        "ldp x29,x30,[sp],#96\n"
        "adrp x16,find_fallback_faces_original\n"
        "ldr x16,[x16,:lo12:find_fallback_faces_original]\n"
        "br x16\n");
}
static TextFaces weighted_text_faces(void *collection,const TextFamilies *families,
        uint64_t style,void *variations,uint32_t flags,float size) {
    int symbols=0;
    const char *family="";
    if(families)for(const uintptr_t *p=families->begin;p<families->end;p++) {
        const char *record=(const char *)*p;
        if(p==families->begin&&record)family=record+8;
        // SkString::Rec: length + reference count + UTF-8 characters.
        if(record&&*(const uint32_t *)record>=17&&!memcmp(record+8,"packages/hyperos/",17)){symbols=1;break;}
    }
    float scale=atomic_load_explicit(&font_weight_scale,memory_order_relaxed);
    TextFontArguments args={0};TextFontAxis axes[32];
    if(!symbols&&text_mi_sans) {
        // All named text faces share the original VF buffer. A family-specific
        // starting axis preserves Xiaomi's light/regular/medium title design.
        float axis=330;
        unsigned base=(unsigned)style&0xffffu;
        if(!strcmp(family,"sans-serif")||!*family) {
            const int weights[]={100,200,300,350,400,500,600,700,800,900};
            const int values[]={150,200,250,305,330,380,450,450,520,600};
            axis=600;
            for(int i=1;i<10;i++)if(base<=(unsigned)weights[i]) {
                axis=values[i-1]+(values[i]-values[i-1])*((int)base-weights[i-1])/(float)(weights[i]-weights[i-1]);break;
            }
        } else if(!strcmp(family,"mipro-light"))axis=250;
        else if(!strcmp(family,"mipro-normal"))axis=305;
        else if(!strcmp(family,"mipro-medium")||!strcmp(family,"sans-serif-medium"))axis=380;
        else if(!strcmp(family,"mipro-semibold"))axis=520;
        // Preserve non-weight coordinates (e.g. width) supplied by the page.
        TextFontArguments *old=(TextFontArguments *)variations;
        size_t count=0;
        if(old&&old->present) {
            args=*old;
            size_t old_count=(size_t)(old->end-old->begin);
            for(size_t i=0;i<old_count&&count<31;i++) {
                if(old->begin[i].tag==0x77676874)axis=old->begin[i].value;
                else axes[count++]=old->begin[i];
            }
        }
        axis*=scale;if(axis<150)axis=150;if(axis>700)axis=700;
        axes[count++]=(TextFontAxis){0x77676874,axis};
        args.begin=axes;args.end=args.capacity=axes+count;args.present=1;variations=&args;
    }
    if(!symbols&&scale!=1.f) {
        unsigned base=(unsigned)style&0xffffu;
        unsigned weight=adjusted_text_style((unsigned)style,scale)&0xffffu;
        style=(style&~UINT64_C(0xffff))|weight;
        static unsigned diagnostics;
        if(diagnostics++<16)LOG("FONT text match weight=%u -> %u size=%.1f",base,weight,size);
    }
    return find_text_faces_original(collection,families,style,variations,flags,size);
}
static void *bind_text_hook(uintptr_t offset,const uint32_t prologue[4],void *hook) {
    uint32_t *entry=(uint32_t *)(engine_base+offset);
    if(!engine_base||memcmp(entry,prologue,16)){LOG("FONT text matcher ABI unsupported at %#lx",offset);return NULL;}
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    unsigned char *trampoline=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_ANONYMOUS|MAP_PRIVATE,-1,0);
    if(trampoline==MAP_FAILED)return NULL;
    memcpy(trampoline,entry,16);
    // ldr x16, .+8; br x16; .quad target. The copied prologue is PC-independent.
    const uint32_t jump[]={0x58000050,0xd61f0200};
    memcpy(trampoline+16,jump,8);
    uintptr_t resume=(uintptr_t)entry+16;memcpy(trampoline+24,&resume,8);
    __builtin___clear_cache((char *)trampoline,(char *)trampoline+32);
    if(mprotect(trampoline,page,PROT_READ|PROT_EXEC)){munmap(trampoline,page);return NULL;}
    uintptr_t first=(uintptr_t)entry&~(page-1);
    if(mprotect((void *)first,page,PROT_READ|PROT_WRITE|PROT_EXEC))return NULL;
    memcpy(entry,jump,8);memcpy((char *)entry+8,&hook,8);
    __builtin___clear_cache((char *)entry,(char *)entry+16);
    if(mprotect((void *)first,page,PROT_READ|PROT_EXEC))return NULL;
    return trampoline;
}
static int bind_text_weight(void) {
    const char *system=getenv("WEATHER_SYSTEM_FONT");text_mi_sans=!system||strcmp(system,"1");
    static const uint32_t primary[]={0xfc180fea,0x6d0123e9,0xa9027bfd,0xa9036ffc};
    static const uint32_t fallback[]={0xd10443ff,0xfd004bea,0x6d0a23e9,0xa90b7bfd};
    find_text_faces_original=(FindTextFaces)bind_text_hook(0xae9a3c,primary,(void*)weighted_text_faces);
    find_fallback_faces_original=bind_text_hook(0xaeb3d8,fallback,(void*)weighted_fallback_faces);
    if(!find_text_faces_original||!find_fallback_faces_original)return 0;
    LOG("FONT in-app text weight matcher installed; symbol fonts excluded");return 1;
}
JNIEXPORT jboolean JNICALL Java_dev_weather_oneplus_WeatherFonts_nativeWeight(JNIEnv *env,jclass type,jfloat scale,jint features) {
    (void)env;(void)type;
    if(!holder||!api||!(scale>=.5f&&scale<=1.75f))return JNI_FALSE;
    // Verified against this engine's own library_loader.cc diagnostics and ABI:
    // 0x90: set_accessibility_features(holder,uint32_t)
    // 0x98: set_font_weight_scale(holder,float), default 1.0 in FontCollection.
    if(!find_text_faces_original||api[0x88/8]!=engine_base+0x6ae8d4||api[0x90/8]!=engine_base+0x6ae940||api[0x98/8]!=engine_base+0x6ae9bc){LOG("FONT weight ABI unsupported");return JNI_FALSE;}
    features&=8; // Flutter AccessibilityFeature.boldText; no second weight multiplier.
    if(font_weight_initialized&&font_weight_scale==scale&&font_weight_features==features)return JNI_FALSE;
    atomic_store_explicit(&font_weight_scale,scale,memory_order_relaxed);
    ((void (*)(void*,int))api[0x90/8])(holder,features);
    // Match weights exactly once. Reload also clears the engine font/shaping
    // caches, including paragraphs built before returning from settings.
    ((void (*)(void*,float))api[0x98/8])(holder,1.f);
    ((void (*)(void*))api[0x88/8])(holder);
    font_weight_features=features;font_weight_initialized=1;
    LOG("FONT weight scale=%.3f accessibilityBold=%d",scale,features!=0);
    return JNI_TRUE;
}
static ANativeWindow *window;
static double viewport[32];
// Supply the original glass compositor with the actual Majestic background.
// Each submitted frame owns an immutable AHB; the engine acquires its own ref
// before handing it to the raster thread. No screen capture permission is used.
typedef struct { AHardwareBuffer *buffer; int width,height; } GlassFrame;
typedef struct {void *context;const uintptr_t *callbacks;} GlassSurfaceTexture;
static void glass_frame_release(void *context) {
    GlassFrame *frame=context;
    if(frame){AHardwareBuffer_release(frame->buffer);free(frame);}
}
static AHardwareBuffer *glass_frame_get(void *context,int32_t *slot,int32_t *dataspace,
        int32_t *hdr,float *matrix,uint32_t *transform,uint8_t *fresh,void *release_callback,
        void *fence_wait,void *fence_context,ARect *crop) {
    (void)release_callback;(void)fence_wait;
    GlassFrame *frame=context;
    static unsigned reads;
    if(++reads==1)LOG("GLASS engine acquired original weather backdrop %dx%d",frame->width,frame->height);
    if(slot)*slot=-1;
    if(dataspace)*dataspace=0x08810000; // Android DATASPACE_SRGB
    if(hdr)*hdr=0;
    if(transform)*transform=0;
    if(fresh)*fresh=1;
    if(fence_context)*(int *)fence_context=-1; // CPU producer has already unlocked.
    if(matrix){memset(matrix,0,16*sizeof(float));matrix[0]=matrix[5]=matrix[10]=matrix[15]=1;}
    if(crop)*crop=(ARect){0,0,frame->width,frame->height};
    AHardwareBuffer_acquire(frame->buffer);
    return frame->buffer;
}
static const uintptr_t glass_frame_callbacks[]={0x18,(uintptr_t)glass_frame_release,(uintptr_t)glass_frame_get};
JNIEXPORT void JNICALL Java_dev_weather_oneplus_MajesticSurface_nativeGlassBackdrop(JNIEnv *env,jobject self,jobject bitmap) {
    (void)self;
    if(!holder||!window)return;
    AndroidBitmapInfo info={0};void *pixels=NULL;
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)return;
    AHardwareBuffer_Desc desc={.width=info.width,.height=info.height,.layers=1,.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage=AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE};
    GlassFrame *frame=calloc(1,sizeof(*frame));
    if(!frame)return;
    if(AHardwareBuffer_allocate(&desc,&frame->buffer)){free(frame);return;}
    frame->width=info.width;frame->height=info.height;
    void *target=NULL;
    if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS){glass_frame_release(frame);return;}
    if(AHardwareBuffer_lock(frame->buffer,AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,-1,NULL,&target)){
        AndroidBitmap_unlockPixels(env,bitmap);glass_frame_release(frame);return;
    }
    AHardwareBuffer_describe(frame->buffer,&desc);
    for(uint32_t y=0;y<info.height;y++)memcpy((uint8_t *)target+y*desc.stride*4,(uint8_t *)pixels+y*info.stride,info.width*4);
    AHardwareBuffer_unlock(frame->buffer,NULL);
    AndroidBitmap_unlockPixels(env,bitmap);
    ((void (*)(void *,int64_t,GlassSurfaceTexture))api[0x1b0/8])(holder,0,(GlassSurfaceTexture){frame,glass_frame_callbacks});
    ((void (*)(void *,int64_t,uint8_t))api[0x1b8/8])(holder,0,1);
    static unsigned count;
    if(++count==1||count%300==0)LOG("GLASS supplied original weather backdrop %ux%u frame=%u",info.width,info.height,count);
}
// Engine callbacks can run concurrently with Activity replacement. A local JNI
// reference keeps the callback target alive after the lock is released.
static jobject current_activity(JNIEnv *env,jmethodID *method,int frame) {
    pthread_mutex_lock(&activity_lock);
    jobject target=activity?(*env)->NewLocalRef(env,activity):NULL;
    if(method)*method=frame?frame_method:message_method;
    if(frame)first_frame_seen=1;
    pthread_mutex_unlock(&activity_lock);
    return target;
}
static int is_current_activity(JNIEnv *env,jobject self) {
    pthread_mutex_lock(&activity_lock);
    int matches=activity&&(*env)->IsSameObject(env,activity,self);
    pthread_mutex_unlock(&activity_lock);
    return matches;
}
static int bind_activity(JNIEnv *env,jobject self) {
    (*env)->GetJavaVM(env,&vm);
    jclass cls=(*env)->GetObjectClass(env,self);
    jmethodID next_message=(*env)->GetMethodID(env,cls,"onPlatformMessage","(Ljava/lang/String;[BJ)V");
    jmethodID next_frame=(*env)->GetMethodID(env,cls,"onFirstFrame","()V");
    (*env)->DeleteLocalRef(env,cls);
    if(!next_message||!next_frame)return 0;
    jobject next_activity=(*env)->NewGlobalRef(env,self);
    if(!next_activity)return 0;
    pthread_mutex_lock(&activity_lock);
    jobject previous=activity;
    activity=next_activity;message_method=next_message;frame_method=next_frame;
    int displayed=first_frame_seen;
    pthread_mutex_unlock(&activity_lock);
    if(previous)(*env)->DeleteGlobalRef(env,previous);
    if(displayed)(*env)->CallVoidMethod(env,self,next_frame);
    return 1;
}
static Span span(const char *s) { return (Span){s, strlen(s)}; }
static JNIEnv *thread_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK;
    if (*attached && (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
    return env;
}
// The original MainScene uses a native child surface, separate from Flutter.
// Replace only libhyper_opengl's cached surface-provider function pointers with
// an Android SurfaceView provider; all scene, shader and animation code remains
// in the original library. HyperNativeWindowCallback owns its native C++ object.
static pthread_mutex_t mgl_lock;
static pthread_once_t mgl_lock_once=PTHREAD_ONCE_INIT;
static jobject mgl_java;
static jmethodID mgl_request_method,mgl_hide_method;
static void *mgl_callback;
static ANativeWindow *mgl_window;
static uint64_t mgl_generation,mgl_token,mgl_surface_id;
static int mgl_width,mgl_height;
static float mgl_x,mgl_y;
static atomic_uint display_refresh_millihz;
static int mgl_native_resolution=1;
static uint32_t *mgl_render_scale_instruction;
// All patches below are one aligned AArch64 instruction, checked against this
// frozen renderer before writing. No scene, shader, quality tier or animation
// timestamp is replaced. Only its extra frame-rate gate and render scale change.
static int mgl_patch_instruction(uint32_t *target,uint32_t value) {
    if(*target==value)return 1;
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uintptr_t start=(uintptr_t)target&~(page-1);
    if(mprotect((void *)start,page,PROT_READ|PROT_WRITE|PROT_EXEC)){
        LOG("MGL render instruction write denied errno=%d",errno);return 0;
    }
    __atomic_store_n(target,value,__ATOMIC_RELEASE);
    __builtin___clear_cache((char *)target,(char *)(target+1));
    if(mprotect((void *)start,page,PROT_READ|PROT_EXEC)){
        LOG("MGL render instruction protection restore failed errno=%d",errno);return 0;
    }
    return 1;
}
static int configure_mgl_rendering(void *library,uintptr_t base) {
    uint32_t *constructor=dlsym(library,"_ZN7weather9FrameSyncC2Ev");
    uint32_t *set_rate=dlsym(library,"_ZN7weather9FrameSync12setFrameRateEi");
    uint32_t *scale=dlsym(library,"_ZNK11DeviceUtils14getRenderScaleEv");
    // Constructor: str x8,[x0,#0x10] -> str xzr,[x0,#0x10].
    // Setter: cmp w1,#1 -> b <its own zero-interval branch>.
    // This removes the default 15 ms / configured 30/60 Hz software gate;
    // AChoreographer still supplies every actual display vsync and real delta.
    if((uintptr_t)constructor!=base+0xffc38||(uintptr_t)set_rate!=base+0xffec4||
            (uintptr_t)scale!=base+0xee418||constructor[11]!=0xf9000808||
            set_rate[0]!=0x7100043f||set_rate[12]!=0xf900081f||
            scale[0]!=0xbd400400||scale[1]!=0xd65f03c0){
        LOG("MGL unsupported frame pacing/render scale ABI");return 0;
    }
    if(!mgl_patch_instruction(constructor+11,0xf900081f)||
            !mgl_patch_instruction(set_rate,0x1400000c))return 0;
    mgl_render_scale_instruction=scale;
    if(!mgl_patch_instruction(scale,mgl_native_resolution?0x1e2e1000:0xbd400400))return 0;
    LOG("MGL display-vsync pacing enabled; native resolution=%d render scale=%s",
        mgl_native_resolution,mgl_native_resolution?"1.0":"original device scale");
    return 1;
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherRenderPreferences_nativeRefreshRate(JNIEnv *env,jclass cls,jfloat actual) {
    (void)env;(void)cls;
    if(!(actual>0&&actual<=1000))return;
    unsigned rate=(unsigned)(actual*1000.0f+0.5f);
    unsigned previous=atomic_exchange(&display_refresh_millihz,rate);
    if(previous==rate)return;
    LOG("DISPLAY actual refresh=%.3f Hz; engine compatibility report=120 Hz (not measured FPS)",rate/1000.0);
}
JNIEXPORT jboolean JNICALL Java_dev_weather_oneplus_WeatherRenderPreferences_nativeResolution(JNIEnv *env,jclass cls,jboolean enabled) {
    (void)env;(void)cls;
    int next=enabled?1:0;
    if(next==mgl_native_resolution)return JNI_FALSE;
    if(mgl_render_scale_instruction&&
            !mgl_patch_instruction(mgl_render_scale_instruction,next?0x1e2e1000:0xbd400400))return JNI_FALSE;
    mgl_native_resolution=next;
    LOG("MGL native resolution preference=%d",next);
    // Before engine startup there is no window to recreate yet.
    return mgl_render_scale_instruction?JNI_TRUE:JNI_FALSE;
}
static void init_mgl_lock(void) {
    pthread_mutexattr_t attr;pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr,PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&mgl_lock,&attr);pthread_mutexattr_destroy(&attr);
}
static void lock_mgl(void){pthread_once(&mgl_lock_once,init_mgl_lock);pthread_mutex_lock(&mgl_lock);}
static void mgl_call_window(size_t slot,ANativeWindow *native_window) {
    if(!mgl_callback)return;
    uintptr_t *vtable=*(uintptr_t **)mgl_callback;
    if(slot<2)((void (*)(void*,ANativeWindow*))vtable[slot])(mgl_callback,native_window);
    else ((void (*)(void*))vtable[slot])(mgl_callback);
}
static void mgl_release_window(void) {
    if(!mgl_window)return;
    mgl_call_window(2,NULL);
    ANativeWindow_release(mgl_window);mgl_window=NULL;
    LOG("MGL original scene window destroyed generation=%llu",(unsigned long long)mgl_generation);
}
// Caller holds mgl_lock. Java only posts work; it does not reenter synchronously.
static void request_mgl_surface(void) {
    if(!mgl_java||!mgl_callback)return;
    int attached;JNIEnv *env=thread_env(&attached);
    if(!env)return;
    (*env)->CallVoidMethod(env,mgl_java,mgl_request_method,(jlong)mgl_generation,
        mgl_width,mgl_height,mgl_x,mgl_y);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionDescribe(env);(*env)->ExceptionClear(env);}
    if(attached)(*vm)->DetachCurrentThread(vm);
}
static uint64_t mgl_create_surface(float x,float y,uint64_t token,uint64_t surface_id,
        int width,int height,void **callback) {
    lock_mgl();
    mgl_release_window();
    if(mgl_callback)mgl_call_window(4,NULL); // Deleting destructor.
    mgl_callback=callback?*callback:NULL;
    if(callback)*callback=NULL; // Move the original unique_ptr into this provider.
    mgl_token=token;mgl_surface_id=surface_id;mgl_width=width;mgl_height=height;mgl_x=x;mgl_y=y;
    ++mgl_generation;
    LOG("MGL create original scene token=%llu surface=%llu size=%dx%d callback=%p generation=%llu",
        (unsigned long long)token,(unsigned long long)surface_id,width,height,mgl_callback,
        (unsigned long long)mgl_generation);
    request_mgl_surface();
    pthread_mutex_unlock(&mgl_lock);
    return 0;
}
static uint64_t mgl_create_surface_origin(uint64_t token,uint64_t surface_id,void **callback) {
    return mgl_create_surface(0,0,token,surface_id,(int)viewport[1],(int)viewport[2],callback);
}
static void mgl_update_position(float x,float y,void *native_window,uint64_t token,uint64_t surface_id) {
    (void)native_window;
    lock_mgl();
    if(mgl_callback&&token==mgl_token&&surface_id==mgl_surface_id){
        mgl_x=x;mgl_y=y;request_mgl_surface();
    }
    pthread_mutex_unlock(&mgl_lock);
}
static void mgl_update_surface(float x,float y,void *native_window,uint64_t token,uint64_t surface_id,
        int width,int height) {
    (void)native_window;
    lock_mgl();
    if(mgl_callback&&token==mgl_token&&surface_id==mgl_surface_id){
        mgl_x=x;mgl_y=y;mgl_width=width;mgl_height=height;request_mgl_surface();
    }
    pthread_mutex_unlock(&mgl_lock);
}
static void mgl_destroy_surface(uint64_t token,uint64_t surface_id) {
    lock_mgl();
    if(mgl_callback&&token==mgl_token&&surface_id==mgl_surface_id){
        mgl_release_window();mgl_call_window(4,NULL);mgl_callback=NULL;
        int attached;JNIEnv *env=thread_env(&attached);
        if(env&&mgl_java){
            (*env)->CallVoidMethod(env,mgl_java,mgl_hide_method,(jlong)mgl_generation);
            if((*env)->ExceptionCheck(env)){(*env)->ExceptionDescribe(env);(*env)->ExceptionClear(env);}
        }
        if(attached&&env)(*vm)->DetachCurrentThread(vm);
        LOG("MGL original scene surface released");
    }
    pthread_mutex_unlock(&mgl_lock);
}
static void mgl_first_frame(void) {LOG("MGL original scene first frame complete");}
typedef struct MglImage {
    struct MglImage *next;
    pthread_mutex_t lock;
    atomic_uint refs;
    atomic_int closing,engine_released;
    atomic_int_fast64_t id;
    uint64_t token;
    AImageReader *reader;
    AImage *current;
    ANativeWindow *producer;
    int first_buffer;
} MglImage;
static pthread_mutex_t mgl_images_lock=PTHREAD_MUTEX_INITIALIZER;
static MglImage *mgl_images;
static void mgl_image_drop(MglImage *image) {
    if(atomic_fetch_sub(&image->refs,1)!=1)return;
    atomic_store(&image->closing,1);
    // Stop listener delivery before freeing its context. Never hold image->lock
    // while deleting the reader: an outstanding listener may be waiting for it.
    if(image->reader)AImageReader_setImageListener(image->reader,NULL);
    if(image->current)AImage_delete(image->current);
    if(image->reader)AImageReader_delete(image->reader);
    // producer is borrowed from reader and must not be independently released.
    LOG("MGL city texture released id=%lld",(long long)atomic_load(&image->id));
    pthread_mutex_destroy(&image->lock);free(image);
}
static void mgl_image_release(void *context) {
    MglImage *image=context;
    atomic_store(&image->engine_released,1);atomic_store(&image->closing,1);
    pthread_mutex_lock(&mgl_images_lock);
    MglImage **entry=&mgl_images;
    while(*entry&&*entry!=image)entry=&(*entry)->next;
    if(*entry)*entry=image->next;
    pthread_mutex_unlock(&mgl_images_lock);
    mgl_image_drop(image);
}
static AHardwareBuffer *mgl_image_buffer(void *context,int (*crop)[4],int *fence) {
    MglImage *image=context;
    if(fence)*fence=-1; // Synchronous acquisition waits for the producer fence.
    if(crop)memset(crop,0,sizeof(*crop));
    if(atomic_load(&image->closing))return NULL;
    pthread_mutex_lock(&image->lock);
    AImage *next=NULL;
    media_status_t status=AImageReader_acquireLatestImage(image->reader,&next);
    if(status==AMEDIA_OK&&next) {
        if(image->current)AImage_delete(image->current);
        image->current=next;
    }else if(status!=AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE){
        LOG("MGL city image acquire failed id=%lld status=%d",(long long)atomic_load(&image->id),status);
    }
    AHardwareBuffer *buffer=NULL;
    if(image->current) {
        AImageCropRect rect={0};
        if(crop&&AImage_getCropRect(image->current,&rect)==AMEDIA_OK){
            (*crop)[0]=rect.left;(*crop)[1]=rect.top;(*crop)[2]=rect.right;(*crop)[3]=rect.bottom;
        }
        if(AImage_getHardwareBuffer(image->current,&buffer)!=AMEDIA_OK)buffer=NULL;
        if(buffer&&!image->first_buffer) {
            AHardwareBuffer_Desc desc={0};AHardwareBuffer_describe(buffer,&desc);
            image->first_buffer=1;
            LOG("MGL city original frame id=%lld buffer=%ux%u format=%u",
                (long long)atomic_load(&image->id),desc.width,desc.height,desc.format);
        }
    }
    pthread_mutex_unlock(&image->lock);
    // Borrowed buffer stays valid through the held current image. Flutter's AHB
    // texture importer retains its own GPU reference, as with the original provider.
    return buffer;
}
static AHardwareBuffer *mgl_image_buffer_without_crop(void *context) {
    return mgl_image_buffer(context,NULL,NULL);
}
static const uintptr_t mgl_image_callbacks[]={
    0x20,(uintptr_t)mgl_image_release,(uintptr_t)mgl_image_buffer_without_crop,(uintptr_t)mgl_image_buffer,
};
typedef struct {void *context;const uintptr_t *callbacks;} HyperImageTexture;
static void mgl_image_available(void *context,AImageReader *reader) {
    (void)reader;
    MglImage *image=context;
    if(atomic_load(&image->closing))return;
    int64_t id=atomic_load(&image->id);
    if(id>=0&&holder)((void (*)(void*,int64_t))api[0xe8/8])(holder,id);
}
static ANativeWindow *mgl_create_image_reader(int64_t *texture_id,uint64_t token) {
    if(texture_id)*texture_id=-1;
    if(!holder||!texture_id)return NULL;
    MglImage *image=calloc(1,sizeof(*image));if(!image)return NULL;
    pthread_mutex_init(&image->lock,NULL);atomic_init(&image->refs,1);
    atomic_init(&image->id,-1);atomic_init(&image->closing,0);atomic_init(&image->engine_released,0);
    image->token=token;
    media_status_t result=AImageReader_newWithUsage(1,1,AIMAGE_FORMAT_PRIVATE,
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,3,&image->reader);
    if(result==AMEDIA_OK)result=AImageReader_getWindow(image->reader,&image->producer);
    AImageReader_ImageListener listener={image,mgl_image_available};
    if(result==AMEDIA_OK)result=AImageReader_setImageListener(image->reader,&listener);
    if(result!=AMEDIA_OK||!image->producer){
        LOG("MGL city reader creation failed status=%d",result);mgl_image_drop(image);return NULL;
    }
    // One creation reference plus one adopted by the Flutter wrapper. This also
    // covers synchronous wrapper disposal if the selected backend rejects images.
    atomic_fetch_add(&image->refs,1);
    int64_t id=((int64_t (*)(void*,HyperImageTexture))api[0xd8/8])
        (holder,(HyperImageTexture){image,mgl_image_callbacks});
    if(id<0||atomic_load(&image->engine_released)){
        LOG("MGL city texture registration failed id=%lld",(long long)id);
        if(!atomic_load(&image->engine_released))mgl_image_release(image);
        mgl_image_drop(image);return NULL;
    }
    atomic_store(&image->id,id);
    pthread_mutex_lock(&mgl_images_lock);image->next=mgl_images;mgl_images=image;pthread_mutex_unlock(&mgl_images_lock);
    *texture_id=id;
    ANativeWindow *producer=image->producer;
    LOG("MGL city texture registered id=%lld token=%llu window=%p",(long long)id,(unsigned long long)token,producer);
    mgl_image_drop(image);
    return producer;
}
static void mgl_unregister_image(int64_t id,uint64_t token) {
    int found=0;
    pthread_mutex_lock(&mgl_images_lock);
    for(MglImage *image=mgl_images;image;image=image->next){
        if(atomic_load(&image->id)==id&&image->token==token){atomic_store(&image->closing,1);found=1;break;}
    }
    pthread_mutex_unlock(&mgl_images_lock);
    if(found&&holder){
        LOG("MGL unregister city texture id=%lld",(long long)id);
        ((void (*)(void*,int64_t))api[0xe0/8])(holder,id);
    }
}
static int bind_mgl_surfaces(void *library) {
    void *getter=dlsym(library,"_ZN7weather17LibSurfaceTexture42getCreateOnlineSurfaceViewWithPositionFuncEv");
    Dl_info native={0};
    if(!getter||!dladdr(getter,&native)||(uintptr_t)getter-(uintptr_t)native.dli_fbase!=0x11f83c){
        LOG("MGL unsupported surface provider ABI");return 0;
    }
    if(!configure_mgl_rendering(library,(uintptr_t)native.dli_fbase))return 0;
    const struct {size_t offset;uintptr_t implementation;} providers[]={
        {0x146850,(uintptr_t)mgl_create_image_reader},
        {0x146858,(uintptr_t)mgl_unregister_image},
        {0x146868,(uintptr_t)mgl_first_frame},
        {0x146870,(uintptr_t)mgl_create_surface_origin},
        {0x146878,(uintptr_t)mgl_create_surface},
        {0x146880,(uintptr_t)mgl_update_position},
        {0x146888,(uintptr_t)mgl_update_surface},
        {0x146890,(uintptr_t)mgl_destroy_surface},
    };
    for(size_t i=0;i<sizeof(providers)/sizeof(providers[0]);i++) {
        uintptr_t *slot=(uintptr_t *)((char *)native.dli_fbase+providers[i].offset);
        if(*slot&&*slot!=providers[i].implementation){LOG("MGL surface provider already initialized");return 0;}
    }
    for(size_t i=0;i<sizeof(providers)/sizeof(providers[0]);i++)
        *(uintptr_t *)((char *)native.dli_fbase+providers[i].offset)=providers[i].implementation;
    LOG("MGL original renderer bound to Android surface provider");
    return 1;
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_MajesticSurface_nativeBind(JNIEnv *env,jobject self) {
    (*env)->GetJavaVM(env,&vm);
    jclass cls=(*env)->GetObjectClass(env,self);
    jmethodID request=(*env)->GetMethodID(env,cls,"requestSurface","(JIIFF)V");
    jmethodID hide=(*env)->GetMethodID(env,cls,"hideSurface","(J)V");
    (*env)->DeleteLocalRef(env,cls);
    if(!request||!hide)return;
    jobject next=(*env)->NewGlobalRef(env,self);if(!next)return;
    lock_mgl();
    if(mgl_java&&(*env)->IsSameObject(env,self,mgl_java)){
        (*env)->DeleteGlobalRef(env,next);request_mgl_surface();pthread_mutex_unlock(&mgl_lock);return;
    }
    mgl_release_window();
    jobject previous=mgl_java;mgl_java=next;mgl_request_method=request;mgl_hide_method=hide;
    if(previous)(*env)->DeleteGlobalRef(env,previous);
    request_mgl_surface();pthread_mutex_unlock(&mgl_lock);
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_MajesticSurface_nativeUnbind(JNIEnv *env,jobject self) {
    lock_mgl();
    if(mgl_java&&(*env)->IsSameObject(env,self,mgl_java)) {
        mgl_release_window();(*env)->DeleteGlobalRef(env,mgl_java);mgl_java=NULL;
    }
    pthread_mutex_unlock(&mgl_lock);
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_MajesticSurface_nativeWindow(JNIEnv *env,jobject self,
        jlong generation,jobject surface) {
    lock_mgl();
    if(!mgl_java||!(*env)->IsSameObject(env,self,mgl_java)||
            (uint64_t)generation!=mgl_generation||!mgl_callback)goto done;
    if(!surface){mgl_release_window();goto done;}
    ANativeWindow *next=ANativeWindow_fromSurface(env,surface);
    if(!next)goto done;
    if(next!=mgl_window) {
        mgl_release_window();mgl_window=next;
        mgl_call_window(0,mgl_window);
        LOG("MGL delivered original scene window=%p generation=%llu",mgl_window,(unsigned long long)mgl_generation);
    }else {
        ANativeWindow_release(next);
        mgl_call_window(1,mgl_window);
    }
done:
    pthread_mutex_unlock(&mgl_lock);
}
static uintptr_t unused_callback(void) { return 0; }
static void batch_create(void *context) { LOG("COMPOSE batch create context=%p tid=%d",context,gettid()); }
static void batch_begin(void *context,int64_t view) { LOG("COMPOSE batch begin context=%p view=%lld tid=%d",context,(long long)view,gettid()); }
static void batch_apply(void *context) { LOG("COMPOSE batch apply context=%p tid=%d",context,gettid()); }
static void blur_light(void *context,int64_t view,const float (*values)[27],int count,const int *ids,const int (*ranges)[2]) {
    LOG("COMPOSE blur context=%p view=%lld values=%p count=%d ids=%p ranges=%p",context,(long long)view,values,count,ids,ranges);
}
static void window_first_frame(void *context,int64_t view) { LOG("COMPOSE first frame context=%p view=%lld",context,(long long)view); }
static void *asset_manager_handle(void *context) { return context; }
static void first_frame(void *context) {
    (void)context;
    LOG("FIRST_FRAME");
    int attached;
    JNIEnv *env = thread_env(&attached);
    if (env) {
        jmethodID method;
        jobject target=current_activity(env,&method,1);
        if(target){(*env)->CallVoidMethod(env,target,method);(*env)->DeleteLocalRef(env,target);}
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionDescribe(env);(*env)->ExceptionClear(env);}
    }
    if (attached && env) (*vm)->DetachCurrentThread(vm);
}
static void message(void *context, const char *channel, size_t channel_length, size_t reply,
                    const uint8_t *data, size_t length) {
    (void)context;
    int attached;
    JNIEnv *env = thread_env(&attached);
    if (!env) return;
    // HyperMessenger uses SIZE_MAX with a NULL pointer for Dart send(null).
    // Pigeon directory requests rely on this form; it is an empty payload.
    if(!data&&length==SIZE_MAX)length=0;
    if (!channel || channel_length > 4096 || length > 16*1024*1024) goto cleanup;
    jmethodID method;
    jobject target=current_activity(env,&method,0);
    if(!target){
        if(holder&&reply)((void (*)(void*,const void*,size_t,size_t))api[0x78/8])(holder,NULL,0,reply);
        goto cleanup;
    }
    char name[4097];
    memcpy(name, channel, channel_length); name[channel_length] = 0;
    jstring jname = (*env)->NewStringUTF(env, name);
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (length) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)data);
    (*env)->CallVoidMethod(env, target, method, jname, bytes, (jlong)reply);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    (*env)->DeleteLocalRef(env, bytes); (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env,target);
cleanup:
    if (attached) (*vm)->DetachCurrentThread(vm);
}
static void dispatch(const char *channel, const void *data, size_t length) {
    if (!holder) return;
    ((void (*)(void *, const char *, size_t, const void *, size_t, size_t))api[0x60/8])
        (holder, channel, strlen(channel), data, length, 0);
}
JNIEXPORT jstring JNICALL Java_dev_weather_oneplus_WeatherActivity_startEngine(JNIEnv *env, jobject self,
        jstring libraries, jstring extra, jstring data_dir, jobject assets, jstring backend) {
    if(!bind_activity(env,self))return NULL; // Preserve the pending JNI exception.
    if (holder) {
        LOG("Rebound existing engine to current Activity");
        return NULL;
    }
    const char *lib = (*env)->GetStringUTFChars(env, libraries, NULL);
    const char *more = (*env)->GetStringUTFChars(env, extra, NULL);
    const char *data = (*env)->GetStringUTFChars(env, data_dir, NULL);
    const char *requested_backend = backend ? (*env)->GetStringUTFChars(env, backend, NULL) : NULL;
    char diagnostic_path[4096];snprintf(diagnostic_path,sizeof(diagnostic_path),"%s/native-host.log",data);
    diagnostic_fd=open(diagnostic_path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    char paths[8192], error[4096] = {0};
    snprintf(paths, sizeof(paths), "%s:%s", lib, more);
    void *linker = xdl_open("linker64", XDL_DEFAULT);
    typedef struct android_namespace_t *(*CreateNS)(const char*,const char*,const char*,uint64_t,const char*,struct android_namespace_t*,const void*);
    CreateNS create = linker ? (CreateNS)xdl_sym(linker, "__loader_android_create_namespace", NULL) : NULL;
    struct android_namespace_t *ns = create ? create("weather_host", paths, "/system/lib64:/system_ext/lib64", 2, NULL, NULL, (void *)malloc) : NULL;
    android_dlextinfo info = {.flags=ANDROID_DLEXT_USE_NAMESPACE, .library_namespace=ns};
    const char *names[] = {"libweathercompat.so", "libhyper_os_flutter.so", "libapp.so", "libhyper_opengl.so"};
    for (size_t i = 0; i < sizeof(names)/sizeof(names[0]); i++) {
        char path[4096]; snprintf(path, sizeof(path), "%s/%s", lib, names[i]);
        void *handle = ns ? android_dlopen_ext(path, RTLD_NOW|RTLD_GLOBAL, &info) : dlopen(path, RTLD_NOW|RTLD_GLOBAL);
        if (!handle) { snprintf(error, sizeof(error), "%s: %s", names[i], dlerror()); goto done; }
        if (i == 1) api = dlsym(handle, "HYPER_FLUTTER_INTERFACE");
        if(i==3&&!bind_mgl_surfaces(handle)){snprintf(error,sizeof(error),"MGL surface provider binding failed");goto done;}
        LOG("Loaded %s", names[i]);
    }
    if (!api || api[0] != 0x268) { snprintf(error, sizeof(error), "Unexpected engine interface"); goto done; }
    locate_engine(api);
    if(!bind_font_manifest()){snprintf(error,sizeof(error),"Font manifest selection failed");goto done;}
    if(!bind_text_weight()){snprintf(error,sizeof(error),"Text weight matcher binding failed");goto done;}
    if(!bind_corner_textures(more)){snprintf(error,sizeof(error),"Engine corner textures could not be loaded");goto done;}
    callbacks[0] = 0x68;
    for (size_t i = 1; i < 13; i++) callbacks[i] = (uintptr_t)unused_callback;
    callbacks[2] = (uintptr_t)first_frame;
    callbacks[4] = (uintptr_t)message;
    callbacks[8] = (uintptr_t)window_first_frame;
    callbacks[9] = (uintptr_t)batch_create;
    callbacks[10] = (uintptr_t)batch_begin;
    callbacks[11] = (uintptr_t)batch_apply;
    callbacks[12] = (uintptr_t)blur_light;
    callbacks[13] = 0x18;
    callbacks[14] = (uintptr_t)unused_callback;
    callbacks[15] = (uintptr_t)asset_manager_handle;
    char aot[4096]; snprintf(aot, sizeof(aot), "--aot-shared-library-name=%s/libapp.so", lib);
    const char *render_option=requested_backend && !strcmp(requested_backend,"opengles") ? "--impeller-backend=opengles" : "--impeller-backend=vulkan";
    LOG("Renderer %s",render_option);
    Span arguments[] = {span(aot), span("--icu-symbol-prefix=_binary_icudtl_dat"), span(render_option), span("--no-enable-merged-platform-ui-thread")};
    typedef void *(*CreateRuntime)(uint32_t, Span, Span, Span, Span);
    LOG("Creating engine runtime");
    runtime = ((CreateRuntime)api[1])(0x02000000, (Span){NULL,0}, span(data), span(data), (Span){arguments, sizeof(arguments)/sizeof(arguments[0])});
    LOG("Runtime=%p", runtime);
    if (!runtime) { snprintf(error, sizeof(error), "Engine runtime creation failed"); goto done; }
    holder = ((void *(*)(void *, void *, uintptr_t *))api[0x40/8])(runtime, &runtime, callbacks);
    LOG("Shell=%p", holder);
    if (!holder) { snprintf(error, sizeof(error), "Engine shell creation failed"); goto done; }
    // Native AAssetManager is valid only while its Java object is retained.
    // The engine survives Activity recreation, so this reference does as well.
    engine_assets=(*env)->NewGlobalRef(env,assets);
    if(!engine_assets){snprintf(error,sizeof(error),"Could not retain engine assets");goto done;}
    typedef void (*Launch)(void*, Span, Span, Span, AssetSource, Span);
    ((Launch)api[0x58/8])(holder, span("main"), (Span){NULL,0}, (Span){NULL,0},
        (AssetSource){AAssetManager_fromJava(env, engine_assets), callbacks+13}, (Span){NULL,0});
    LOG("Dart launch returned");
done:
    (*env)->ReleaseStringUTFChars(env, libraries, lib);
    (*env)->ReleaseStringUTFChars(env, extra, more);
    (*env)->ReleaseStringUTFChars(env, data_dir, data);
    if(requested_backend)(*env)->ReleaseStringUTFChars(env, backend, requested_backend);
    return error[0] ? (*env)->NewStringUTF(env, error) : NULL;
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeReply(JNIEnv *env, jobject self, jlong id, jbyteArray bytes) {
    (void)self;
    if (!holder || !id) return;
    jsize count = bytes ? (*env)->GetArrayLength(env, bytes) : 0;
    jbyte *data = bytes ? (*env)->GetByteArrayElements(env, bytes, NULL) : NULL;
    ((void (*)(void*, const void*, size_t, size_t))api[0x78/8])(holder, data, count, (size_t)id);
    if (data) (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeSend(JNIEnv *env, jobject self, jstring name, jbyteArray bytes) {
    if(!is_current_activity(env,self))return;
    const char *channel = (*env)->GetStringUTFChars(env, name, NULL);
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    dispatch(channel, data, length);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, channel);
}
static void detach_surface(void) {
    if(!window)return;
    ((void (*)(void*))api[0xc0/8])(holder);
    ANativeWindow_release(window); // Release only the host's independent reference.
    window=NULL;
    LOG("Surface detached");
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeAttach(JNIEnv *env,jobject self) {
    if(holder&&bind_activity(env,self))LOG("Activity resumed and attached to existing engine");
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeDetach(JNIEnv *env,jobject self) {
    if(!is_current_activity(env,self))return;
    if(holder)detach_surface();
    pthread_mutex_lock(&activity_lock);
    jobject previous=activity;activity=NULL;
    pthread_mutex_unlock(&activity_lock);
    if(previous)(*env)->DeleteGlobalRef(env,previous);
    LOG("Activity detached; engine and assets retained");
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeSurface(JNIEnv *env, jobject self, jobject surface, jint width, jint height, jdouble density) {
    if (!holder||!is_current_activity(env,self)) return;
    if (!surface) {
        detach_surface();
        return;
    }
    ANativeWindow *next=ANativeWindow_fromSurface(env,surface);
    if(!next){LOG("Surface has no native window");return;}
    if(next!=window){
        detach_surface();window=next;
        // hyper_SurfaceCreated adopts (does not acquire) its ANativeWindow.
        // The engine and this host must each own a separate reference.
        ANativeWindow_acquire(window);
        ((void (*)(void*,void*))api[0xa8/8])(holder,window);
        LOG("Surface attached window=%p host+engine references",window);
    }else ANativeWindow_release(next);
    memset(viewport,0,sizeof(viewport));
    viewport[0]=density; viewport[1]=width; viewport[2]=height;
    // The original host falsely fixed this at 60. Report 120 to remove its
    // compatibility ceiling; never synthesize vsync or alter frame timestamps.
    ((void (*)(void*,double,double,double,double))api[0xc8/8])(holder,120.0,width,height,density);
    ((void (*)(void*,void*))api[0xa0/8])(holder,viewport);
    ((void (*)(void*,int,int))api[0xb0/8])(holder,width,height);
    LOG("Surface %dx%d @%f engine report=120 Hz actual display=%.3f Hz",width,height,density,
        atomic_load(&display_refresh_millihz)/1000.0);
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeTouch(JNIEnv *env, jobject self, jobject buffer, jint length) {
    if(!holder || !is_current_activity(env,self) || length<=0 || length%312) return;
    void *bytes=(*env)->GetDirectBufferAddress(env,buffer);
    if(bytes)((void (*)(void*, const void*, size_t))api[0x68/8])(holder,bytes,(size_t)length);
}
JNIEXPORT void JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeInsets(JNIEnv *env,jobject self,
        jint left,jint top,jint right,jint bottom,jint ime_bottom,
        jint gesture_left,jint gesture_top,jint gesture_right,jint gesture_bottom,jdouble touch_slop) {
    if(!is_current_activity(env,self))return;
    // HyperViewportMetrics contains padding/insets/gesture distances in physical
    // pixels; hyper_SetViewportMetrics inserts the four tight view constraints.
    viewport[3]=top;viewport[4]=right;viewport[5]=bottom;viewport[6]=left;
    viewport[7]=0;viewport[8]=0;viewport[9]=ime_bottom;viewport[10]=0;
    viewport[11]=gesture_top;viewport[12]=gesture_right;viewport[13]=gesture_bottom;viewport[14]=gesture_left;
    viewport[15]=touch_slop;
    if(holder&&viewport[1]>0&&viewport[2]>0)
        ((void (*)(void*,void*))api[0xa0/8])(holder,viewport);
    LOG("Insets left=%d top=%d right=%d bottom=%d ime=%d gestures=[%d,%d,%d,%d] slop=%g",
        left,top,right,bottom,ime_bottom,gesture_left,gesture_top,gesture_right,gesture_bottom,touch_slop);
}
