// Run with Android's actual libandroid and Looper, also exercise missing API sets.
#include <assert.h>
#include <android/looper.h>
#include <dlfcn.h>
#include <stdio.h>
#include <time.h>
#include "../port/choreographer_compat.h"

static WeatherAndroidProc table[7],before[7];
static AChoreographer_frameCallback64 deferred;
static void *deferred_data;
static unsigned rate=60000,received;
static int64_t expected_time,last_time;
static AChoreographer *choreographer;
static unsigned actual_refresh(void){return rate;}
static int64_t now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (int64_t)t.tv_sec*1000000000+t.tv_nsec;}
static void setup(void *frame64){
    for(unsigned i=0;i<7;i++)table[i]=(WeatherAndroidProc){weather_choreographer_names[i],NULL};
    table[0].function=(void *)AChoreographer_getInstance;
    table[1].function=frame64;
}
static void fake64(AChoreographer *c,AChoreographer_frameCallback64 callback,void *data){
    (void)c;assert(!deferred);deferred=callback;deferred_data=data;
}
static void fake_legacy(AChoreographer *c,AChoreographer_frameCallback callback,void *data){
    fake64(c,(AChoreographer_frameCallback64)callback,data);
}
static void receive(const AChoreographerFrameCallbackData *data,void *context){
    assert(context==&received);
    size_t index=((size_t (*)(const AChoreographerFrameCallbackData *))table[3].function)(data);
    int64_t id=((int64_t (*)(const AChoreographerFrameCallbackData *,size_t))table[4].function)(data,index);
    int64_t deadline=((int64_t (*)(const AChoreographerFrameCallbackData *,size_t))table[5].function)(data,index);
    int64_t time=((int64_t (*)(const AChoreographerFrameCallbackData *))table[6].function)(data);
    assert(index==0 && id==-1);
    unsigned actual=rate>=1000 && rate<=1000000 ? rate : 60000;
    assert(deadline-time==INT64_C(1000000000000)/actual);
    if(expected_time)assert(time==expected_time);
    else {assert(time>last_time && time<=now()+1000000 && now()-time<5000000000);last_time=time;}
    received++;
    if(!expected_time && received<20)
        ((void (*)(AChoreographer *,AChoreographer_vsyncCallback,void *))table[2].function)(choreographer,receive,&received);
}
static void request(void){((void (*)(AChoreographer *,AChoreographer_vsyncCallback,void *))table[2].function)(choreographer,receive,&received);}
static void run_real(void *frame64,WeatherPostFrameLegacy legacy,int sdk,int expected){
    setup(frame64);assert(weather_bind_choreographer(table,sdk,legacy,actual_refresh)==expected);
    expected_time=0;last_time=0;received=0;rate=120000;
    request();int64_t limit=now()+INT64_C(5000000000);
    while(received<20 && now()<limit)ALooper_pollOnce(100,NULL,NULL,NULL);
    assert(received==20);
}
int main(void){
    setup((void *)fake64);
    for(unsigned i=2;i<7;i++)table[i].function=(void *)fake64;
    memcpy(before,table,sizeof(table));assert(weather_bind_choreographer(table,37,NULL,actual_refresh)==1);
    assert(!memcmp(before,table,sizeof(table)));
    for(int sdk=28;sdk<=33;sdk++)for(unsigned mask=0;mask<32;mask++){
        setup((void *)fake64);
        for(unsigned i=2;i<7;i++)if(mask&(1u<<(i-2)))table[i].function=(void *)fake64;
        memcpy(before,table,sizeof(table));
        int result=weather_bind_choreographer(table,sdk,NULL,actual_refresh);
        if(mask==31){assert(result==1);assert(!memcmp(before,table,sizeof(table)));}
        else if(sdk==33){assert(!result);assert(!memcmp(before,table,sizeof(table)));}
        else assert(result==2);
    }
    setup(NULL);memcpy(before,table,sizeof(table));
    assert(!weather_bind_choreographer(table,28,NULL,actual_refresh));assert(!memcmp(before,table,sizeof(table)));
    table[2].name="wrong ABI";assert(!weather_bind_choreographer(table,28,fake_legacy,actual_refresh));
    const unsigned rates[]={0,60000,90000,120000,144000,1000001};
    for(unsigned legacy=0;legacy<2;legacy++){
        setup(legacy ? NULL : (void *)fake64);
        assert(weather_bind_choreographer(table,28,fake_legacy,actual_refresh)==(legacy?3:2));
        for(unsigned i=0;i<sizeof(rates)/sizeof(rates[0]);i++){
            rate=rates[i];received=0;expected_time=INT64_C(1234567890123);request();
            assert(!received && deferred);AChoreographer_frameCallback64 callback=deferred;deferred=NULL;
            callback(expected_time,deferred_data);assert(received==1);
        }
    }
    void *android=dlopen("libandroid.so",RTLD_NOW);assert(android);
    void *frame64=dlsym(android,"AChoreographer_postFrameCallback64");assert(frame64);
    WeatherPostFrameLegacy legacy=(WeatherPostFrameLegacy)dlsym(android,"AChoreographer_postFrameCallback");assert(legacy);
    ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);choreographer=AChoreographer_getInstance();assert(choreographer);
    run_real(frame64,legacy,32,2);
    run_real(NULL,legacy,28,3);
    puts("PASS: 192 API availability cases; no partial writes; real frame64 and legacy callbacks, 20 frames each; timestamp, lifetime, re-post and refresh changes");
    return 0;
}
