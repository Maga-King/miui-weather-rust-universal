#include <android/log.h>
#include <stdlib.h>
#include "xdl.h"
static int resolved_count;
int weather_shim_resolved_count(void) { return resolved_count; }
__attribute__((noreturn)) static void weather_guard_0(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN31RecyclingClippingPixelAllocator15copyIfNecessaryEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_0 = (void *)weather_guard_0;
__attribute__((noreturn)) static void weather_guard_1(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN31RecyclingClippingPixelAllocatorC1EPN7android6BitmapEbNSt3__18optionalI6SkRectEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_1 = (void *)weather_guard_1;
__attribute__((noreturn)) static void weather_guard_2(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN6SkData12MakeWithProcEPKvmPFvS1_PvES2_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_2 = (void *)weather_guard_2;
__attribute__((noreturn)) static void weather_guard_3(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android10uirenderer22HardwareBitmapUploader22hasFP16SupportAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_3 = (void *)weather_guard_3;
__attribute__((noreturn)) static void weather_guard_4(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android10uirenderer36BufferDescriptionToImageInfoAsPublicERK20AHardwareBuffer_Desc5sk_spI12SkColorSpaceE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_4 = (void *)weather_guard_4;
__attribute__((noreturn)) static void weather_guard_5(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android10uirenderer7Gainmap31allocateHardwareGainmapAsPublicERKNS_2spIS1_EE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_5 = (void *)weather_guard_5;
__attribute__((noreturn)) static void weather_guard_6(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android12MinikinUtils17getBoundsAsPublicEPKNS_5PaintEN7minikin4BidiEPKNS_8TypefaceEPKtmPNS4_11MinikinRectE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_6 = (void *)weather_guard_6;
__attribute__((noreturn)) static void weather_guard_7(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android12MinikinUtils19measureTextAsPublicEPKNS_5PaintEN7minikin4BidiEPKNS_8TypefaceEPKtmmmPfPNS4_11MinikinRectEPjt"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_7 = (void *)weather_guard_7;
__attribute__((noreturn)) static void weather_guard_8(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android12MinikinUtils27prepareMinikinPaintAsPublicEPKNS_5PaintEPKNS_8TypefaceE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_8 = (void *)weather_guard_8;
__attribute__((noreturn)) static void weather_guard_9(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android13MiSurfaceStub10ScenarioIdE"); abort(); }
unsigned long weather_scenario_id __asm__("_ZN7android13MiSurfaceStub10ScenarioIdE") = 0;
__attribute__((noreturn)) static void weather_guard_10(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android14SurfaceControl22setSFAnimationCallbackERKNS_2wpINS_19SFAnimationListener19SFAnimationCallbackEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_10 = (void *)weather_guard_10;
__attribute__((noreturn)) static void weather_guard_11(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android14SurfaceControl25createSFAnimationListenerEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_11 = (void *)weather_guard_11;
__attribute__((noreturn)) static void weather_guard_12(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android15MinikinFontSkia14populateSkFontEP6SkFontPKN7minikin11MinikinFontENS3_10FontFakeryE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_12 = (void *)weather_guard_12;
__attribute__((noreturn)) static void weather_guard_13(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android15MinikinFontSkiaC1E5sk_spI10SkTypefaceEiPKvmNSt3__117basic_string_viewIcNS6_11char_traitsIcEEEEiRKN7minikin18SortedPackedVectorINSB_13FontVariationELm2EtEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_13 = (void *)weather_guard_13;
__attribute__((noreturn)) static void weather_guard_14(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android15VelocityTrackerC1ENS0_8StrategyEb"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_14 = (void *)weather_guard_14;
__attribute__((noreturn)) static void weather_guard_15(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction12setHdrDimmerERKNS_2spINS_14SurfaceControlEEEbRKNSt3__16vectorINS8_IfNS7_9allocatorIfEEEENS9_ISB_EEEESF_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_15 = (void *)weather_guard_15;
__attribute__((noreturn)) static void weather_guard_16(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction15setCornerRadiusERKNS_2spINS_14SurfaceControlEEEff"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_16 = (void *)weather_guard_16;
__attribute__((noreturn)) static void weather_guard_17(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction15stopSFAnimationERKNS_2spINS_14SurfaceControlEEEi"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_17 = (void *)weather_guard_17;
__attribute__((noreturn)) static void weather_guard_18(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction16setMiCornerRadiiERKNS_2spINS_14SurfaceControlEEENSt3__16vectorIfNS7_9allocatorIfEEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_18 = (void *)weather_guard_18;
__attribute__((noreturn)) static void weather_guard_19(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction16setSurfaceStrokeERKNS_2spINS_14SurfaceControlEEERKNS_7details5TVec3INS_4halfEEEff"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_19 = (void *)weather_guard_19;
__attribute__((noreturn)) static void weather_guard_20(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction16startSFAnimationERKNS_2spINS_14SurfaceControlEEERKNS2_INS_3gui17SfAnimationParamsEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_20 = (void *)weather_guard_20;
__attribute__((noreturn)) static void weather_guard_21(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction17adjustSFAnimationERKNS_2spINS_14SurfaceControlEEERKNS2_INS_3gui17SfAnimationParamsEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_21 = (void *)weather_guard_21;
__attribute__((noreturn)) static void weather_guard_22(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction17setHdrDimmerRatioERKNS_2spINS_14SurfaceControlEEEf"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_22 = (void *)weather_guard_22;
__attribute__((noreturn)) static void weather_guard_23(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction17setSelfBlurRadiusERKNS_2spINS_14SurfaceControlEEEi"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_23 = (void *)weather_guard_23;
__attribute__((noreturn)) static void weather_guard_24(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction22setSFAnimationListenerERKNS_2spINS_14SurfaceControlEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_24 = (void *)weather_guard_24;
__attribute__((noreturn)) static void weather_guard_25(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction23setUseParentSelfBlurExcERKNS_2spINS_14SurfaceControlEEEbRKNSt3__16vectorINS_7String8ENS7_9allocatorIS9_EEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_25 = (void *)weather_guard_25;
__attribute__((noreturn)) static void weather_guard_26(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android21SurfaceComposerClient11Transaction29setAnimationParamsFetchStatusERKNS_2spINS_14SurfaceControlEEENSt3__16vectorIiNS7_9allocatorIiEEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_26 = (void *)weather_guard_26;
__attribute__((noreturn)) static void weather_guard_27(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android3gui17SfAnimationParams6createENSt3__16vectorIfNS2_9allocatorIfEEEENS3_IiNS4_IiEEEES8_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_27 = (void *)weather_guard_27;
__attribute__((noreturn)) static void weather_guard_28(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android5Paint20setAntiAliasAsPublicEb"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_28 = (void *)weather_guard_28;
__attribute__((noreturn)) static void weather_guard_29(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android5Paint20setJavaFlagsAsPublicEj"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_29 = (void *)weather_guard_29;
__attribute__((noreturn)) static void weather_guard_30(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android5PaintC1ERKS0_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_30 = (void *)weather_guard_30;
__attribute__((noreturn)) static void weather_guard_31(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android5PaintC1Ev"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_31 = (void *)weather_guard_31;
__attribute__((noreturn)) static void weather_guard_32(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android5PaintD1Ev"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_32 = (void *)weather_guard_32;
__attribute__((noreturn)) static void weather_guard_33(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap16compressAsPublicENS0_18JavaCompressFormatEiP9SkWStream"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_33 = (void *)weather_guard_33;
__attribute__((noreturn)) static void weather_guard_34(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap18createFromAsPublicERK11SkImageInfomiPvmbl"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_34 = (void *)weather_guard_34;
__attribute__((noreturn)) static void weather_guard_35(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap18setGainmapAsPublicEONS_2spINS_10uirenderer7GainmapEEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_35 = (void *)weather_guard_35;
__attribute__((noreturn)) static void weather_guard_36(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap19getSkBitmapAsPublicEP8SkBitmap"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_36 = (void *)weather_guard_36;
__attribute__((noreturn)) static void weather_guard_37(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap19reconfigureAsPublicERK11SkImageInfom"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_37 = (void *)weather_guard_37;
__attribute__((noreturn)) static void weather_guard_38(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap20setAlphaTypeAsPublicE11SkAlphaType"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_38 = (void *)weather_guard_38;
__attribute__((noreturn)) static void weather_guard_39(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap21setColorSpaceAsPublicE5sk_spI12SkColorSpaceE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_39 = (void *)weather_guard_39;
__attribute__((noreturn)) static void weather_guard_40(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap26allocateHeapBitmapAsPublicEP8SkBitmap"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_40 = (void *)weather_guard_40;
__attribute__((noreturn)) static void weather_guard_41(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap26allocateHeapBitmapAsPublicEmRK11SkImageInfom"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_41 = (void *)weather_guard_41;
__attribute__((noreturn)) static void weather_guard_42(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap28setHasHardwareMipMapAsPublicEb"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_42 = (void *)weather_guard_42;
__attribute__((noreturn)) static void weather_guard_43(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap29computeAllocationSizeAsPublicEmiPm"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_43 = (void *)weather_guard_43;
__attribute__((noreturn)) static void weather_guard_44(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap30allocateHardwareBitmapAsPublicERK8SkBitmap"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_44 = (void *)weather_guard_44;
__attribute__((noreturn)) static void weather_guard_45(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Bitmap32createFromHardwareBufferAsPublicEP15AHardwareBufferRK11SkImageInfoRK20AHardwareBuffer_DescNS_13BitmapPaletteE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_45 = (void *)weather_guard_45;
__attribute__((noreturn)) static void weather_guard_46(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Canvas13create_canvasERK8SkBitmap"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_46 = (void *)weather_guard_46;
__attribute__((noreturn)) static void weather_guard_47(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Canvas14drawTextOnPathEPKtiN7minikin4BidiERK6SkPathffRKNS_5PaintEPKNS_8TypefaceE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_47 = (void *)weather_guard_47;
__attribute__((noreturn)) static void weather_guard_48(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android6Canvas8drawTextEPKtiiiiiffN7minikin4BidiERKNS_5PaintEPKNS_8TypefaceEPNS3_12MeasuredTextE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_48 = (void *)weather_guard_48;
__attribute__((noreturn)) static void weather_guard_49(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android8Typeface10setDefaultEPKS0_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_49 = (void *)weather_guard_49;
__attribute__((noreturn)) static void weather_guard_50(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android8Typeface14resolveDefaultEPKS0_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_50 = (void *)weather_guard_50;
__attribute__((noreturn)) static void weather_guard_51(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android8Typeface18createFromFamiliesEONSt3__16vectorINS1_10shared_ptrIN7minikin10FontFamilyEEENS1_9allocatorIS6_EEEEiiPKS0_"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_51 = (void *)weather_guard_51;
__attribute__((noreturn)) static void weather_guard_52(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN7android8Typeface31createFromTypefaceWithVariationEPS0_RKN7minikin18SortedPackedVectorINS2_13FontVariationELm2EtEE"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_52 = (void *)weather_guard_52;
__attribute__((noreturn)) static void weather_guard_53(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN8SkPixmapC1ERK11SkImageInfoPKvm"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_53 = (void *)weather_guard_53;
__attribute__((noreturn)) static void weather_guard_54(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZN8SkPixmapC1Ev"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_54 = (void *)weather_guard_54;
__attribute__((noreturn)) static void weather_guard_55(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK11SkImageInfo12isCompressedEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_55 = (void *)weather_guard_55;
__attribute__((noreturn)) static void weather_guard_56(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK11SkImageInfo16makeWHCompressedEii"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_56 = (void *)weather_guard_56;
__attribute__((noreturn)) static void weather_guard_57(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK7android5Paint20getJavaFlagsAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_57 = (void *)weather_guard_57;
__attribute__((noreturn)) static void weather_guard_58(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK7android6Bitmap15gainmapAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_58 = (void *)weather_guard_58;
__attribute__((noreturn)) static void weather_guard_59(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK7android6Bitmap19getAshmemFdAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_59 = (void *)weather_guard_59;
__attribute__((noreturn)) static void weather_guard_60(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK7android6Bitmap25hasHardwareMipMapAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_60 = (void *)weather_guard_60;
__attribute__((noreturn)) static void weather_guard_61(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZNK7android6Bitmap30getAllocationByteCountAsPublicEv"); abort(); }
__attribute__((visibility("hidden"))) void *weather_slot_61 = (void *)weather_guard_61;
__attribute__((noreturn)) static void weather_guard_62(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZTVN7android3gui14CallAnimParamsE"); abort(); }
void *weather_vtable_62[8] __asm__("_ZTVN7android3gui14CallAnimParamsE") = {0,0,weather_guard_62,weather_guard_62,weather_guard_62,weather_guard_62,weather_guard_62,weather_guard_62};
__attribute__((noreturn)) static void weather_guard_63(void) { __android_log_assert("unsupported native API", "WeatherPortProbe", "UNIMPLEMENTED: _ZTVN8SkBitmap13HeapAllocatorE"); abort(); }
void *weather_vtable_63[8] __asm__("_ZTVN8SkBitmap13HeapAllocatorE") = {0,0,weather_guard_63,weather_guard_63,weather_guard_63,weather_guard_63,weather_guard_63,weather_guard_63};
__attribute__((constructor)) static void initialize_bridge(void) {
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN31RecyclingClippingPixelAllocator15copyIfNecessaryEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN31RecyclingClippingPixelAllocator15copyIfNecessaryEv", 0);
    if (address) { weather_slot_0 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN31RecyclingClippingPixelAllocator15copyIfNecessaryEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN31RecyclingClippingPixelAllocatorC1EPN7android6BitmapEbNSt3__18optionalI6SkRectEE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN31RecyclingClippingPixelAllocatorC1EPN7android6BitmapEbNSt3__18optionalI6SkRectEE", 0);
    if (address) { weather_slot_1 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN31RecyclingClippingPixelAllocatorC1EPN7android6BitmapEbNSt3__18optionalI6SkRectEE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android10uirenderer22HardwareBitmapUploader14hasFP16SupportEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android10uirenderer22HardwareBitmapUploader14hasFP16SupportEv", 0);
    if (address) { weather_slot_3 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android10uirenderer22HardwareBitmapUploader14hasFP16SupportEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android10uirenderer7Gainmap23allocateHardwareGainmapERKNS_2spIS1_EE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android10uirenderer7Gainmap23allocateHardwareGainmapERKNS_2spIS1_EE", 0);
    if (address) { weather_slot_5 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android10uirenderer7Gainmap23allocateHardwareGainmapERKNS_2spIS1_EE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android12MinikinUtils9getBoundsEPKNS_5PaintEN7minikin4BidiEPKNS_8TypefaceEPKtmPNS4_11MinikinRectE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android12MinikinUtils9getBoundsEPKNS_5PaintEN7minikin4BidiEPKNS_8TypefaceEPKtmPNS4_11MinikinRectE", 0);
    if (address) { weather_slot_6 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android12MinikinUtils9getBoundsEPKNS_5PaintEN7minikin4BidiEPKNS_8TypefaceEPKtmPNS4_11MinikinRectE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android12MinikinUtils19prepareMinikinPaintEPKNS_5PaintEPKNS_8TypefaceE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android12MinikinUtils19prepareMinikinPaintEPKNS_5PaintEPKNS_8TypefaceE", 0);
    if (address) { weather_slot_8 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android12MinikinUtils19prepareMinikinPaintEPKNS_5PaintEPKNS_8TypefaceE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android15MinikinFontSkia14populateSkFontEP6SkFontPKN7minikin11MinikinFontENS3_10FontFakeryE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android15MinikinFontSkia14populateSkFontEP6SkFontPKN7minikin11MinikinFontENS3_10FontFakeryE", 0);
    if (address) { weather_slot_12 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android15MinikinFontSkia14populateSkFontEP6SkFontPKN7minikin11MinikinFontENS3_10FontFakeryE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android15MinikinFontSkiaC1E5sk_spI10SkTypefaceEiPKvmNSt3__117basic_string_viewIcNS6_11char_traitsIcEEEEiRKN7minikin18SortedPackedVectorINSB_13FontVariationELm2EtEE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android15MinikinFontSkiaC1E5sk_spI10SkTypefaceEiPKvmNSt3__117basic_string_viewIcNS6_11char_traitsIcEEEEiRKN7minikin18SortedPackedVectorINSB_13FontVariationELm2EtEE", 0);
    if (address) { weather_slot_13 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android15MinikinFontSkiaC1E5sk_spI10SkTypefaceEiPKvmNSt3__117basic_string_viewIcNS6_11char_traitsIcEEEEiRKN7minikin18SortedPackedVectorINSB_13FontVariationELm2EtEE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libinput.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android15VelocityTrackerC1ENS0_8StrategyE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android15VelocityTrackerC1ENS0_8StrategyE", 0);
    if (address) { weather_slot_14 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android15VelocityTrackerC1ENS0_8StrategyE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android5Paint12setJavaFlagsEj", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android5Paint12setJavaFlagsEj", 0);
    if (address) { weather_slot_29 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android5Paint12setJavaFlagsEj");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android5PaintC1ERKS0_", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android5PaintC1ERKS0_", 0);
    if (address) { weather_slot_30 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android5PaintC1ERKS0_");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android5PaintC1Ev", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android5PaintC1Ev", 0);
    if (address) { weather_slot_31 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android5PaintC1Ev");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android5PaintD1Ev", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android5PaintD1Ev", 0);
    if (address) { weather_slot_32 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android5PaintD1Ev");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap8compressENS0_18JavaCompressFormatEiP9SkWStream", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap8compressENS0_18JavaCompressFormatEiP9SkWStream", 0);
    if (address) { weather_slot_33 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap8compressENS0_18JavaCompressFormatEiP9SkWStream");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap10setGainmapEONS_2spINS_10uirenderer7GainmapEEE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap10setGainmapEONS_2spINS_10uirenderer7GainmapEEE", 0);
    if (address) { weather_slot_35 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap10setGainmapEONS_2spINS_10uirenderer7GainmapEEE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap11getSkBitmapEP8SkBitmap", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap11getSkBitmapEP8SkBitmap", 0);
    if (address) { weather_slot_36 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap11getSkBitmapEP8SkBitmap");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap11reconfigureERK11SkImageInfom", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap11reconfigureERK11SkImageInfom", 0);
    if (address) { weather_slot_37 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap11reconfigureERK11SkImageInfom");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap12setAlphaTypeE11SkAlphaType", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap12setAlphaTypeE11SkAlphaType", 0);
    if (address) { weather_slot_38 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap12setAlphaTypeE11SkAlphaType");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap13setColorSpaceE5sk_spI12SkColorSpaceE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap13setColorSpaceE5sk_spI12SkColorSpaceE", 0);
    if (address) { weather_slot_39 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap13setColorSpaceE5sk_spI12SkColorSpaceE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap18allocateHeapBitmapEP8SkBitmap", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap18allocateHeapBitmapEP8SkBitmap", 0);
    if (address) { weather_slot_40 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap18allocateHeapBitmapEP8SkBitmap");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap18allocateHeapBitmapEmRK11SkImageInfom", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap18allocateHeapBitmapEmRK11SkImageInfom", 0);
    if (address) { weather_slot_41 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap18allocateHeapBitmapEmRK11SkImageInfom");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap20setHasHardwareMipMapEb", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap20setHasHardwareMipMapEb", 0);
    if (address) { weather_slot_42 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap20setHasHardwareMipMapEb");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap21computeAllocationSizeEmiPm", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap21computeAllocationSizeEmiPm", 0);
    if (address) { weather_slot_43 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap21computeAllocationSizeEmiPm");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Bitmap22allocateHardwareBitmapERK8SkBitmap", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Bitmap22allocateHardwareBitmapERK8SkBitmap", 0);
    if (address) { weather_slot_44 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Bitmap22allocateHardwareBitmapERK8SkBitmap");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Canvas13create_canvasERK8SkBitmap", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Canvas13create_canvasERK8SkBitmap", 0);
    if (address) { weather_slot_46 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Canvas13create_canvasERK8SkBitmap");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Canvas14drawTextOnPathEPKtiN7minikin4BidiERK6SkPathffRKNS_5PaintEPKNS_8TypefaceE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Canvas14drawTextOnPathEPKtiN7minikin4BidiERK6SkPathffRKNS_5PaintEPKNS_8TypefaceE", 0);
    if (address) { weather_slot_47 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Canvas14drawTextOnPathEPKtiN7minikin4BidiERK6SkPathffRKNS_5PaintEPKNS_8TypefaceE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android6Canvas8drawTextEPKtiiiiiffN7minikin4BidiERKNS_5PaintEPKNS_8TypefaceEPNS3_12MeasuredTextE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android6Canvas8drawTextEPKtiiiiiffN7minikin4BidiERKNS_5PaintEPKNS_8TypefaceEPNS3_12MeasuredTextE", 0);
    if (address) { weather_slot_48 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android6Canvas8drawTextEPKtiiiiiffN7minikin4BidiERKNS_5PaintEPKNS_8TypefaceEPNS3_12MeasuredTextE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android8Typeface18createFromFamiliesEONSt3__16vectorINS1_10shared_ptrIN7minikin10FontFamilyEEENS1_9allocatorIS6_EEEEiiPKS0_", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android8Typeface18createFromFamiliesEONSt3__16vectorINS1_10shared_ptrIN7minikin10FontFamilyEEENS1_9allocatorIS6_EEEEiiPKS0_", 0);
    if (address) { weather_slot_51 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android8Typeface18createFromFamiliesEONSt3__16vectorINS1_10shared_ptrIN7minikin10FontFamilyEEENS1_9allocatorIS6_EEEEiiPKS0_");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZN7android8Typeface31createFromTypefaceWithVariationEPS0_RKN7minikin18SortedPackedVectorINS2_13FontVariationELm2EtEE", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZN7android8Typeface31createFromTypefaceWithVariationEPS0_RKN7minikin18SortedPackedVectorINS2_13FontVariationELm2EtEE", 0);
    if (address) { weather_slot_52 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZN7android8Typeface31createFromTypefaceWithVariationEPS0_RKN7minikin18SortedPackedVectorINS2_13FontVariationELm2EtEE");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZNK7android5Paint12getJavaFlagsEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZNK7android5Paint12getJavaFlagsEv", 0);
    if (address) { weather_slot_57 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZNK7android5Paint12getJavaFlagsEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZNK7android6Bitmap7gainmapEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZNK7android6Bitmap7gainmapEv", 0);
    if (address) { weather_slot_58 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZNK7android6Bitmap7gainmapEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZNK7android6Bitmap11getAshmemFdEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZNK7android6Bitmap11getAshmemFdEv", 0);
    if (address) { weather_slot_59 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZNK7android6Bitmap11getAshmemFdEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZNK7android6Bitmap17hasHardwareMipMapEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZNK7android6Bitmap17hasHardwareMipMapEv", 0);
    if (address) { weather_slot_60 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZNK7android6Bitmap17hasHardwareMipMapEv");
    if (library) xdl_close(library);
    }
    {
    void *library = xdl_open("libhwui.so", XDL_TRY_FORCE_LOAD);
    void *address = library ? xdl_sym(library, "_ZNK7android6Bitmap22getAllocationByteCountEv", 0) : 0;
    if (!address && library) address = xdl_dsym(library, "_ZNK7android6Bitmap22getAllocationByteCountEv", 0);
    if (address) { weather_slot_61 = address; resolved_count++; }
    else __android_log_print(ANDROID_LOG_ERROR, "WeatherPortProbe", "Could not resolve: _ZNK7android6Bitmap22getAllocationByteCountEv");
    if (library) xdl_close(library);
    }
    __android_log_print(ANDROID_LOG_INFO, "WeatherPortProbe", "Compatibility candidates resolved: %d; remaining APIs have diagnostic traps", resolved_count);
}
