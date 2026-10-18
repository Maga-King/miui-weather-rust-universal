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
static void locate_engine(void *interface) {
    Dl_info engine={0};
    if(!dladdr(interface,&engine)||!engine.dli_fbase){LOG("RENDER missing engine base");return;}
    engine_base=(uintptr_t)engine.dli_fbase;
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
static ANativeWindow *window;
static double viewport[32];
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
    ((void (*)(void*,double,double,double,double))api[0xc8/8])(holder,60.0,width,height,density);
    ((void (*)(void*,void*))api[0xa0/8])(holder,viewport);
    ((void (*)(void*,int,int))api[0xb0/8])(holder,width,height);
    LOG("Surface %dx%d @%f",width,height,density);
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
