#include "dwg_serialize.h"
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DWGB_MAX_INSERT_DEPTH 5
#define DWGB_MAX_ENTITIES 500000

/* XCLIP: 현재 활성 world 클립 사각형(축정렬). write_insert 진입 시 set, 나갈 때 restore.
 * 기하 클리핑(선/폴리라인 잘라내기)과 소형 엔티티 컬링에 사용. */
static int    g_clip_on = 0;
static double g_clx0, g_cly0, g_clx1, g_cly1;

/* Liang-Barsky: 선분 (x0,y0)-(x1,y1)을 사각형 [xmin,xmax]x[ymin,ymax]로 클립.
 * 보이면 1 반환(엔드포인트를 클립 결과로 갱신), 완전히 밖이면 0. */
static int clip_seg(double *x0, double *y0, double *x1, double *y1,
                    double xmin, double ymin, double xmax, double ymax) {
    double dx = *x1 - *x0, dy = *y1 - *y0;
    double p[4] = { -dx, dx, -dy, dy };
    double q[4] = { *x0 - xmin, xmax - *x0, *y0 - ymin, ymax - *y0 };
    double u1 = 0.0, u2 = 1.0;
    for (int i = 0; i < 4; ++i) {
        if (p[i] == 0.0) { if (q[i] < 0.0) return 0; }
        else {
            double r = q[i] / p[i];
            if (p[i] < 0.0) { if (r > u2) return 0; if (r > u1) u1 = r; }
            else            { if (r < u1) return 0; if (r < u2) u2 = r; }
        }
    }
    double ox = *x0, oy = *y0;
    *x0 = ox + u1 * dx; *y0 = oy + u1 * dy;
    *x1 = ox + u2 * dx; *y1 = oy + u2 * dy;
    return 1;
}

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

/* ---- Layer name → index 캐시 (직렬화 1회 동안만 유효) ---- */

typedef struct {
    char    *name;      /* malloc된 UTF-8 사본 (NULL 가능) */
    int32_t  index;
} LayerCacheEntry;

typedef struct {
    LayerCacheEntry *entries;
    int              count;
    Dwg_Object_LAYER **arr;  /* dwg_getall_LAYER 결과 보관 (한 번만 호출) */
} LayerCache;

/* 직렬화 한 번에만 유효 — dwgb_serialize 시작/끝에서 init/free */
static LayerCache g_layer_cache;

static void layer_cache_init(LayerCache *cache, const Dwg_Data *dwg) {
    cache->entries = NULL;
    cache->count = 0;
    cache->arr = dwg_getall_LAYER((Dwg_Data *)dwg);
    if (!cache->arr) return;
    /* NULL-terminated 배열의 길이 측정 */
    int n = 0;
    while (cache->arr[n]) n++;
    cache->count = n;
    cache->entries = (LayerCacheEntry *)calloc((size_t)n, sizeof(LayerCacheEntry));
    if (!cache->entries) { cache->count = 0; return; }
    for (int i = 0; i < n; ++i) {
        Dwg_Object_LAYER *lay = cache->arr[i];
        cache->entries[i].index = i;
        if (lay && lay->name) {
            cache->entries[i].name = tv_to_utf8(dwg, lay->name);
        }
    }
}

static void layer_cache_free(LayerCache *cache) {
    if (cache->entries) {
        for (int i = 0; i < cache->count; ++i) free(cache->entries[i].name);
        free(cache->entries);
    }
    if (cache->arr) free(cache->arr);
    cache->entries = NULL;
    cache->arr = NULL;
    cache->count = 0;
}

static int32_t layer_cache_resolve(const LayerCache *cache,
                                   const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Object_Entity *ent = obj->tio.entity;
    if (!ent || !ent->layer || !ent->layer->obj) return -1;
    Dwg_Object_LAYER *lay = ent->layer->obj->tio.object->tio.LAYER;
    if (!lay || !lay->name) return -1;
    char *target = tv_to_utf8(dwg, lay->name);
    if (!target) return -1;
    int32_t result = -1;
    for (int i = 0; i < cache->count; ++i) {
        if (cache->entries[i].name &&
            strcmp(cache->entries[i].name, target) == 0) {
            result = cache->entries[i].index;
            break;
        }
    }
    free(target);
    return result;
}

/* ---- 2D affine transform helper ---- */
/* scale → rotate → translate */
static void affine_point(double *px, double *py,
                         double sx, double sy, double rot_rad,
                         double tx, double ty) {
    double x = (*px) * sx;
    double y = (*py) * sy;
    double cs = cos(rot_rad), sn = sin(rot_rad);
    double rx = x * cs - y * sn;
    double ry = x * sn + y * cs;
    *px = rx + tx;
    *py = ry + ty;
}

/* ---- Layer 테이블 ---- */

static int write_layer_table(Writer *w, const Dwg_Data *dwg) {
    (void)dwg; /* 캐시에서 가져오므로 dwg 직접 사용 안 함 */
    int written = 0;
    Dwg_Object_LAYER **layers = g_layer_cache.arr;
    int n = g_layer_cache.count;
    if (!layers) return 0;
    for (int i = 0; i < n; ++i) {
        Dwg_Object_LAYER *lay = layers[i];
        if (!lay) continue;
        const char *cached_name = (g_layer_cache.entries && g_layer_cache.entries[i].name)
            ? g_layer_cache.entries[i].name : "";
        w_string_utf8(w, cached_name);
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
    return written;
}

/* ---- Entity 헤더 (typeId, layer_idx, color, rgb) ---- */

/* layer handle로부터 우리 테이블 인덱스 찾기 — g_layer_cache 사용 (O(n) 1회 구축). */
static int32_t resolve_layer_idx(const Dwg_Data *dwg, const Dwg_Object *obj) {
    return layer_cache_resolve(&g_layer_cache, dwg, obj);
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

/* ---- 9a-1: POLYLINE 계열 ---- */

/* 반환: emit한 엔티티 수. 클립 활성 + 경계 가로지름이면 보이는 세그먼트를 2점 폴리라인으로 분할. */
static int write_lwpolyline(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                            double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LWPOLYLINE *e = obj->tio.entity->tio.LWPOLYLINE;
    uint8_t closed = ((e->flag & 1) || (e->flag & 512)) ? 1 : 0;
    BITCODE_BL n = e->num_points;

    if (g_clip_on && n >= 2) {
        /* 전부 클립 안쪽인지 검사 */
        int all_in = 1;
        for (BITCODE_BL i = 0; i < n; ++i) {
            double x = e->points[i].x, y = e->points[i].y;
            affine_point(&x, &y, sx, sy, rot, tx, ty);
            if (x < g_clx0 || x > g_clx1 || y < g_cly0 || y > g_cly1) { all_in = 0; break; }
        }
        if (!all_in) {
            /* 가로지름: 세그먼트별 클립 → 2점 폴리라인 조각 */
            BITCODE_BL segs = closed ? n : (n - 1);
            int emitted = 0;
            for (BITCODE_BL i = 0; i < segs && w->ok; ++i) {
                BITCODE_BL j = (i + 1) % n;
                double ax = e->points[i].x, ay = e->points[i].y;
                double bx = e->points[j].x, by = e->points[j].y;
                affine_point(&ax, &ay, sx, sy, rot, tx, ty);
                affine_point(&bx, &by, sx, sy, rot, tx, ty);
                if (!clip_seg(&ax, &ay, &bx, &by, g_clx0, g_cly0, g_clx1, g_cly1)) continue;
                write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
                w_u8(w, 0); w_i32(w, 2);
                w_f64(w, ax); w_f64(w, ay); w_f64(w, bx); w_f64(w, by);
                emitted++;
            }
            return emitted;
        }
        /* 전부 안쪽 → 통째 emit (아래 공통 경로) */
    }

    write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
    w_u8(w, closed);
    w_i32(w, (int32_t)n);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < n; ++i) {
        double px = e->points[i].x, py = e->points[i].y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
    return 1;
}

static void write_polyline_2d(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                              double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_POLYLINE_2D *e = obj->tio.entity->tio.POLYLINE_2D;
    write_entity_header(w, dwg, obj, DWGB_TYPE_POLYLINE_2D);
    uint8_t closed = (e->flag & 1) ? 1 : 0;
    w_u8(w, closed);
    BITCODE_BL n = e->num_owned;
    w_i32(w, (int32_t)n);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < n; ++i) {
        if (!e->vertex || !e->vertex[i] || !e->vertex[i]->obj) {
            w_f64(w, 0.0); w_f64(w, 0.0); continue;
        }
        Dwg_Entity_VERTEX_2D *v = e->vertex[i]->obj->tio.entity->tio.VERTEX_2D;
        double px = v->point.x, py = v->point.y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
}

static void write_polyline_3d(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                              double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_POLYLINE_3D *e = obj->tio.entity->tio.POLYLINE_3D;
    write_entity_header(w, dwg, obj, DWGB_TYPE_POLYLINE_3D);
    uint8_t closed = (e->flag & 1) ? 1 : 0;
    w_u8(w, closed);
    BITCODE_BL n = e->num_owned;
    w_i32(w, (int32_t)n);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < n; ++i) {
        if (!e->vertex || !e->vertex[i] || !e->vertex[i]->obj) {
            w_f64(w, 0.0); w_f64(w, 0.0); continue;
        }
        Dwg_Entity_VERTEX_3D *v = e->vertex[i]->obj->tio.entity->tio.VERTEX_3D;
        double px = v->point.x, py = v->point.y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
}

/* ---- 9a-2: TEXT / MTEXT ---- */

static void write_text(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                       double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_TEXT *e = obj->tio.entity->tio.TEXT;
    write_entity_header(w, dwg, obj, DWGB_TYPE_TEXT);
    double px = e->ins_pt.x, py = e->ins_pt.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&px, &py, sx, sy, rot, tx, ty);
    }
    w_f64(w, px); w_f64(w, py);
    double scale_avg = (sx + sy) * 0.5;
    w_f64(w, e->height * fabs(scale_avg));
    double rot_deg = e->rotation * 180.0 / 3.14159265358979323846;
    double final_rot = rot_deg + (rot * 180.0 / 3.14159265358979323846);
    w_f64(w, final_rot);
    char *utf8 = tv_to_utf8(dwg, e->text_value);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
}

static void write_mtext(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_MTEXT *e = obj->tio.entity->tio.MTEXT;
    write_entity_header(w, dwg, obj, DWGB_TYPE_MTEXT);
    double px = e->ins_pt.x, py = e->ins_pt.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&px, &py, sx, sy, rot, tx, ty);
    }
    w_f64(w, px); w_f64(w, py);
    double scale_avg = (sx + sy) * 0.5;
    w_f64(w, e->text_height * fabs(scale_avg));
    /* MTEXT rotation은 x_axis_dir 벡터로부터 계산 */
    double rot_rad = atan2(e->x_axis_dir.y, e->x_axis_dir.x);
    double rot_deg = rot_rad * 180.0 / 3.14159265358979323846;
    double final_rot = rot_deg + (rot * 180.0 / 3.14159265358979323846);
    w_f64(w, final_rot);
    char *utf8 = tv_to_utf8(dwg, e->text);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
}

/* ---- 9a-3: 3DFACE / SOLID / ELLIPSE / SPLINE ---- */

static void write_3dface(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity__3DFACE *e = obj->tio.entity->tio._3DFACE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_3DFACE);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    double c1x = e->corner1.x, c1y = e->corner1.y;
    double c2x = e->corner2.x, c2y = e->corner2.y;
    double c3x = e->corner3.x, c3y = e->corner3.y;
    double c4x = e->corner4.x, c4y = e->corner4.y;
    if (do_transform) {
        affine_point(&c1x, &c1y, sx, sy, rot, tx, ty);
        affine_point(&c2x, &c2y, sx, sy, rot, tx, ty);
        affine_point(&c3x, &c3y, sx, sy, rot, tx, ty);
        affine_point(&c4x, &c4y, sx, sy, rot, tx, ty);
    }
    w_f64(w, c1x); w_f64(w, c1y);
    w_f64(w, c2x); w_f64(w, c2y);
    w_f64(w, c3x); w_f64(w, c3y);
    w_f64(w, c4x); w_f64(w, c4y);
}

static void write_solid(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    /* SOLID corners are BITCODE_2RD (2D points) */
    Dwg_Entity_SOLID *e = obj->tio.entity->tio.SOLID;
    write_entity_header(w, dwg, obj, DWGB_TYPE_SOLID);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    double c1x = e->corner1.x, c1y = e->corner1.y;
    double c2x = e->corner2.x, c2y = e->corner2.y;
    double c3x = e->corner3.x, c3y = e->corner3.y;
    double c4x = e->corner4.x, c4y = e->corner4.y;
    if (do_transform) {
        affine_point(&c1x, &c1y, sx, sy, rot, tx, ty);
        affine_point(&c2x, &c2y, sx, sy, rot, tx, ty);
        affine_point(&c3x, &c3y, sx, sy, rot, tx, ty);
        affine_point(&c4x, &c4y, sx, sy, rot, tx, ty);
    }
    w_f64(w, c1x); w_f64(w, c1y);
    w_f64(w, c2x); w_f64(w, c2y);
    w_f64(w, c3x); w_f64(w, c3y);
    w_f64(w, c4x); w_f64(w, c4y);
}

static void write_ellipse(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                          double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_ELLIPSE *e = obj->tio.entity->tio.ELLIPSE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_ELLIPSE);
    double cx = e->center.x, cy = e->center.y;
    double smx = e->sm_axis.x, smy = e->sm_axis.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&cx, &cy, sx, sy, rot, tx, ty);
        /* sm_axis is a direction vector — scale + rotate but no translate */
        affine_point(&smx, &smy, sx, sy, rot, 0.0, 0.0);
    }
    w_f64(w, cx); w_f64(w, cy);
    w_f64(w, smx); w_f64(w, smy);
    w_f64(w, e->axis_ratio);
    w_f64(w, e->start_angle); w_f64(w, e->end_angle);
}

static void write_spline(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot) {
    /* SPLINE ctrl_pts are Dwg_SPLINE_control_point with .x, .y, .z, .w members */
    Dwg_Entity_SPLINE *e = obj->tio.entity->tio.SPLINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_SPLINE);
    w_i32(w, (int32_t)e->degree);
    w_i32(w, (int32_t)e->num_ctrl_pts);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < e->num_ctrl_pts; ++i) {
        double px = e->ctrl_pts[i].x, py = e->ctrl_pts[i].y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
}

/* ---- 9a-4: DIMENSION / LEADER ---- */

/* write_dimension expands anonymous blocks via write_entity (forward declared here) */
static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr);

static void write_dimension(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                            double tx, double ty, double sx, double sy, double rot,
                            int depth, int *count_ptr) {
    /* 모든 DIMENSION_* sub-type은 DIMENSION_COMMON 매크로를 공유.
     * ALIGNED로 캐스팅하여 공통 필드 접근. */
    Dwg_Entity_DIMENSION_ALIGNED *e = obj->tio.entity->tio.DIMENSION_ALIGNED;

    /* anonymous block은 BLOCK-local 좌표를 담고 있다. INSERT처럼
     * (clone_ins_pt, ins_scale, ins_rotation)으로 변환해야 WCS로 옴.
     * 부모(tx,ty,sx,sy,rot) 변환과 합성. */
    if (e->block && e->block->obj
        && e->block->obj->fixedtype == DWG_TYPE_BLOCK_HEADER) {
        Dwg_Object_BLOCK_HEADER *bh = e->block->obj->tio.object->tio.BLOCK_HEADER;
        if (bh) {
            double cx = e->clone_ins_pt.x, cy = e->clone_ins_pt.y;
            double csx = (e->ins_scale.x != 0.0) ? e->ins_scale.x : 1.0;
            double csy = (e->ins_scale.y != 0.0) ? e->ins_scale.y : 1.0;
            double crot = e->ins_rotation;
            /* 부모 변환을 dimension 삽입점에 적용 */
            double abs_x = cx, abs_y = cy;
            affine_point(&abs_x, &abs_y, sx, sy, rot, tx, ty);
            double new_sx = sx * csx;
            double new_sy = sy * csy;
            double new_rot = rot + crot;

            int expanded = 0;
            if (bh->entities && bh->num_owned > 0) {
                for (BITCODE_BL i = 0; i < bh->num_owned; ++i) {
                    if (*count_ptr >= DWGB_MAX_ENTITIES) break;
                    if (!bh->entities[i] || !bh->entities[i]->obj) continue;
                    write_entity(w, dwg, bh->entities[i]->obj,
                                 abs_x, abs_y, new_sx, new_sy, new_rot,
                                 depth + 1, count_ptr);
                    expanded++;
                }
            } else if (bh->first_entity && bh->first_entity->obj
                       && bh->last_entity && bh->last_entity->obj) {
                BITCODE_RL start = bh->first_entity->obj->index;
                BITCODE_RL end   = bh->last_entity->obj->index;
                if (end >= dwg->num_objects) end = (BITCODE_RL)(dwg->num_objects - 1);
                for (BITCODE_RL i = start; i <= end; ++i) {
                    if (*count_ptr >= DWGB_MAX_ENTITIES) break;
                    const Dwg_Object *child = &dwg->object[i];
                    if (child->supertype != DWG_SUPERTYPE_ENTITY) continue;
                    write_entity(w, dwg, child,
                                 abs_x, abs_y, new_sx, new_sy, new_rot,
                                 depth + 1, count_ptr);
                    expanded++;
                }
            }
            if (expanded > 0) return;
        }
    }

    /* fallback: anonymous block 없거나 비었을 때 — def_pt→text_midpt 직선 */
    write_entity_header(w, dwg, obj, DWGB_TYPE_DIMENSION);
    double dpx = e->def_pt.x, dpy = e->def_pt.y;
    double tmpx = e->text_midpt.x, tmpy = e->text_midpt.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&dpx, &dpy, sx, sy, rot, tx, ty);
        affine_point(&tmpx, &tmpy, sx, sy, rot, tx, ty);
    }
    w_f64(w, dpx); w_f64(w, dpy);
    w_f64(w, tmpx); w_f64(w, tmpy);
    w_i32(w, 0);
    char *utf8 = tv_to_utf8(dwg, e->user_text);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
    (*count_ptr)++;
}

static void write_leader(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LEADER *e = obj->tio.entity->tio.LEADER;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LEADER);
    w_i32(w, (int32_t)e->num_points);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < e->num_points; ++i) {
        double px = e->points[i].x, py = e->points[i].y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
}

/* ---- 9a-5: HATCH ---- */

static void write_hatch(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_HATCH *e = obj->tio.entity->tio.HATCH;
    write_entity_header(w, dwg, obj, DWGB_TYPE_HATCH);
    uint8_t isSolid = e->is_solid_fill ? 1 : 0;
    w_u8(w, isSolid);

    /* Placeholder for num_paths — filled after loop */
    size_t pos_num_paths = w->len;
    w_i32(w, 0);
    int32_t actual_paths = 0;
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);

    for (BITCODE_BL p = 0; p < e->num_paths; ++p) {
        Dwg_HATCH_Path *path = &e->paths[p];
        int is_polyline = (path->flag & 2) != 0;

        /* Placeholder for num_verts */
        size_t pos_num_verts = w->len;
        w_i32(w, 0);
        int32_t nv = 0;

        if (is_polyline) {
            /* Polyline path: count is also num_segs_or_paths */
            for (BITCODE_BL v = 0; v < path->num_segs_or_paths; ++v) {
                double px = path->polyline_paths[v].point.x;
                double py = path->polyline_paths[v].point.y;
                if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
                w_f64(w, px); w_f64(w, py);
                nv++;
            }
        } else {
            /* Segment path: only emit LINE segments (curve_type == 1) */
            for (BITCODE_BL s = 0; s < path->num_segs_or_paths; ++s) {
                Dwg_HATCH_PathSeg *seg = &path->segs[s];
                if (seg->curve_type == 1) {
                    double p1x = seg->first_endpoint.x,  p1y = seg->first_endpoint.y;
                    double p2x = seg->second_endpoint.x, p2y = seg->second_endpoint.y;
                    if (do_transform) {
                        affine_point(&p1x, &p1y, sx, sy, rot, tx, ty);
                        affine_point(&p2x, &p2y, sx, sy, rot, tx, ty);
                    }
                    w_f64(w, p1x); w_f64(w, p1y);
                    w_f64(w, p2x); w_f64(w, p2y);
                    nv += 2;
                }
            }
        }
        if (!w->ok) return;
        if (nv == 0) {
            /* LINE segment가 하나도 없으면 placeholder를 rewind해서 num_paths(actual_paths)와
               stream에 남은 path 개수를 일치시킴 — 안 그러면 디코더 오프셋이 path 하나당 4byte씩 어긋남. */
            w->len = pos_num_verts;
        } else {
            memcpy(w->buf + pos_num_verts, &nv, 4);
            actual_paths++;
        }
    }
    if (!w->ok) return;
    memcpy(w->buf + pos_num_paths, &actual_paths, 4);
}

/* ---- Basic entities ---- */

/* 반환: emit한 엔티티 수(0 또는 1). 클립 활성 시 사각형으로 잘라냄. */
static int write_line(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                      double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LINE *e = obj->tio.entity->tio.LINE;
    double x1 = e->start.x, y1 = e->start.y;
    double x2 = e->end.x,   y2 = e->end.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&x1, &y1, sx, sy, rot, tx, ty);
        affine_point(&x2, &y2, sx, sy, rot, tx, ty);
    }
    if (g_clip_on && !clip_seg(&x1, &y1, &x2, &y2, g_clx0, g_cly0, g_clx1, g_cly1))
        return 0;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
    w_f64(w, x1); w_f64(w, y1); w_f64(w, x2); w_f64(w, y2);
    return 1;
}

static void write_circle(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_CIRCLE *e = obj->tio.entity->tio.CIRCLE;
    double cx = e->center.x, cy = e->center.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&cx, &cy, sx, sy, rot, tx, ty);
    }
    double scale_avg = (sx + sy) * 0.5;
    double scaled_r = e->radius * fabs(scale_avg);
    write_entity_header(w, dwg, obj, DWGB_TYPE_CIRCLE);
    w_f64(w, cx); w_f64(w, cy);
    w_f64(w, scaled_r);
}

static void write_arc(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                      double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_ARC *e = obj->tio.entity->tio.ARC;
    double cx = e->center.x, cy = e->center.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&cx, &cy, sx, sy, rot, tx, ty);
    }
    double scale_avg = (sx + sy) * 0.5;
    double scaled_r = e->radius * fabs(scale_avg);
    /* LibreDWG는 radian으로 보관 */
    double sd = e->start_angle * 180.0 / 3.14159265358979323846;
    double ed = e->end_angle   * 180.0 / 3.14159265358979323846;
    double rot_deg = rot * 180.0 / 3.14159265358979323846;
    sd += rot_deg;
    ed += rot_deg;
    write_entity_header(w, dwg, obj, DWGB_TYPE_ARC);
    w_f64(w, cx); w_f64(w, cy);
    w_f64(w, scaled_r);
    w_f64(w, sd); w_f64(w, ed);
}

/* ---- XCLIP: INSERT 의 SPATIAL_FILTER 찾기 ---- */

/* DICTIONARY 에서 key 에 해당하는 itemhandle 의 Dwg_Object* 반환 (없으면 NULL) */
static Dwg_Object *dict_find(const Dwg_Data *dwg, Dwg_Object *dict_obj, const char *key) {
    if (!dict_obj || !dict_obj->tio.object) return NULL;
    if (dict_obj->fixedtype != DWG_TYPE_DICTIONARY) return NULL;
    Dwg_Object_DICTIONARY *d = dict_obj->tio.object->tio.DICTIONARY;
    if (!d || !d->texts || !d->itemhandles) return NULL;
    for (BITCODE_BL i = 0; i < d->numitems; ++i) {
        if (!d->texts[i]) continue;
        char *t = tv_to_utf8(dwg, d->texts[i]);
        int match = (t && strcmp(t, key) == 0);
        if (t) free(t);
        if (match && d->itemhandles[i] && d->itemhandles[i]->obj)
            return d->itemhandles[i]->obj;
    }
    return NULL;
}

/* INSERT entity → xdic → ACAD_FILTER dict → SPATIAL → SPATIAL_FILTER (없으면 NULL) */
static Dwg_Object_SPATIAL_FILTER *insert_xclip(const Dwg_Data *dwg, const Dwg_Object *ins_obj) {
    if (!ins_obj || !ins_obj->tio.entity) return NULL;
    BITCODE_H xdic = ins_obj->tio.entity->xdicobjhandle;
    if (!xdic || !xdic->obj) return NULL;
    Dwg_Object *filter_dict = dict_find(dwg, xdic->obj, "ACAD_FILTER");
    if (!filter_dict) return NULL;
    Dwg_Object *sp_obj = dict_find(dwg, filter_dict, "SPATIAL");
    if (!sp_obj || sp_obj->fixedtype != DWG_TYPE_SPATIAL_FILTER) return NULL;
    if (!sp_obj->tio.object) return NULL;
    return sp_obj->tio.object->tio.SPATIAL_FILTER;
}

/* 클립 컬링용 엔티티 대표점(블록 로컬 좌표). 못 구하면 0 반환(=컬링 안 함, 보존). */
static int entity_local_repr(const Dwg_Object *obj, double *px, double *py) {
    if (!obj || !obj->tio.entity) return 0;
    switch (obj->fixedtype) {
        case DWG_TYPE_LINE:   { Dwg_Entity_LINE   *e=obj->tio.entity->tio.LINE;   *px=e->start.x;  *py=e->start.y;  return 1; }
        case DWG_TYPE_CIRCLE: { Dwg_Entity_CIRCLE *e=obj->tio.entity->tio.CIRCLE; *px=e->center.x; *py=e->center.y; return 1; }
        case DWG_TYPE_ARC:    { Dwg_Entity_ARC    *e=obj->tio.entity->tio.ARC;    *px=e->center.x; *py=e->center.y; return 1; }
        case DWG_TYPE_ELLIPSE:{ Dwg_Entity_ELLIPSE*e=obj->tio.entity->tio.ELLIPSE;*px=e->center.x; *py=e->center.y; return 1; }
        case DWG_TYPE_TEXT:   { Dwg_Entity_TEXT   *e=obj->tio.entity->tio.TEXT;   *px=e->ins_pt.x; *py=e->ins_pt.y; return 1; }
        case DWG_TYPE_MTEXT:  { Dwg_Entity_MTEXT  *e=obj->tio.entity->tio.MTEXT;  *px=e->ins_pt.x; *py=e->ins_pt.y; return 1; }
        case DWG_TYPE_INSERT: { Dwg_Entity_INSERT *e=obj->tio.entity->tio.INSERT; *px=e->ins_pt.x; *py=e->ins_pt.y; return 1; }
        case DWG_TYPE__3DFACE:{ Dwg_Entity__3DFACE*e=obj->tio.entity->tio._3DFACE;*px=e->corner1.x;*py=e->corner1.y;return 1; }
        case DWG_TYPE_SOLID:  { Dwg_Entity_SOLID  *e=obj->tio.entity->tio.SOLID;  *px=e->corner1.x;*py=e->corner1.y;return 1; }
        case DWG_TYPE_LWPOLYLINE: {
            Dwg_Entity_LWPOLYLINE *e=obj->tio.entity->tio.LWPOLYLINE;
            if (e->num_points > 0 && e->points) { *px=e->points[0].x; *py=e->points[0].y; return 1; }
            return 0;
        }
        case DWG_TYPE_SPLINE: {
            Dwg_Entity_SPLINE *e=obj->tio.entity->tio.SPLINE;
            if (e->num_ctrl_pts > 0 && e->ctrl_pts) { *px=e->ctrl_pts[0].x; *py=e->ctrl_pts[0].y; return 1; }
            return 0;
        }
        case DWG_TYPE_LEADER: {
            Dwg_Entity_LEADER *e=obj->tio.entity->tio.LEADER;
            if (e->num_points > 0 && e->points) { *px=e->points[0].x; *py=e->points[0].y; return 1; }
            return 0;
        }
        case DWG_TYPE_DIMENSION_ALIGNED:
        case DWG_TYPE_DIMENSION_LINEAR:
        case DWG_TYPE_DIMENSION_ANG3PT:
        case DWG_TYPE_DIMENSION_ANG2LN:
        case DWG_TYPE_DIMENSION_RADIUS:
        case DWG_TYPE_DIMENSION_DIAMETER:
        case DWG_TYPE_DIMENSION_ORDINATE: {
            /* 모든 DIMENSION_* 는 DIMENSION_COMMON 공유 → ALIGNED 캐스팅. def_pt 사용. */
            Dwg_Entity_DIMENSION_ALIGNED *e=obj->tio.entity->tio.DIMENSION_ALIGNED;
            *px=e->def_pt.x; *py=e->def_pt.y; return 1;
        }
        case DWG_TYPE_HATCH: {
            Dwg_Entity_HATCH *e=obj->tio.entity->tio.HATCH;
            if (e->num_paths > 0 && e->paths) {
                Dwg_HATCH_Path *p = &e->paths[0];
                if ((p->flag & 2) && p->num_segs_or_paths > 0 && p->polyline_paths) {
                    *px=p->polyline_paths[0].point.x; *py=p->polyline_paths[0].point.y; return 1;
                } else if (p->num_segs_or_paths > 0 && p->segs) {
                    *px=p->segs[0].first_endpoint.x; *py=p->segs[0].first_endpoint.y; return 1;
                }
            }
            return 0;
        }
        default: return 0;
    }
}

/* HATCH 전체 bbox(블록 로컬). 솔리드 채움이 클립 밖으로 길게 뻗어 회색 띠로 겹치는 문제를
 * 막기 위해 대표점이 아닌 bbox 로 판정한다. 구하면 1. */
static int entity_local_bbox(const Dwg_Object *obj,
                             double *minx, double *miny, double *maxx, double *maxy) {
    if (!obj || !obj->tio.entity) return 0;
    if (obj->fixedtype != DWG_TYPE_HATCH) return 0;
    Dwg_Entity_HATCH *e = obj->tio.entity->tio.HATCH;
    if (e->num_paths == 0 || !e->paths) return 0;
    double a = 1e18, b = 1e18, c = -1e18, d = -1e18; int found = 0;
    for (BITCODE_BL pi = 0; pi < e->num_paths; ++pi) {
        Dwg_HATCH_Path *p = &e->paths[pi];
        if ((p->flag & 2) && p->polyline_paths) {
            for (BITCODE_BL v = 0; v < p->num_segs_or_paths; ++v) {
                double x = p->polyline_paths[v].point.x, y = p->polyline_paths[v].point.y;
                if (x<a)a=x; if (y<b)b=y; if (x>c)c=x; if (y>d)d=y; found=1;
            }
        } else if (p->segs) {
            for (BITCODE_BL s = 0; s < p->num_segs_or_paths; ++s) {
                if (p->segs[s].curve_type != 1) continue;
                double x1=p->segs[s].first_endpoint.x,  y1=p->segs[s].first_endpoint.y;
                double x2=p->segs[s].second_endpoint.x, y2=p->segs[s].second_endpoint.y;
                if (x1<a)a=x1; if (y1<b)b=y1; if (x1>c)c=x1; if (y1>d)d=y1;
                if (x2<a)a=x2; if (y2<b)b=y2; if (x2>c)c=x2; if (y2>d)d=y2; found=1;
            }
        }
    }
    if (!found) return 0;
    *minx=a; *miny=b; *maxx=c; *maxy=d; return 1;
}


/* ---- 9b: INSERT 재귀 전개 ---- */

static void write_insert(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (depth >= DWGB_MAX_INSERT_DEPTH) return;
    Dwg_Entity_INSERT *e = obj->tio.entity->tio.INSERT;
    if (!e->block_header || !e->block_header->obj) return;
    Dwg_Object_BLOCK_HEADER *bh = e->block_header->obj->tio.object->tio.BLOCK_HEADER;
    if (!bh) return;

    /* INSERT 자체의 변환: parent 변환과 합성.
     * DWG 규칙: child_world = ins_pt + Rot·Scale·(child_local − base_pt).
     * affine_point 은 P'=Rot·Scale·P+(tx,ty) 이므로, 자식에 넘길 평행이동은
     *   T = ins_pt_world − Rot(new_rot)·Scale(new_s)·base_pt
     * 가 되어야 한다. base_pt 를 빼지 않으면 "기존 지오메트리를 base_pt 위치에서
     * 캡처한" 블록(상세도/단면 등)이 원래 절대좌표 근처에 그대로 쌓여 떡덩어리가 된다. */
    double ix = e->ins_pt.x, iy = e->ins_pt.y;
    affine_point(&ix, &iy, sx, sy, rot, tx, ty);
    double new_sx = sx * e->scale.x;
    double new_sy = sy * e->scale.y;
    double new_rot = rot + e->rotation;

    /* base_pt 보정: Rot·Scale 적용한 base_pt 를 평행이동에서 차감 */
    double bpx = bh->base_pt.x, bpy = bh->base_pt.y;
    affine_point(&bpx, &bpy, new_sx, new_sy, new_rot, 0.0, 0.0);
    double child_tx = ix - bpx;
    double child_ty = iy - bpy;

    /* XCLIP: 이 INSERT 에 SPATIAL_FILTER 가 있으면 클립 경계를 world bbox 로 계산.
     * 모델(실측): clip_world = childTransform( invXform2D(clip_vert) ).
     *   invXform2D: 블록 raw = inv·clip_vert (clip공간→블록 정의공간)
     *   childTransform: 블록 raw → world (자식 엔티티와 동일 변환)
     * num_clip_verts==2 = 직사각형(두 코너), >2 = 폴리곤(MVP: bbox 사용). */
    int    has_clip = 0;
    double clminx = 1e18, clminy = 1e18, clmaxx = -1e18, clmaxy = -1e18;
    {
        Dwg_Object_SPATIAL_FILTER *sf = insert_xclip(dwg, obj);
        if (sf && sf->num_clip_verts >= 2 && sf->clip_verts) {
            const double *iv = sf->inverse_transform;
            int m = (int)sf->num_clip_verts;
            /* 코너 목록: 직사각형이면 두 점으로 4코너 구성 */
            double cxs[4], cys[4]; int ncorn;
            if (m == 2) {
                double ax = sf->clip_verts[0].x, ay = sf->clip_verts[0].y;
                double bx = sf->clip_verts[1].x, by = sf->clip_verts[1].y;
                cxs[0]=ax; cys[0]=ay; cxs[1]=bx; cys[1]=ay;
                cxs[2]=bx; cys[2]=by; cxs[3]=ax; cys[3]=by; ncorn=4;
            } else {
                ncorn = 0; /* 폴리곤: 아래서 직접 순회 */
            }
            /* 폴리곤이면 전체 vert, 직사각형이면 4코너를 world bbox 로 */
            int total = (m == 2) ? ncorn : m;
            for (int k = 0; k < total; ++k) {
                double cx = (m == 2) ? cxs[k] : sf->clip_verts[k].x;
                double cy = (m == 2) ? cys[k] : sf->clip_verts[k].y;
                double bx, by;
                if (iv) { bx = iv[0]*cx + iv[1]*cy + iv[3];
                          by = iv[4]*cx + iv[5]*cy + iv[7]; }
                else    { bx = cx; by = cy; }
                affine_point(&bx, &by, new_sx, new_sy, new_rot, child_tx, child_ty);
                if (bx < clminx) clminx = bx; if (by < clminy) clminy = by;
                if (bx > clmaxx) clmaxx = bx; if (by > clmaxy) clmaxy = by;
            }
            if (clminx <= clmaxx) has_clip = 1;
        }
    }

    /* 이 INSERT 의 클립을 부모 클립과 교집합하여 g_clip 설정.
     * 자식 컬링/기하 클리핑은 write_entity 와 leaf writer 가 g_clip 으로 처리. */
    int    saved_on = g_clip_on;
    double svx0 = g_clx0, svy0 = g_cly0, svx1 = g_clx1, svy1 = g_cly1;
    if (has_clip) {
        if (g_clip_on) {
            if (clminx > g_clx0) g_clx0 = clminx;
            if (clminy > g_cly0) g_cly0 = clminy;
            if (clmaxx < g_clx1) g_clx1 = clmaxx;
            if (clmaxy < g_cly1) g_cly1 = clmaxy;
        } else {
            g_clx0 = clminx; g_cly0 = clminy; g_clx1 = clmaxx; g_cly1 = clmaxy;
            g_clip_on = 1;
        }
    }

    /* BLOCK_HEADER의 자식 엔티티 순회 */
    if (bh->entities && bh->num_owned > 0) {
        for (BITCODE_BL i = 0; i < bh->num_owned; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            if (!bh->entities[i] || !bh->entities[i]->obj) continue;
            write_entity(w, dwg, bh->entities[i]->obj,
                         child_tx, child_ty, new_sx, new_sy, new_rot, depth + 1, count_ptr);
        }
    } else if (bh->first_entity && bh->first_entity->obj
               && bh->last_entity && bh->last_entity->obj) {
        /* 폴백: object 인덱스 범위로 순회 */
        BITCODE_RL start = bh->first_entity->obj->index;
        BITCODE_RL end   = bh->last_entity->obj->index;
        if (end >= dwg->num_objects) end = (BITCODE_RL)(dwg->num_objects - 1);
        for (BITCODE_RL i = start; i <= end; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            const Dwg_Object *child = &dwg->object[i];
            if (child->supertype != DWG_SUPERTYPE_ENTITY) continue;
            write_entity(w, dwg, child, child_tx, child_ty, new_sx, new_sy, new_rot,
                         depth + 1, count_ptr);
        }
    }

    /* g_clip 복원 */
    g_clip_on = saved_on;
    g_clx0 = svx0; g_cly0 = svy0; g_clx1 = svx1; g_cly1 = svy1;
}

static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (*count_ptr >= DWGB_MAX_ENTITIES) return;

    /* XCLIP 활성 시 클립 처리:
     *  - LINE/LWPOLYLINE/POLYLINE/INSERT/DIMENSION: 통과(leaf writer 가 잘라내거나 재귀가 처리)
     *  - HATCH: world bbox 가 클립 밖 또는 클립크기 이상 overflow 면 컬링(회색 띠 방지)
     *  - 소형 타입(원/호/텍스트 등): 대표점이 클립 밖이면 컬링 */
    if (g_clip_on) {
        switch (obj->fixedtype) {
            case DWG_TYPE_LINE:
            case DWG_TYPE_LWPOLYLINE:
            case DWG_TYPE_POLYLINE_2D:
            case DWG_TYPE_POLYLINE_3D:
            case DWG_TYPE_INSERT:
            case DWG_TYPE_DIMENSION_ALIGNED:
            case DWG_TYPE_DIMENSION_LINEAR:
            case DWG_TYPE_DIMENSION_ANG3PT:
            case DWG_TYPE_DIMENSION_ANG2LN:
            case DWG_TYPE_DIMENSION_RADIUS:
            case DWG_TYPE_DIMENSION_DIAMETER:
            case DWG_TYPE_DIMENSION_ORDINATE:
                break;
            case DWG_TYPE_HATCH: {
                double lminx, lminy, lmaxx, lmaxy;
                if (entity_local_bbox(obj, &lminx, &lminy, &lmaxx, &lmaxy)) {
                    double xs[4] = {lminx, lmaxx, lmaxx, lminx};
                    double ys[4] = {lminy, lminy, lmaxy, lmaxy};
                    double wmnx=1e18, wmny=1e18, wmxx=-1e18, wmxy=-1e18;
                    for (int k = 0; k < 4; ++k) {
                        double px = xs[k], py = ys[k];
                        affine_point(&px, &py, sx, sy, rot, tx, ty);
                        if (px<wmnx)wmnx=px; if (py<wmny)wmny=py;
                        if (px>wmxx)wmxx=px; if (py>wmxy)wmxy=py;
                    }
                    double cw = g_clx1-g_clx0, ch = g_cly1-g_cly0;
                    int outside  = (wmxx<g_clx0||wmnx>g_clx1||wmxy<g_cly0||wmny>g_cly1);
                    int overflow = (wmnx<g_clx0-cw||wmxx>g_clx1+cw||wmny<g_cly0-ch||wmxy>g_cly1+ch);
                    if (outside || overflow) return;
                }
                break;
            }
            default: {
                double rpx, rpy;
                if (entity_local_repr(obj, &rpx, &rpy)) {
                    affine_point(&rpx, &rpy, sx, sy, rot, tx, ty);
                    if (rpx<g_clx0||rpx>g_clx1||rpy<g_cly0||rpy>g_cly1) return;
                }
            }
        }
    }

    switch (obj->fixedtype) {
        case DWG_TYPE_LINE:
            *count_ptr += write_line(w, dwg, obj, tx, ty, sx, sy, rot); break;
        case DWG_TYPE_CIRCLE:
            write_circle(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_ARC:
            write_arc(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_LWPOLYLINE:
            *count_ptr += write_lwpolyline(w, dwg, obj, tx, ty, sx, sy, rot); break;
        case DWG_TYPE_POLYLINE_2D:
            write_polyline_2d(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_POLYLINE_3D:
            write_polyline_3d(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_TEXT:
            write_text(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_MTEXT:
            write_mtext(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE__3DFACE:
            write_3dface(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_SOLID:
            write_solid(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_ELLIPSE:
            write_ellipse(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_SPLINE:
            write_spline(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_HATCH:
            write_hatch(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_LEADER:
            write_leader(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_DIMENSION_ALIGNED:
        case DWG_TYPE_DIMENSION_LINEAR:
        case DWG_TYPE_DIMENSION_ANG3PT:
        case DWG_TYPE_DIMENSION_ANG2LN:
        case DWG_TYPE_DIMENSION_RADIUS:
        case DWG_TYPE_DIMENSION_DIAMETER:
        case DWG_TYPE_DIMENSION_ORDINATE:
            write_dimension(w, dwg, obj, tx, ty, sx, sy, rot, depth, count_ptr); break;
        case DWG_TYPE_INSERT:
            write_insert(w, dwg, obj, tx, ty, sx, sy, rot, depth, count_ptr); break;
        default: break;
    }
}

/* ---- 메인 진입점 ---- */

uint8_t *dwgb_serialize(const Dwg_Data *dwg, size_t *out_len) {
    Writer w;
    memset(&w, 0, sizeof(Writer));
    w.ok = 1;

    /* 레이어 캐시: 직렬화 동안만 유효 */
    layer_cache_init(&g_layer_cache, dwg);

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

    /* Entities — model-space block의 자식만 순회.
     * 블록 정의 내부 엔티티들은 INSERT 전개를 통해서만 도달.
     * Paper-space는 일단 무시 (도면 인쇄 레이아웃은 모바일 뷰어에서 불필요). */
    int n_entities = 0;
    size_t pos_num_entities_final = pos_num_entities;
    Dwg_Object_BLOCK_HEADER *mspace = NULL;
    if (dwg->header_vars.BLOCK_RECORD_MSPACE
        && dwg->header_vars.BLOCK_RECORD_MSPACE->obj) {
        mspace = dwg->header_vars.BLOCK_RECORD_MSPACE->obj->tio.object->tio.BLOCK_HEADER;
    }
    if (mspace && mspace->entities && mspace->num_owned > 0) {
        for (BITCODE_BL i = 0; i < mspace->num_owned; ++i) {
            if (n_entities >= DWGB_MAX_ENTITIES || !w.ok) break;
            if (!mspace->entities[i] || !mspace->entities[i]->obj) continue;
            write_entity(&w, dwg, mspace->entities[i]->obj,
                         0.0, 0.0, 1.0, 1.0, 0.0, 0, &n_entities);
        }
    } else if (mspace && mspace->first_entity && mspace->first_entity->obj
               && mspace->last_entity && mspace->last_entity->obj) {
        /* 폴백: object 인덱스 범위로 순회 */
        BITCODE_RL start = mspace->first_entity->obj->index;
        BITCODE_RL end   = mspace->last_entity->obj->index;
        if (end >= dwg->num_objects) end = (BITCODE_RL)(dwg->num_objects - 1);
        for (BITCODE_RL i = start; i <= end; ++i) {
            if (n_entities >= DWGB_MAX_ENTITIES || !w.ok) break;
            const Dwg_Object *obj = &dwg->object[i];
            if (obj->supertype != DWG_SUPERTYPE_ENTITY) continue;
            write_entity(&w, dwg, obj, 0.0, 0.0, 1.0, 1.0, 0.0, 0, &n_entities);
        }
    }
    if (w.ok) {
        memcpy(w.buf + pos_num_entities_final, &n_entities, 4);
    }

    if (!w.ok) {
        layer_cache_free(&g_layer_cache);
        free(w.buf);
        return NULL;
    }
    layer_cache_free(&g_layer_cache);
    *out_len = w.len;
    return w.buf;
}
