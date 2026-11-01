/*
 * thorbridge.c - JNI bridge between the Thor Half-Life Java deck (de.harmssi.thorhalflife.NativeBridge)
 * and the HL client library (cl_dll/thor_deck.cpp).
 *
 * The client library exports two plain C functions:
 *   int  ThorHUD_Snapshot( char *buf, int size );   // compact JSON snapshot of the HUD state
 *   void ThorHUD_Command( const char *cmd );         // queued, executed on the game thread
 *
 * The bridge never touches game state itself: it only finds those symbols in the already loaded
 * client library (dlopen RTLD_NOLOAD) and forwards the calls. GPL-3.0-or-later (see README).
 */
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "ThorHalfLife"

typedef int (*snapshot_fn)(char *, int);
typedef void (*command_fn)(const char *);

static snapshot_fn g_snapshot;
static command_fn g_command;
static char g_buf[32768];

static int resolve(JNIEnv *env, jstring jpath)
{
    if (g_snapshot && g_command)
        return 1;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    void *h = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!h)
        return 0; /* client library not loaded yet */
    g_snapshot = (snapshot_fn)dlsym(h, "ThorHUD_Snapshot");
    g_command = (command_fn)dlsym(h, "ThorHUD_Command");
    /* keep the reference: the engine never unloads the client library while the process lives */
    if (!g_snapshot || !g_command) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "client library without ThorHUD_* exports");
        g_snapshot = NULL;
        g_command = NULL;
        dlclose(h);
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "deck bridge connected to client library");
    return 1;
}

JNIEXPORT jstring JNICALL
Java_de_harmssi_thorhalflife_NativeBridge_snapshot(JNIEnv *env, jclass cls, jstring clientLib)
{
    (void)cls;
    if (!resolve(env, clientLib))
        return NULL;
    int n = g_snapshot(g_buf, (int)sizeof(g_buf));
    if (n <= 0)
        return NULL;
    g_buf[sizeof(g_buf) - 1] = 0;
    return (*env)->NewStringUTF(env, g_buf);
}

JNIEXPORT jboolean JNICALL
Java_de_harmssi_thorhalflife_NativeBridge_command(JNIEnv *env, jclass cls, jstring clientLib, jstring cmd)
{
    (void)cls;
    if (!resolve(env, clientLib))
        return JNI_FALSE;
    const char *c = (*env)->GetStringUTFChars(env, cmd, NULL);
    g_command(c);
    (*env)->ReleaseStringUTFChars(env, cmd, c);
    return JNI_TRUE;
}
