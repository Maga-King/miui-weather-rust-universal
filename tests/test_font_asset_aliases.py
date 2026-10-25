"""Compile the production font-open hook and check its paths on ARM64."""
from pathlib import Path
import argparse
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    source = (ROOT / 'port/host.c').read_text(encoding='utf-8')
    hook = source[source.index('static AAsset *text_font_asset_open('):source.index('static void locate_engine(')]
    prelude = r'''#include <stdio.h>
#include <string.h>
#include <stdlib.h>
typedef void AAssetManager;
typedef char AAsset;
static int seen_mode;
static AAssetManager *seen_manager;
static AAsset *AAssetManager_open(AAssetManager *manager,const char *name,int mode) {
    seen_mode=mode;seen_manager=manager;return (AAsset *)name;
}
'''
    harness = r'''
static int checks;
static void check(const char *name,const char *expected) {
    char manager;
    const char *got=text_font_asset_open(&manager,name,3);
    if(!got||strcmp(got,expected)||seen_mode!=3||seen_manager!=&manager) {
        fprintf(stderr,"Bad font alias: %s -> %s\n",name,got?got:"NULL");exit(1);
    }
    ++checks;
}
int main(void) {
    const char *families[]={"MiSans","MiSansTibetan"};
    const char *prefixes[]={"","flutter_assets/"};
    const int weights[]={250,305,330,380,450,520};
    char name[256],target[256];
    for(int f=0;f<2;f++) {
        snprintf(target,sizeof(target),"fonts/%sVF.ttf",families[f]);
        for(int p=0;p<2;p++) {
            snprintf(name,sizeof(name),"%sfonts/Weather%sVF.ttf",prefixes[p],families[f]);check(name,target);
            for(int w=0;w<6;w++) {
                snprintf(name,sizeof(name),"%sfonts/%s-%d.ttf",prefixes[p],families[f],weights[w]);check(name,target);
            }
        }
    }
    const char *untouched[]={"fonts/MiSansVF.ttf","fonts/MiSansTibetanVF.ttf",
        "flutter_assets/packages/hyperos/fonts/HyperOSSymbolsVF.ttf",
        "other/fonts/WeatherMiSansVF.ttf","flutter_assets/fonts/MiSans-999.ttf",
        "flutter_assets/fonts/WeatherMiSansVF.ttf.backup","flutter_assets/FontManifest.json"};
    for(int i=0;i<7;i++)check(untouched[i],untouched[i]);
    if(text_font_asset_open(NULL,NULL,3)!=NULL)return 1;
    printf("PASS: %d production font alias checks plus NULL input\n",checks);
    return 0;
}
'''
    work = ROOT / '.build/font-alias-check'
    work.mkdir(parents=True, exist_ok=True)
    cfile, binary = work / 'check.c', work / 'check'
    cfile.write_text(prelude + hook + harness, encoding='utf-8')
    tc = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
    subprocess.run([str(tc / 'bin/clang.exe'), '--target=aarch64-linux-android28',
                    '--sysroot=' + str(tc / 'sysroot'), str(cfile), '-o', str(binary)], check=True)
    remote = '/data/local/tmp/weather-font-alias-check'
    adb = ['adb', '-s', args.serial]
    try:
        subprocess.run(adb + ['push', str(binary), remote], check=True, capture_output=True)
        subprocess.run(adb + ['shell', 'chmod', '700', remote], check=True)
        subprocess.run(adb + ['shell', remote], check=True)
    finally:
        subprocess.run(adb + ['shell', 'rm', '-f', remote], check=False, capture_output=True)

if __name__ == '__main__':
    main()
