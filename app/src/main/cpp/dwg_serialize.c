#include "dwg_serialize.h"
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    uint8_t *buf;
    size_t   len;
    size_t   cap;
    int      ok;
} Writer;

static void w_reserve(Writer *w, size_t need) {
    if (!w->ok) return;
    if (w->len + need > w->cap) {
        size_t new_cap = w->cap ? w->cap * 2 : 4096;
        while (new_cap < w->len + need) new_cap *= 2;
        uint8_t *nb = (uint8_t *)realloc(w->buf, new_cap);
        if (!nb) { w->ok = 0; return; }
        w->buf = nb; w->cap = new_cap;
    }
}

static void w_u8 (Writer *w, uint8_t  v) { w_reserve(w, 1); if(w->ok){w->buf[w->len++]=v;} }
static void w_u16(Writer *w, uint16_t v) { w_reserve(w, 2); if(w->ok){memcpy(w->buf+w->len,&v,2); w->len+=2;} }
static void w_u32(Writer *w, uint32_t v) { w_reserve(w, 4); if(w->ok){memcpy(w->buf+w->len,&v,4); w->len+=4;} }
static void w_i16(Writer *w, int16_t  v) { w_reserve(w, 2); if(w->ok){memcpy(w->buf+w->len,&v,2); w->len+=2;} }
static void w_i32(Writer *w, int32_t  v) { w_reserve(w, 4); if(w->ok){memcpy(w->buf+w->len,&v,4); w->len+=4;} }
static void w_f64(Writer *w, double   v) { w_reserve(w, 8); if(w->ok){memcpy(w->buf+w->len,&v,8); w->len+=8;} }

static void w_string_utf8(Writer *w, const char *s) {
    size_t n = s ? strlen(s) : 0;
    if (n > 0xFFFF) n = 0xFFFF;
    w_u16(w, (uint16_t)n);
    w_reserve(w, n);
    if (w->ok && n > 0) { memcpy(w->buf + w->len, s, n); w->len += n; }
}

/* LibreDWG의 TV(텍스트 값)는 codepage가 이미 적용된 char* 또는 R2007+의 TU(UTF-16).
 * bit_TV_to_utf8()이 두 경우 모두 안전하게 UTF-8 char*를 반환한다.
 *
 * 중요: bit_TV_to_utf8은 codepage가 CP_UTF8이거나 escape 확장이 필요 없는 경우
 * 입력 포인터(tv)를 그대로 반환할 수 있다. 그 경우 free()를 호출하면 LibreDWG
 * 내부 메모리가 손상된다. 우리는 항상 malloc된 사본을 반환하도록 strdup한다.
 *
 * 호출 후에는 free()로 해제. NULL이면 빈 문자열로 처리.
 */
static char *tv_to_utf8(const Dwg_Data *dwg, BITCODE_TV tv) {
    if (!tv) return NULL;
    char *r = bit_TV_to_utf8(tv, dwg->header.codepage);
    if (!r) return NULL;
    if (r == (char *)tv) {
        /* bit_TV_to_utf8이 src를 그대로 반환 → free() 안전을 위해 복사 */
        return strdup((const char *)tv);
    }
    return r;
}

/* ---- Layer 테이블 ---- */

static int write_layer_table(Writer *w, const Dwg_Data *dwg) {
    Dwg_Object_LAYER **layers = dwg_getall_LAYER((Dwg_Data *)dwg);
    int written = 0;
    if (!layers) return 0;
    for (int i = 0; layers[i] != NULL; ++i) {
        Dwg_Object_LAYER *lay = layers[i];
        char *name = tv_to_utf8(dwg, lay->name);
        w_string_utf8(w, name ? name : "");
        free(name);
        int16_t ci = (int16_t)(lay->color.index);
        w_i16(w, ci);
        uint32_t rgb = 0;
        if ((lay->color.flag & 0x80) && !(lay->color.flag & 0x40)) {
            rgb = (uint32_t)(lay->color.rgb & 0x00FFFFFFu);
        }
        w_u32(w, rgb);
        uint8_t flags = 0;
        if (lay->frozen)  flags |= 1;
        if (lay->off)     flags |= 2;
        if (lay->locked)  flags |= 4;
        w_u8(w, flags);
        written++;
    }
    free(layers);
    return written;
}

/* ---- Entity 헤더 (typeId, layer_idx, color, rgb) ---- */

/* layer handle로부터 우리 테이블 인덱스 찾기 — name 문자열 매칭으로 단순화.
 * Task 11에서 hash로 개선 예정. */
static int32_t resolve_layer_idx(const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Object_Entity *ent = obj->tio.entity;
    if (!ent || !ent->layer || !ent->layer->obj) return -1;
    Dwg_Object_LAYER *src_lay = ent->layer->obj->tio.object->tio.LAYER;
    if (!src_lay || !src_lay->name) return -1;
    char *src_name = tv_to_utf8(dwg, src_lay->name);
    if (!src_name) return -1;

    Dwg_Object_LAYER **arr = dwg_getall_LAYER((Dwg_Data *)dwg);
    int32_t idx = -1;
    if (arr) {
        for (int i = 0; arr[i] != NULL; ++i) {
            if (!arr[i]->name) continue;
            char *nm = tv_to_utf8(dwg, arr[i]->name);
            if (nm && strcmp(nm, src_name) == 0) { idx = (int32_t)i; free(nm); break; }
            free(nm);
        }
        free(arr);
    }
    free(src_name);
    return idx;
}

static void write_entity_header(Writer *w, const Dwg_Data *dwg,
                                const Dwg_Object *obj, uint8_t typeId) {
    w_u8(w, typeId);
    w_i32(w, resolve_layer_idx(dwg, obj));
    Dwg_Object_Entity *ent = obj->tio.entity;
    int16_t ci = -1;
    uint32_t rgb = 0;
    if (ent) {
        ci = (int16_t)(ent->color.index);
        if ((ent->color.flag & 0x80) && !(ent->color.flag & 0x40)) {
            rgb = (uint32_t)(ent->color.rgb & 0x00FFFFFFu);
        }
    }
    w_i16(w, ci);
    w_u32(w, rgb);
}

/* ---- 엔티티 ---- */

static void write_line(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_LINE *e = obj->tio.entity->tio.LINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
    w_f64(w, e->start.x); w_f64(w, e->start.y);
    w_f64(w, e->end.x);   w_f64(w, e->end.y);
}

static void write_circle(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_CIRCLE *e = obj->tio.entity->tio.CIRCLE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_CIRCLE);
    w_f64(w, e->center.x); w_f64(w, e->center.y);
    w_f64(w, e->radius);
}

static void write_arc(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_ARC *e = obj->tio.entity->tio.ARC;
    write_entity_header(w, dwg, obj, DWGB_TYPE_ARC);
    w_f64(w, e->center.x); w_f64(w, e->center.y);
    w_f64(w, e->radius);
    /* LibreDWG는 radian으로 보관 */
    double sd = e->start_angle * 180.0 / 3.14159265358979323846;
    double ed = e->end_angle   * 180.0 / 3.14159265358979323846;
    w_f64(w, sd); w_f64(w, ed);
}

/* ---- 메인 진입점 ---- */

uint8_t *dwgb_serialize(const Dwg_Data *dwg, size_t *out_len) {
    Writer w;
    memset(&w, 0, sizeof(Writer));
    w.ok = 1;

    /* Header */
    w_u32(&w, DWGB_MAGIC);
    w_u16(&w, DWGB_PROTOCOL_VERSION);
    w_u16(&w, 0);

    /* num_layers / num_entities placeholder — 나중에 채움 */
    size_t pos_num_layers   = w.len; w_i32(&w, 0);
    size_t pos_num_entities = w.len; w_i32(&w, 0);

    /* extents: 일단 미사용 */
    w_i32(&w, 0);

    /* Layers */
    int n_layers = write_layer_table(&w, dwg);
    if (w.ok) {
        int32_t nl = (int32_t)n_layers;
        memcpy(w.buf + pos_num_layers, &nl, 4);
    }

    /* Entities */
    int n_entities = 0;
    for (BITCODE_BL i = 0; i < dwg->num_objects; ++i) {
        const Dwg_Object *obj = &dwg->object[i];
        if (obj->supertype != DWG_SUPERTYPE_ENTITY) continue;
        switch (obj->fixedtype) {
            case DWG_TYPE_LINE:   write_line  (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_CIRCLE: write_circle(&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_ARC:    write_arc   (&w, dwg, obj); n_entities++; break;
            default: break;  /* Task 9~10에서 더 추가 */
        }
        if (!w.ok) break;
    }
    if (w.ok) {
        int32_t ne = (int32_t)n_entities;
        memcpy(w.buf + pos_num_entities, &ne, 4);
    }

    if (!w.ok) { free(w.buf); return NULL; }
    *out_len = w.len;
    return w.buf;
}
