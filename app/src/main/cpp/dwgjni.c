#include <jni.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include "out_dxf.h"
#include "dwg_serialize.h"

#define LOG_TAG "CleanCAD/dwgjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jstring JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeLibredwgVersion(
        JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, dwg_api_version_string());
}

JNIEXPORT jint JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeDwgToDxf(
        JNIEnv *env, jobject thiz,
        jstring inPathJ, jstring outPathJ) {
    const char *inPath  = (*env)->GetStringUTFChars(env, inPathJ, NULL);
    const char *outPath = (*env)->GetStringUTFChars(env, outPathJ, NULL);

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    int error = dwg_read_file(inPath, &dwg);

    if (error >= DWG_ERR_CRITICAL) {
        (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
        (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
        return error;
    }

    Bit_Chain dat;
    memset(&dat, 0, sizeof(Bit_Chain));
    dat.version      = dwg.header.version;
    dat.from_version = dwg.header.from_version;
    dat.fh           = fopen(outPath, "wb");

    if (!dat.fh) {
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
        (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
        return DWG_ERR_IOERROR;
    }

    error = dwg_write_dxf(&dat, &dwg);
    fclose(dat.fh);
    dwg_free(&dwg);

    (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
    (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
    return (error >= DWG_ERR_CRITICAL) ? error : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeDwgToBuffer(
        JNIEnv *env, jobject thiz, jstring inPathJ) {
    const char *inPath = (*env)->GetStringUTFChars(env, inPathJ, NULL);
    if (!inPath) {
        LOGE("nativeDwgToBuffer: GetStringUTFChars returned NULL");
        return NULL;
    }
    LOGI("nativeDwgToBuffer: start path=%s", inPath);

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    int err = dwg_read_file(inPath, &dwg);
    LOGI("nativeDwgToBuffer: dwg_read_file rc=%d, num_objects=%u",
         err, (unsigned)dwg.num_objects);
    (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
    if (err >= DWG_ERR_CRITICAL) {
        LOGE("nativeDwgToBuffer: critical read error (rc=%d)", err);
        dwg_free(&dwg);
        return NULL;
    }

    size_t len = 0;
    uint8_t *bytes = dwgb_serialize(&dwg, &len);
    LOGI("nativeDwgToBuffer: serialized %zu bytes", len);
    dwg_free(&dwg);
    if (!bytes) {
        LOGE("nativeDwgToBuffer: dwgb_serialize returned NULL");
        return NULL;
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (!out) {
        LOGE("nativeDwgToBuffer: NewByteArray(%zu) failed", len);
        free(bytes);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)bytes);
    free(bytes);
    LOGI("nativeDwgToBuffer: returned %zu-byte array", len);
    return out;
}
