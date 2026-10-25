/* Observe the packaged renderer without changing its drawing or animation.
 * Included after host.c's JNI/activity helpers. All offsets are ABI-checked. */
static void (*startup_temperature_draw_original)(void *,void *,void *,int);
static void (*startup_renderer_draw_original)(void *);
static uintptr_t startup_temperature_vtable;
static _Atomic int startup_temperature_drawn, startup_weather_notified;
static int startup_observer_installed;

static void startup_temperature_draw(void *painter,void *global,void *uniform,int mode) {
    startup_temperature_draw_original(painter,global,uniform,mode);
    if(atomic_load(&startup_weather_notified))return;
    // Original draw requires the texture at +0x1b8. TemperatureUniform::setAlpha
    // writes +0xf4; validate the dynamic type before reading its private field.
    // No frame is claimed for an empty texture or a fully transparent glyph.
    if(painter&&uniform&&*(uintptr_t *)uniform==startup_temperature_vtable
            &&*(uintptr_t *)((char *)painter+0x1b8)
            &&*(float *)((char *)uniform+0xf4)>0.0f)
        atomic_store(&startup_temperature_drawn,1);
}
static void startup_renderer_draw(void *renderer) {
    startup_renderer_draw_original(renderer);
    if(!atomic_load(&startup_temperature_drawn)||atomic_load(&startup_weather_notified))return;
    // Notify only after the enclosing original scene has finished drawing.
    int attached;JNIEnv *env=thread_env(&attached);
    if(env){
        jobject target=current_activity(env,NULL,0);
        if(target){
            jclass cls=(*env)->GetObjectClass(env,target);
            jmethodID method=(*env)->GetMethodID(env,cls,"onWeatherFrameReady","()V");
            if(method&&!atomic_exchange(&startup_weather_notified,1)){
                LOG("STARTUP original weather frame includes visible temperature");
                (*env)->CallVoidMethod(env,target,method);
            }
            (*env)->DeleteLocalRef(env,cls);(*env)->DeleteLocalRef(env,target);
        }
        if((*env)->ExceptionCheck(env)){(*env)->ExceptionDescribe(env);(*env)->ExceptionClear(env);}
    }
    if(attached&&env)(*vm)->DetachCurrentThread(vm);
}
static int bind_startup_frame_observer(void *library,uintptr_t base) {
    if(startup_observer_installed)return 1;
    uintptr_t *temperature=(uintptr_t *)(base+0x13b648);
    uintptr_t *renderer=(uintptr_t *)(base+0x13aec8);
    uintptr_t vtable=(uintptr_t)dlsym(library,"_ZTV18TemperatureUniform");
    if(*temperature!=base+0xe777c||*renderer!=base+0x9cd90
            ||vtable!=base+0x135b28
            ||*(uint32_t *)(base+0xe77c4)!=0xf940de68
            ||*(uint32_t *)(base+0xdd568)!=0xbd00f400
            ||*(uint32_t *)(base+0xe79a4)!=0x94010e3f){
        LOG("STARTUP renderer observation ABI unavailable");return 0;
    }
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uintptr_t start=(uintptr_t)renderer&~(page-1);
    size_t size=(((uintptr_t)temperature+sizeof(uintptr_t)+page-1)&~(page-1))-start;
    if(mprotect((void *)start,size,PROT_READ|PROT_WRITE))return 0;
    startup_temperature_draw_original=(void (*)(void *,void *,void *,int))*temperature;
    startup_renderer_draw_original=(void (*)(void *))*renderer;
    startup_temperature_vtable=vtable+2*sizeof(uintptr_t);
    *temperature=(uintptr_t)startup_temperature_draw;
    *renderer=(uintptr_t)startup_renderer_draw;
    if(mprotect((void *)start,size,PROT_READ))LOG("STARTUP cannot restore import page protection");
    startup_observer_installed=1;
    LOG("STARTUP observing original temperature draw and enclosing frame");return 1;
}
JNIEXPORT jboolean JNICALL Java_dev_weather_oneplus_WeatherActivity_nativeHasStartupObserver(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;return startup_observer_installed?JNI_TRUE:JNI_FALSE;
}
