#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include "out_dxf.h"

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
