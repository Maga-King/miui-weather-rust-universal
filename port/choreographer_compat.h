// Adapter for this engine's API 33 frame-timeline contract on older Android.
// Bind before creating engine threads. Never mix real opaque timeline data with
// our accessors: replace the complete five-function group together.
#ifndef WEATHER_CHOREOGRAPHER_COMPAT_H
#define WEATHER_CHOREOGRAPHER_COMPAT_H
#include <android/choreographer.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { const char *name; void *function; } WeatherAndroidProc;
static const char *const weather_choreographer_names[] = {
    "AChoreographer_getInstance",
    "AChoreographer_postFrameCallback64",
    "AChoreographer_postVsyncCallback",
    "AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex",
    "AChoreographerFrameCallbackData_getFrameTimelineVsyncId",
    "AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos",
    "AChoreographerFrameCallbackData_getFrameTimeNanos"
};
typedef void (*WeatherPostFrame64)(AChoreographer *, AChoreographer_frameCallback64, void *);
typedef void (*WeatherPostFrameLegacy)(AChoreographer *, AChoreographer_frameCallback, void *);
static WeatherPostFrame64 weather_post_frame64;
static WeatherPostFrameLegacy weather_post_frame_legacy;
static unsigned (*weather_actual_refresh)(void);
typedef struct { int64_t frame_time, deadline; } WeatherFrameTimeline;
typedef struct { AChoreographer_vsyncCallback callback; void *data; } WeatherPendingFrame;

static void weather_legacy_frame64(AChoreographer *choreographer,
        AChoreographer_frameCallback64 callback, void *data) {
    // The application and its entire runtime are arm64-only. Android's old
    // callback uses long, which has exactly the int64_t ABI on this platform.
    _Static_assert(sizeof(long)==sizeof(int64_t), "arm64 callback ABI required");
    weather_post_frame_legacy(choreographer, (AChoreographer_frameCallback)callback, data);
}
static void weather_frame_callback(int64_t frame_time, void *data) {
    WeatherPendingFrame pending=*(WeatherPendingFrame *)data;
    free(data);
    unsigned rate=weather_actual_refresh ? weather_actual_refresh() : 0;
    if(rate<1000 || rate>1000000)rate=60000;
    int64_t interval=INT64_C(1000000000000)/rate;
    WeatherFrameTimeline timeline={frame_time,
        frame_time>INT64_MAX-interval ? INT64_MAX : frame_time+interval};
    // Same lifetime as the system's callback data: valid only inside callback.
    pending.callback((const AChoreographerFrameCallbackData *)&timeline, pending.data);
}
static void weather_post_vsync(AChoreographer *choreographer,
        AChoreographer_vsyncCallback callback, void *data) {
    WeatherPendingFrame *pending=malloc(sizeof(*pending));
    if(!pending)abort(); // Match the engine's allocation failure, never lose a frame silently.
    *pending=(WeatherPendingFrame){callback,data};
    weather_post_frame64(choreographer,weather_frame_callback,pending);
}
static size_t weather_timeline_index(const AChoreographerFrameCallbackData *data) {
    (void)data;return 0;
}
static int64_t weather_timeline_id(const AChoreographerFrameCallbackData *data,size_t index) {
    (void)data;(void)index;
    // Older Android has no SurfaceFlinger frame-timeline token. -1 is invalid;
    // do not invent a token that might refer to an unrelated system frame.
    return -1;
}
static int64_t weather_timeline_deadline(const AChoreographerFrameCallbackData *data,size_t index) {
    (void)index;return ((const WeatherFrameTimeline *)data)->deadline;
}
static int64_t weather_timeline_time(const AChoreographerFrameCallbackData *data) {
    return ((const WeatherFrameTimeline *)data)->frame_time;
}
// Returns 1 for untouched API33+, 2 for old frame64, 3 for arm64 API28;
// 0 means unsupported ABI or no usable platform callback. No partial writes.
static int weather_bind_choreographer(WeatherAndroidProc *table,int sdk,
        WeatherPostFrameLegacy legacy,unsigned (*actual_refresh)(void)) {
    for(size_t i=0;i<7;i++)
        if(!table[i].name || strcmp(table[i].name,weather_choreographer_names[i]))return 0;
    int complete=1;
    for(size_t i=2;i<7;i++)if(!table[i].function)complete=0;
    if(complete)return 1;
    if(sdk>=33 || !table[0].function || (!table[1].function && !legacy))return 0;
    weather_actual_refresh=actual_refresh;
    weather_post_frame_legacy=legacy;
    weather_post_frame64=table[1].function ? (WeatherPostFrame64)table[1].function : weather_legacy_frame64;
    int result=table[1].function ? 2 : 3;
    if(!table[1].function)table[1].function=(void *)weather_legacy_frame64;
    table[2].function=(void *)weather_post_vsync;
    table[3].function=(void *)weather_timeline_index;
    table[4].function=(void *)weather_timeline_id;
    table[5].function=(void *)weather_timeline_deadline;
    table[6].function=(void *)weather_timeline_time;
    return result;
}
#endif
