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

    /* 진단: model_space 최상위 엔티티 타입 분포 + INSERT 통계 */
    {
        Dwg_Object_Ref *m = dwg.header_vars.BLOCK_RECORD_MSPACE;
        if (m && m->obj) {
            Dwg_Object_BLOCK_HEADER *ms = m->obj->tio.object->tio.BLOCK_HEADER;
            if (ms) {
                int n_insert = 0, n_dim = 0, n_hatch = 0, n_text = 0, n_line = 0, n_other = 0;
                unsigned long n_top = 0;
                /* INSERT 타겟 블록 이름 top-frequency 추적: 최대 8개만 */
                struct { char name[64]; int count; } top_blocks[8];
                int n_block_names = 0;
                memset(top_blocks, 0, sizeof(top_blocks));
                if (ms->entities && ms->num_owned > 0) {
                    n_top = ms->num_owned;
                    for (BITCODE_BL i = 0; i < ms->num_owned; ++i) {
                        if (!ms->entities[i] || !ms->entities[i]->obj) continue;
                        Dwg_Object *eo = ms->entities[i]->obj;
                        switch (eo->fixedtype) {
                            case DWG_TYPE_INSERT: {
                                n_insert++;
                                Dwg_Entity_INSERT *ie = eo->tio.entity->tio.INSERT;
                                const char *bn = "(unnamed)";
                                if (ie->block_header && ie->block_header->obj) {
                                    Dwg_Object_BLOCK_HEADER *bh
                                        = ie->block_header->obj->tio.object->tio.BLOCK_HEADER;
                                    if (bh && bh->name) bn = (const char *)bh->name;
                                }
                                int found = 0;
                                for (int k = 0; k < n_block_names; ++k) {
                                    if (strncmp(top_blocks[k].name, bn, 63) == 0) {
                                        top_blocks[k].count++;
                                        found = 1; break;
                                    }
                                }
                                if (!found && n_block_names < 8) {
                                    strncpy(top_blocks[n_block_names].name, bn, 63);
                                    top_blocks[n_block_names].count = 1;
                                    n_block_names++;
                                }
                                break;
                            }
                            case DWG_TYPE_DIMENSION_ALIGNED:
                            case DWG_TYPE_DIMENSION_LINEAR:
                            case DWG_TYPE_DIMENSION_ANG3PT:
                            case DWG_TYPE_DIMENSION_ANG2LN:
                            case DWG_TYPE_DIMENSION_RADIUS:
                            case DWG_TYPE_DIMENSION_DIAMETER:
                            case DWG_TYPE_DIMENSION_ORDINATE:
                                n_dim++; break;
                            case DWG_TYPE_HATCH:    n_hatch++; break;
                            case DWG_TYPE_TEXT:
                            case DWG_TYPE_MTEXT:    n_text++; break;
                            case DWG_TYPE_LINE:     n_line++; break;
                            default: n_other++;
                        }
                    }
                }
                LOGI("nativeDwgToBuffer: mspace top-level entities=%lu insert=%d dim=%d hatch=%d text=%d line=%d other=%d",
                     n_top, n_insert, n_dim, n_hatch, n_text, n_line, n_other);

                /* mspace top-level LINE 분포 — bounding box로 평면도 영역 직접 포함 여부 확인 */
                double lx_min = 1e18, ly_min = 1e18, lx_max = -1e18, ly_max = -1e18;
                int n_line_plan = 0; /* 평면도 영역 추정: x in [-100K, 200K], y in [-600K, -300K] */
                if (ms->entities) {
                    for (BITCODE_BL i = 0; i < ms->num_owned; ++i) {
                        if (!ms->entities[i] || !ms->entities[i]->obj) continue;
                        Dwg_Object *eo = ms->entities[i]->obj;
                        if (eo->fixedtype != DWG_TYPE_LINE) continue;
                        Dwg_Entity_LINE *le = eo->tio.entity->tio.LINE;
                        double x1 = le->start.x, y1 = le->start.y;
                        if (x1 < lx_min) lx_min = x1;
                        if (y1 < ly_min) ly_min = y1;
                        if (x1 > lx_max) lx_max = x1;
                        if (y1 > ly_max) ly_max = y1;
                        if (x1 > -100000.0 && x1 < 200000.0
                            && y1 > -600000.0 && y1 < -300000.0)
                            n_line_plan++;
                    }
                }
                LOGI("nativeDwgToBuffer: mspace LINE bbox=(%.0f,%.0f)~(%.0f,%.0f) in_plan_area=%d/%d",
                     lx_min, ly_min, lx_max, ly_max, n_line_plan, n_line);

                /* paper-space (PSPACE)에 entities가 있는지 확인 */
                Dwg_Object_Ref *pref = dwg.header_vars.BLOCK_RECORD_PSPACE;
                if (pref && pref->obj) {
                    Dwg_Object_BLOCK_HEADER *ps = pref->obj->tio.object->tio.BLOCK_HEADER;
                    if (ps) {
                        LOGI("nativeDwgToBuffer: PSPACE num_owned=%u", (unsigned)ps->num_owned);
                    }
                }
                /* 모든 BLOCK_HEADER 순회해서 다른 layout block 찾기 */
                int n_layouts = 0;
                for (BITCODE_BL i = 0; i < dwg.num_objects && n_layouts < 10; ++i) {
                    Dwg_Object *bo = &dwg.object[i];
                    if (bo->fixedtype != DWG_TYPE_BLOCK_HEADER) continue;
                    Dwg_Object_BLOCK_HEADER *bh = bo->tio.object->tio.BLOCK_HEADER;
                    if (!bh || !bh->name) continue;
                    /* layout block은 *Paper_Space, *Paper_Space0, *Model_Space 형식 */
                    const char *nm = (const char*)bh->name;
                    if (strncmp(nm, "*Paper", 6) == 0 && bh->num_owned > 0) {
                        LOGI("nativeDwgToBuffer: layout '%s' num_owned=%u",
                             nm, (unsigned)bh->num_owned);
                        n_layouts++;
                    }
                }
                for (int k = 0; k < n_block_names; ++k) {
                    LOGI("nativeDwgToBuffer: INSERT target block '%s' x%d",
                         top_blocks[k].name, top_blocks[k].count);
                }
            }
        }
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
