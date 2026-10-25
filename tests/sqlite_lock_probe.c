/* Reproduce mixed SQLite WAL locking on disposable files only. */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
typedef struct {
    void *library;
    int (*open)(const char *, void **, int, const char *);
    int (*exec)(void *, const char *, void *, void *, char **);
    int (*close)(void *);
    const char *(*error)(void *);
    const char *(*version)(void);
} Api;
static Api api(const char *path) {
    Api a={0};a.library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(!a.library){fprintf(stderr,"dlopen: %s\n",dlerror());exit(2);}
    a.open=dlsym(a.library,"sqlite3_open_v2");a.exec=dlsym(a.library,"sqlite3_exec");
    a.close=dlsym(a.library,"sqlite3_close");a.error=dlsym(a.library,"sqlite3_errmsg");
    a.version=dlsym(a.library,"sqlite3_libversion");
    if(!a.open||!a.exec||!a.close||!a.error||!a.version)exit(3);
    printf("Loaded %s: %s\n",path,a.version());return a;
}
static void execute(Api *a,void *db,const char *sql) {
    int code=a->exec(db,sql,NULL,NULL,NULL);
    if(code){fprintf(stderr,"SQL %s: %d %s\n",sql,code,a->error(db));exit(4);}
}
static void reader(const char *path) {
    Api a=api("/system/lib64/libsqlite.so");void *db=NULL;
    if(a.open(path,&db,1|0x10000,NULL))exit(5);
    execute(&a,db,"SELECT * FROM weather_lock_probe");a.close(db);
}
static int writer_lock(const char *path) {
    Api a=api("/system/lib64/libsqlite.so");void *db=NULL;
    if(a.open(path,&db,2|0x10000,NULL))return 5;
    int code=a.exec(db,"BEGIN IMMEDIATE",NULL,NULL,NULL);
    printf("Second library BEGIN IMMEDIATE: %d (5 means the writer lock was respected)\n",code);
    if(code==0)execute(&a,db,"ROLLBACK");
    a.close(db);
    return code==5?0:9;
}
int main(int argc,char **argv) {
    setbuf(stdout,NULL);
    if(argc!=4||strstr(argv[3],"/data/local/tmp/weather-lock-probe-")!=argv[3])return 6;
    Api a=api(argv[1]);void *db=NULL;
    if(a.open(argv[3],&db,2|4|0x10000,NULL))return 7;
    execute(&a,db,"PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS weather_lock_probe(n); DELETE FROM weather_lock_probe; INSERT INTO weather_lock_probe VALUES(0)");
    if(!strncmp(argv[2],"lock-",5)) {
        execute(&a,db,"BEGIN IMMEDIATE; INSERT INTO weather_lock_probe VALUES(1)");
        int result;
        if(!strcmp(argv[2],"lock-separate")) {
            pid_t child=fork();if(child==0)_exit(writer_lock(argv[3]));
            int status;waitpid(child,&status,0);result=WIFEXITED(status)?WEXITSTATUS(status):10;
        } else result=writer_lock(argv[3]);
        execute(&a,db,"ROLLBACK");a.close(db);return result;
    }
    for(int i=0;i<10;i++) {
        execute(&a,db,"BEGIN IMMEDIATE; INSERT INTO weather_lock_probe VALUES(1)");
        if(!strcmp(argv[2],"separate")) {
            pid_t child=fork();if(child==0){reader(argv[3]);_exit(0);}
            int status;waitpid(child,&status,0);if(!WIFEXITED(status)||WEXITSTATUS(status))return 8;
        } else reader(argv[3]);
        char shm[4096];snprintf(shm,sizeof(shm),"%s-shm",argv[3]);struct stat info;
        printf("Iteration %d shared-memory bytes: %lld\n",i,stat(shm,&info)?-1LL:(long long)info.st_size);
        execute(&a,db,"COMMIT");
    }
    a.close(db);printf("PASS: ten WAL writes with %s reader\n",argv[2]);return 0;
}
