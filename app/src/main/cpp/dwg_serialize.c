#include "dwg_serialize.h"
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DWGB_MAX_INSERT_DEPTH 5
#define DWGB_MAX_ENTITIES 500000
#define DWGB_PI 3.14159265358979323846
#define HATCH_ARC_MIN_SEGS 8
#define HATCH_ARC_MAX_SEGS 64
#define HATCH_MAX_FILL_SEGMENTS 4000     /* hatch당 채움 세그먼트 상한 → 초과 시 솔리드 폴백 */
#define HATCH_MAX_FAMILY_LINES 20000     /* defline당 평행선 상한 → 초과 시 솔리드 폴백 */

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

/* ---- XCLIP: 해치 경계 루프 클리핑 (Sutherland–Hodgman, 축정렬 사각형) ----
 * 부분 침범 해치의 채움/패턴이 클립창 밖(표제란 등)으로 번지는 것 방지 (이슈2). */

/* 반평면 1개로 닫힌 루프 클립. axis: 0=x, 1=y. keep_ge: 1이면 좌표>=lim 유지, 0이면 <=lim.
 * in(2n doubles) → out(용량 ≥ 4n+8 doubles, 호출자 할당). 반환: 출력 점 수(≤2n). */
static int clip_loop_halfplane(const double *in, int n, double *out,
                               int axis, int keep_ge, double lim) {
    int m = 0;
    for (int i = 0; i < n; ++i) {
        int j = (i + 1) % n;
        double cx = in[2*i], cy = in[2*i+1];
        double nx = in[2*j], ny = in[2*j+1];
        double cv = axis ? cy : cx;
        double nv = axis ? ny : nx;
        int cin = keep_ge ? (cv >= lim) : (cv <= lim);
        int nin = keep_ge ? (nv >= lim) : (nv <= lim);
        if (cin) { out[2*m] = cx; out[2*m+1] = cy; ++m; }
        if (cin != nin) {                        /* 경계 교차점 추가 (cv!=nv 보장) */
            double t = (lim - cv) / (nv - cv);
            if (axis) { out[2*m] = cx + t * (nx - cx); out[2*m+1] = lim; }
            else      { out[2*m] = lim;                out[2*m+1] = cy + t * (ny - cy); }
            ++m;
        }
    }
    return m;
}

/* 닫힌 루프(*pts_io: 2*(*np_io) doubles, world)를 [x0,x1]×[y0,y1]로 클립.
 * 클립 결과로 버퍼 교체(원본 free). 결과 <3점이면 루프 드롭(*pts_io=NULL, *np_io=0).
 * malloc 실패 시 진행된 만큼만 적용(안전한 보수적 폴백). */
static void clip_loop_to_rect(double **pts_io, int *np_io,
                              double x0, double y0, double x1, double y1) {
    double *pts = *pts_io;
    int n = *np_io;
    if (!pts || n < 3) return;

    /* 빠른 경로: 루프 bbox가 클립창에 완전 포함이면 무변경 */
    double mnx = 1e18, mny = 1e18, mxx = -1e18, mxy = -1e18;
    for (int i = 0; i < n; ++i) {
        double px = pts[2*i], py = pts[2*i+1];
        if (px < mnx) mnx = px; if (px > mxx) mxx = px;
        if (py < mny) mny = py; if (py > mxy) mxy = py;
    }
    if (mnx >= x0 && mxx <= x1 && mny >= y0 && mxy <= y1) return;

    const int    axes[4]  = {0, 0, 1, 1};
    const int    keeps[4] = {1, 0, 1, 0};
    const double lims[4]  = {x0, x1, y0, y1};
    double *cur = pts; int cn = n;
    for (int p = 0; p < 4 && cn >= 3; ++p) {
        double *nb = (double *)malloc(sizeof(double) * (size_t)(4 * cn + 8));
        if (!nb) break;
        int m = clip_loop_halfplane(cur, cn, nb, axes[p], keeps[p], lims[p]);
        if (cur != pts) free(cur);
        cur = nb; cn = m;
    }
    if (cn < 3) {                                /* 완전 밖/퇴화 → 루프 드롭 */
        if (cur != pts) free(cur);
        free(pts);
        *pts_io = NULL; *np_io = 0;
        return;
    }
    if (cur == pts) return;                      /* 클립 패스 미수행(메모리 부족) */
    free(pts);
    *pts_io = cur; *np_io = cn;
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
    char *r;
    if (dwg->header.version >= R_2007) {
        /* R2007+(AC1021+): 텍스트가 UTF-16(TU)로 저장됨 → bit_convert_TU.
         * 이전엔 모든 버전에 bit_TV_to_utf8(codepage)를 써서 신버전 한글이 깨졌다. */
        r = bit_convert_TU((BITCODE_TU)tv);
    } else {
        /* ~R2004: 도면 코드페이지(CP949 등) 인코딩 → bit_TV_to_utf8 */
        r = bit_TV_to_utf8(tv, dwg->header.codepage);
    }
    if (!r) return NULL;
    if (r == (char *)tv) {
        /* 변환 함수가 src를 그대로 반환 → free() 안전을 위해 복사 */
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
    /* DXF 72/73 정렬이 있으면 기준점은 alignment_pt(DXF 11), 아니면 ins_pt(DXF 10) */
    int h72 = (int)e->horiz_alignment, v73 = (int)e->vert_alignment;
    double px = (h72 != 0 || v73 != 0) ? e->alignment_pt.x : e->ins_pt.x;
    double py = (h72 != 0 || v73 != 0) ? e->alignment_pt.y : e->ins_pt.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&px, &py, sx, sy, rot, tx, ty);
    }
    w_f64(w, px); w_f64(w, py);
    double scale_avg = (sx + sy) * 0.5;
    w_f64(w, e->height * fabs(scale_avg));
    double rot_deg = e->rotation * 180.0 / 3.14159265358979323846;
    double final_rot = rot_deg + (rot * 180.0 / 3.14159265358979323846);
    w_f64(w, final_rot);
    /* 정렬: halign 0=left 1=center 2=right ; valign 0=baseline 1=bottom 2=middle 3=top */
    int t_halign = (h72 == 1 || h72 == 4) ? 1 : (h72 == 2) ? 2 : 0;
    int t_valign = (v73 >= 1 && v73 <= 3) ? v73 : 0;
    w_i32(w, t_halign); w_i32(w, t_valign);
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
    /* attachment(DXF71, 1-9) → halign/valign. 디멘션 텍스트는 보통 5(중앙-가운데). */
    int att = (int)e->attachment; if (att < 1 || att > 9) att = 1;
    int m_col = (att - 1) % 3;                 /* 0 left 1 center 2 right */
    int m_row = (att - 1) / 3;                 /* 0 top 1 middle 2 bottom */
    int m_halign = m_col;
    int m_valign = (m_row == 0) ? 3 : (m_row == 1) ? 2 : 1;  /* top→3 middle→2 bottom→1 */
    w_i32(w, m_halign); w_i32(w, m_valign);
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

/* ---- 9a-4a: IMAGE / WIPEOUT / OLE2FRAME 프레임 ----
 * 래스터 이미지·OLE 객체 자체는 렌더 못 하므로 경계 프레임만 닫힌 LWPOLYLINE 으로
 * 내보낸다 (ZWCAD 도 미해상 이미지를 프레임+경로로 표시). 좌표는 affine 변환 적용. */
static void emit_quad_frame(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                            const double *xs, const double *ys,
                            double tx, double ty, double sx, double sy, double rot) {
    write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
    w_u8(w, 1);          /* closed */
    w_i32(w, 4);
    for (int k = 0; k < 4; ++k) {
        double px = xs[k], py = ys[k];
        affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
}

/* IMAGE/WIPEOUT 공통: pt0 + uvec*W + vvec*H 로 4코너 프레임. (둘은 필드 레이아웃 동일) */
static void write_raster_frame(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                               double p0x, double p0y, double uvx, double uvy,
                               double vvx, double vvy, double iw, double ih,
                               double tx, double ty, double sx, double sy, double rot) {
    double xs[4] = { p0x, p0x + uvx*iw, p0x + uvx*iw + vvx*ih, p0x + vvx*ih };
    double ys[4] = { p0y, p0y + uvy*iw, p0y + uvy*iw + vvy*ih, p0y + vvy*ih };
    emit_quad_frame(w, dwg, obj, xs, ys, tx, ty, sx, sy, rot);
}

/* ---- 9a-4b: MULTILEADER (MLEADER) ----
 * 현대식 다중 지시선. 기하는 ctx(AnnotContext)에 들어있다.
 * 디코더 변경 없이: 리더 선 → LWPOLYLINE, dogleg(랜딩선) → LINE, 텍스트 content → MTEXT
 * 로 분해해 기존 레코드 포맷으로 내보낸다. 색상/레이어는 MLEADER 엔티티 헤더에서 가져온다. */
static void write_multileader(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                              double tx, double ty, double sx, double sy, double rot,
                              int *count_ptr) {
    Dwg_Entity_MULTILEADER *e = obj->tio.entity->tio.MULTILEADER;
    if (!e) return;
    Dwg_MLEADER_AnnotContext *ctx = &e->ctx;
    double scale_avg = (sx + sy) * 0.5;
    const double DEG = 180.0 / 3.14159265358979323846;

    /* 1) 리더 선들: 각 node의 각 line의 points → 열린 LWPOLYLINE */
    for (BITCODE_BL li = 0; li < ctx->num_leaders && w->ok; ++li) {
        Dwg_LEADER_Node *node = &ctx->leaders[li];
        if (!node) continue;
        for (BITCODE_BL ji = 0; ji < node->num_lines && w->ok; ++ji) {
            Dwg_LEADER_Line *line = &node->lines[ji];
            if (!line || !line->points || line->num_points < 2) continue;
            if (*count_ptr >= DWGB_MAX_ENTITIES) return;
            write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
            w_u8(w, 0);                                /* open */
            w_i32(w, (int32_t)line->num_points);
            for (BITCODE_BL pi = 0; pi < line->num_points; ++pi) {
                double px = line->points[pi].x, py = line->points[pi].y;
                affine_point(&px, &py, sx, sy, rot, tx, ty);
                w_f64(w, px); w_f64(w, py);
            }
            (*count_ptr)++;
        }
        /* dogleg(랜딩선): lastleaderlinepoint → +dogleg_vector*dogleg_length */
        if (node->has_dogleg && node->dogleg_length != 0.0
            && *count_ptr < DWGB_MAX_ENTITIES && w->ok) {
            double ax = node->lastleaderlinepoint.x, ay = node->lastleaderlinepoint.y;
            double bx = ax + node->dogleg_vector.x * node->dogleg_length;
            double by = ay + node->dogleg_vector.y * node->dogleg_length;
            affine_point(&ax, &ay, sx, sy, rot, tx, ty);
            affine_point(&bx, &by, sx, sy, rot, tx, ty);
            write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
            w_f64(w, ax); w_f64(w, ay); w_f64(w, bx); w_f64(w, by);
            (*count_ptr)++;
        }
    }

    /* 2) 텍스트 content → MTEXT */
    if (ctx->has_content_txt && ctx->content.txt.default_text
        && *count_ptr < DWGB_MAX_ENTITIES && w->ok) {
        char *utf8 = tv_to_utf8(dwg, ctx->content.txt.default_text);
        if (utf8 && utf8[0]) {
            double px = ctx->content.txt.location.x;
            double py = ctx->content.txt.location.y;
            affine_point(&px, &py, sx, sy, rot, tx, ty);
            double h = ctx->text_height;
            if (h <= 0.0) h = ctx->content.txt.height;
            if (h <= 0.0) h = 2.5;                     /* 최후 폴백 */
            double rdeg = ctx->content.txt.rotation * DEG + rot * DEG;
            write_entity_header(w, dwg, obj, DWGB_TYPE_MTEXT);
            w_f64(w, px); w_f64(w, py);
            w_f64(w, h * fabs(scale_avg));
            w_f64(w, rdeg);
            w_i32(w, 0); w_i32(w, 0);   /* 정렬: 좌측+베이스라인 (MLEADER 현 동작 유지) */
            w_string_utf8(w, utf8);
            (*count_ptr)++;
        }
        free(utf8);
    }
}

/* ---- HATCH 패턴 채움용 동적 double 버퍼 + 헬퍼 ---- */

typedef struct { double *v; int n; int cap; } DBuf;

static void dbuf_init(DBuf *b) { b->v = NULL; b->n = 0; b->cap = 0; }
static void dbuf_free(DBuf *b) { free(b->v); b->v = NULL; b->n = 0; b->cap = 0; }
static int  dbuf_push(DBuf *b, double x) {
    if (b->n >= b->cap) {
        int nc = b->cap ? b->cap * 2 : 64;
        double *nv = (double *)realloc(b->v, (size_t)nc * sizeof(double));
        if (!nv) return 0;
        b->v = nv; b->cap = nc;
    }
    b->v[b->n++] = x; return 1;
}
static void dbuf_push_xform(DBuf *b, double x, double y,
                            double sx, double sy, double rot, double tx, double ty) {
    affine_point(&x, &y, sx, sy, rot, tx, ty);
    dbuf_push(b, x); dbuf_push(b, y);
}

/* 원호를 짧은 선분으로 분할해 점들을 push (시작점 제외, 끝점 포함). 로컬좌표로 샘플 후 변환. */
static void emit_arc(DBuf *b, double cx, double cy, double r,
                     double a0, double a1, int ccw,
                     double sx, double sy, double rot, double tx, double ty) {
    double sweep = a1 - a0;
    if (ccw)  { while (sweep < 0) sweep += 2.0 * DWGB_PI; }
    else      { while (sweep > 0) sweep -= 2.0 * DWGB_PI; }
    double aSweep = fabs(sweep);
    int segs = (int)ceil(aSweep / (DWGB_PI / 16.0));   /* ~11.25° 간격 */
    if (segs < HATCH_ARC_MIN_SEGS) segs = HATCH_ARC_MIN_SEGS;
    if (segs > HATCH_ARC_MAX_SEGS) segs = HATCH_ARC_MAX_SEGS;
    for (int i = 1; i <= segs; ++i) {
        double a = a0 + sweep * ((double)i / (double)segs);
        dbuf_push_xform(b, cx + r * cos(a), cy + r * sin(a), sx, sy, rot, tx, ty);
    }
}

/* polyline bulge 구간을 원호로 분할해 push (시작점 제외, 끝점 포함). */
static void emit_bulge(DBuf *b, double x0, double y0, double x1, double y1, double bulge,
                       double sx, double sy, double rot, double tx, double ty) {
    if (fabs(bulge) < 1e-9) { dbuf_push_xform(b, x1, y1, sx, sy, rot, tx, ty); return; }
    double theta = 4.0 * atan(bulge);
    double half = theta / 2.0, s = sin(half);
    if (fabs(s) < 1e-9) { dbuf_push_xform(b, x1, y1, sx, sy, rot, tx, ty); return; }
    double cot = cos(half) / s;
    double cx = (x0 + x1) / 2.0 - (y1 - y0) / 2.0 * cot;
    double cy = (y0 + y1) / 2.0 + (x1 - x0) / 2.0 * cot;
    double r  = sqrt((x0 - cx) * (x0 - cx) + (y0 - cy) * (y0 - cy));
    double a0 = atan2(y0 - cy, x0 - cx);
    double a1 = atan2(y1 - cy, x1 - cx);
    emit_arc(b, cx, cy, r, a0, a1, (bulge > 0) ? 1 : 0, sx, sy, rot, tx, ty);
}

/* 경계 path 하나를 변환된 월드 점열(out)로 빌드. 타원호/스플라인(curve_type 3,4)을
 * 만나면 코드(chord)로 근사하고 *unsupported=1 (호출자가 patternFallback 처리). */
static void build_one_loop(DBuf *out, Dwg_HATCH_Path *path, int *unsupported,
                           double sx, double sy, double rot, double tx, double ty) {
    if (path->flag & 2) {                       /* polyline path */
        BITCODE_BL nv = path->num_segs_or_paths;
        if (nv == 0 || !path->polyline_paths) return;
        dbuf_push_xform(out, path->polyline_paths[0].point.x,
                             path->polyline_paths[0].point.y, sx, sy, rot, tx, ty);
        BITCODE_BL lim = path->closed ? nv : (nv > 0 ? nv - 1 : 0);
        for (BITCODE_BL i = 0; i < lim; ++i) {
            BITCODE_BL j = (i + 1) % nv;
            double ax = path->polyline_paths[i].point.x, ay = path->polyline_paths[i].point.y;
            double bx = path->polyline_paths[j].point.x, by = path->polyline_paths[j].point.y;
            double bl = path->bulges_present ? path->polyline_paths[i].bulge : 0.0;
            if (fabs(bl) > 1e-9) emit_bulge(out, ax, ay, bx, by, bl, sx, sy, rot, tx, ty);
            else                 dbuf_push_xform(out, bx, by, sx, sy, rot, tx, ty);
        }
    } else {                                     /* segment path */
        if (!path->segs) return;
        for (BITCODE_BL s = 0; s < path->num_segs_or_paths; ++s) {
            Dwg_HATCH_PathSeg *sg = &path->segs[s];
            if (sg->curve_type == 1) {           /* LINE */
                if (s == 0)
                    dbuf_push_xform(out, sg->first_endpoint.x, sg->first_endpoint.y,
                                    sx, sy, rot, tx, ty);
                dbuf_push_xform(out, sg->second_endpoint.x, sg->second_endpoint.y,
                                sx, sy, rot, tx, ty);
            } else if (sg->curve_type == 2) {    /* CIRCULAR ARC */
                /* DWG hatch boundary 의 CW(!is_ccw) arc 는 각도가 Y-mirror 되어 저장됨(실측):
                 * 각도를 negate 해야 인접 세그먼트와 연속(안 그러면 reflex sweep → 반지름이
                 * 도면급인 거대 원이 그려져 회색 덩어리/왜곡). is_ccw arc 는 그대로 사용. */
                int cc = sg->is_ccw ? 1 : 0;
                double aa0 = sg->start_angle, aa1 = sg->end_angle;
                if (!cc) { aa0 = -aa0; aa1 = -aa1; }
                if (s == 0)
                    dbuf_push_xform(out, sg->center.x + sg->radius * cos(aa0),
                                         sg->center.y + sg->radius * sin(aa0),
                                    sx, sy, rot, tx, ty);
                emit_arc(out, sg->center.x, sg->center.y, sg->radius,
                         aa0, aa1, cc, sx, sy, rot, tx, ty);
            } else {                              /* ELLIPTICAL ARC / SPLINE → chord 근사 */
                *unsupported = 1;
                /* LibreDWG 가 first/second_endpoint 를 (0,0)으로 미설정하는 경우가 있어,
                 * 그대로 push 하면 경계가 원점까지 늘어나 bbox 가 폭주(원점~도면 전체).
                 * 퇴화(≈0,0) endpoint 는 skip 해 폭주를 막는다. */
                double e1x = sg->first_endpoint.x,  e1y = sg->first_endpoint.y;
                double e2x = sg->second_endpoint.x, e2y = sg->second_endpoint.y;
                if (s == 0 && (fabs(e1x) > 1e-6 || fabs(e1y) > 1e-6))
                    dbuf_push_xform(out, e1x, e1y, sx, sy, rot, tx, ty);
                if (fabs(e2x) > 1e-6 || fabs(e2y) > 1e-6)
                    dbuf_push_xform(out, e2x, e2y, sx, sy, rot, tx, ty);
            }
        }
    }
}

/* ---- 9a-5: HATCH ---- */

/* uv 좌표 [uA,uB]@vk 구간을 월드 세그먼트(x1,y1,x2,y2)로 fill에 push. */
static int emit_uv_seg(DBuf *fill, double p0x, double p0y,
                       double dirx, double diry, double perpx, double perpy,
                       double uA, double uB, double vk) {
    double ax = p0x + uA * dirx + vk * perpx, ay = p0y + uA * diry + vk * perpy;
    double bx = p0x + uB * dirx + vk * perpx, by = p0y + uB * diry + vk * perpy;
    if (!dbuf_push(fill, ax)) return 0; if (!dbuf_push(fill, ay)) return 0;
    if (!dbuf_push(fill, bx)) return 0; if (!dbuf_push(fill, by)) return 0;
    return 1;
}

/* 한 def-line의 평행선 패밀리를 경계로 클립해 fill에 push. perp 간격을 min_spacing에 반영.
 * 반환: 1 정상, 0 폭발(밀도/라인수 초과 → 호출자가 폴백 처리). */
static int emit_defline_fill(DBuf *fill, Dwg_HATCH_DefLine *dl,
                             double **loop_pts, int *loop_np, int nloops,
                             double sx, double sy, double rot, double tx, double ty,
                             double *min_spacing) {
    double p0x = dl->pt0.x, p0y = dl->pt0.y;
    double ux = dl->pt0.x + cos(dl->angle), uy = dl->pt0.y + sin(dl->angle);
    double ox = dl->pt0.x + dl->offset.x,   oy = dl->pt0.y + dl->offset.y;
    affine_point(&p0x, &p0y, sx, sy, rot, tx, ty);
    affine_point(&ux, &uy, sx, sy, rot, tx, ty);
    affine_point(&ox, &oy, sx, sy, rot, tx, ty);
    double dirx = ux - p0x, diry = uy - p0y;
    double along = sqrt(dirx * dirx + diry * diry);
    if (along < 1e-12) return 1;                 /* 퇴화 → 이 defline 무시 */
    dirx /= along; diry /= along;
    double perpx = -diry, perpy = dirx;
    double offx = ox - p0x, offy = oy - p0y;
    double off_u = offx * dirx + offy * diry;
    double off_v = offx * perpx + offy * perpy;
    if (fabs(off_v) < 1e-9) return 1;            /* 평행선 겹침 → 무시 */
    if (fabs(off_v) < *min_spacing) *min_spacing = fabs(off_v);

    double vmin = 1e18, vmax = -1e18;
    for (int L = 0; L < nloops; ++L) {
        double *pp = loop_pts[L]; int np = loop_np[L];
        for (int i = 0; i < np; ++i) {
            double X = pp[2*i] - p0x, Y = pp[2*i+1] - p0y;
            double v = X * perpx + Y * perpy;
            if (v < vmin) vmin = v; if (v > vmax) vmax = v;
        }
    }
    if (vmin > vmax) return 1;
    double klo_d = vmin / off_v, khi_d = vmax / off_v;
    long klo = (long)floor(fmin(klo_d, khi_d)) - 1;
    long khi = (long)ceil (fmax(klo_d, khi_d)) + 1;
    if (khi - klo > HATCH_MAX_FAMILY_LINES) return 0;

    double total = 0.0;
    for (BITCODE_BS i = 0; i < dl->num_dashes; ++i) total += fabs(dl->dashes[i]) * along;

    for (long k = klo; k <= khi; ++k) {
        double vk = (double)k * off_v;
        double cross[512]; int nc = 0;
        for (int L = 0; L < nloops; ++L) {
            double *pp = loop_pts[L]; int np = loop_np[L];
            for (int i = 0; i < np; ++i) {
                int j = (i + 1) % np;
                double X1 = pp[2*i]   - p0x, Y1 = pp[2*i+1] - p0y;
                double X2 = pp[2*j]   - p0x, Y2 = pp[2*j+1] - p0y;
                double v1 = X1 * perpx + Y1 * perpy;
                double v2 = X2 * perpx + Y2 * perpy;
                if ((v1 <= vk && vk < v2) || (v2 <= vk && vk < v1)) {
                    double t = (vk - v1) / (v2 - v1);
                    double u1 = X1 * dirx + Y1 * diry;
                    double u2 = X2 * dirx + Y2 * diry;
                    if (nc < 512) cross[nc++] = u1 + t * (u2 - u1);
                }
            }
        }
        if (nc < 2) continue;
        for (int a = 1; a < nc; ++a) {           /* insertion sort */
            double key = cross[a]; int bb = a - 1;
            while (bb >= 0 && cross[bb] > key) { cross[bb+1] = cross[bb]; bb--; }
            cross[bb+1] = key;
        }
        double phase = (double)k * off_u;
        for (int pi = 0; pi + 1 < nc; pi += 2) {
            double uA = cross[pi], uB = cross[pi+1];
            if (uB - uA < 1e-9) continue;
            if (dl->num_dashes <= 0 || total <= 1e-9) {
                if (!emit_uv_seg(fill, p0x, p0y, dirx, diry, perpx, perpy, uA, uB, vk)) return 0;
            } else {
                double m = floor((uA - phase) / total);
                double cur = phase + m * total;
                int guard = 0;
                while (cur < uB && guard++ < 200000) {
                    for (BITCODE_BS i = 0; i < dl->num_dashes; ++i) {
                        double len = fabs(dl->dashes[i]) * along;
                        double segEnd = cur + len;
                        if (dl->dashes[i] > 0) {
                            double a = fmax(cur, uA), b = fmin(segEnd, uB);
                            if (b > a && !emit_uv_seg(fill, p0x, p0y, dirx, diry,
                                                      perpx, perpy, a, b, vk)) return 0;
                        }
                        cur = segEnd;
                        if (dl->dashes[i] == 0) cur += 1e-6;   /* 0(dot) 무한루프 방지 */
                        if (cur >= uB) break;
                    }
                }
            }
            if (fill->n / 4 > HATCH_MAX_FILL_SEGMENTS) return 0;
        }
    }
    return 1;
}

static void write_hatch(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_HATCH *e = obj->tio.entity->tio.HATCH;
    write_entity_header(w, dwg, obj, DWGB_TYPE_HATCH);
    uint8_t isSolid = e->is_solid_fill ? 1 : 0;

    int nloops = (int)e->num_paths;
    if (nloops < 0) nloops = 0;
    double **loop_pts = NULL;
    int     *loop_np  = NULL;
    int      unsupported = 0;
    if (nloops > 0 && e->paths) {
        loop_pts = (double **)calloc((size_t)nloops, sizeof(double *));
        loop_np  = (int *)    calloc((size_t)nloops, sizeof(int));
        if (!loop_pts || !loop_np) {
            free(loop_pts); free(loop_np);
            w_u8(w, isSolid); w_u8(w, 0); w_f64(w, 0.0); w_i32(w, 0); w_i32(w, 0);
            return;
        }
        for (int L = 0; L < nloops; ++L) {
            DBuf lb; dbuf_init(&lb);
            build_one_loop(&lb, &e->paths[L], &unsupported, sx, sy, rot, tx, ty);
            loop_pts[L] = lb.v; loop_np[L] = lb.n / 2;
        }
        /* XCLIP: 경계 루프를 클립창으로 기하 클리핑 — 부분 침범 해치의 채움/패턴/
         * 경계가 클립 밖으로 번지지 않게 (이슈2). 빈 루프(np=0)는 이후 단계
         * (emit_defline_fill·emit 루프)가 자연히 스킵한다. */
        if (g_clip_on) {
            for (int L = 0; L < nloops; ++L)
                clip_loop_to_rect(&loop_pts[L], &loop_np[L],
                                  g_clx0, g_cly0, g_clx1, g_cly1);
        }
    }

    DBuf fill; dbuf_init(&fill);
    uint8_t patternFallback = 0;
    double  min_spacing = 1e18;
    if (!isSolid && nloops > 0 && e->num_deflines > 0) {
        if (unsupported) {
            patternFallback = 1;                 /* 곡선 경계 → 솔리드 폴백 */
        } else {
            int ok = 1;
            for (BITCODE_BS d = 0; d < e->num_deflines && ok; ++d)
                ok = emit_defline_fill(&fill, &e->deflines[d], loop_pts, loop_np, nloops,
                                       sx, sy, rot, tx, ty, &min_spacing);
            if (!ok) { dbuf_free(&fill); dbuf_init(&fill); patternFallback = 1; }
        }
    }
    double out_spacing = (patternFallback || min_spacing >= 1e18) ? 0.0 : min_spacing;

    w_u8(w, isSolid);
    w_u8(w, patternFallback);
    w_f64(w, out_spacing);
    w_i32(w, nloops);
    for (int L = 0; L < nloops; ++L) {
        int nv = loop_np ? loop_np[L] : 0;
        w_i32(w, nv);
        for (int i = 0; i < nv; ++i) { w_f64(w, loop_pts[L][2*i]); w_f64(w, loop_pts[L][2*i+1]); }
    }
    int nfill = fill.n / 4;
    w_i32(w, nfill);
    for (int i = 0; i < nfill; ++i) {
        w_f64(w, fill.v[4*i]);   w_f64(w, fill.v[4*i+1]);
        w_f64(w, fill.v[4*i+2]); w_f64(w, fill.v[4*i+3]);
    }

    dbuf_free(&fill);
    if (loop_pts) { for (int L = 0; L < nloops; ++L) free(loop_pts[L]); free(loop_pts); }
    free(loop_np);
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

static void write_point(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                        double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_POINT *e = obj->tio.entity->tio.POINT;
    double px = e->x, py = e->y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&px, &py, sx, sy, rot, tx, ty);
    }
    write_entity_header(w, dwg, obj, DWGB_TYPE_POINT);
    w_f64(w, px); w_f64(w, py);
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
        case DWG_TYPE_POINT:  { Dwg_Entity_POINT  *e=obj->tio.entity->tio.POINT;  *px=e->x;        *py=e->y;        return 1; }
        case DWG_TYPE_IMAGE:  { Dwg_Entity_IMAGE  *e=obj->tio.entity->tio.IMAGE;  *px=e->pt0.x;    *py=e->pt0.y;    return 1; }
        case DWG_TYPE_OLE2FRAME:{Dwg_Entity_OLE2FRAME*e=obj->tio.entity->tio.OLE2FRAME;*px=e->pt1.x;*py=e->pt1.y;   return 1; }
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

    /* INSERT 의 ATTRIB(속성값 텍스트)를 TEXT 로 emit. ATTRIB 은 INSERT 와 같은 좌표공간
     * (블록 로컬 아님) → 부모 변환(tx,ty,sx,sy,rot) 적용. 표제란/라벨 텍스트 보강. */
    if (e->attribs) {
        double scale_avg = (sx + sy) * 0.5;
        for (BITCODE_BL i = 0; i < e->num_owned && w->ok; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            if (!e->attribs[i] || !e->attribs[i]->obj) continue;
            const Dwg_Object *ao = e->attribs[i]->obj;
            if (ao->fixedtype != DWG_TYPE_ATTRIB || !ao->tio.entity) continue;
            Dwg_Entity_ATTRIB *at = ao->tio.entity->tio.ATTRIB;
            if (!at || (at->flags & 1)) continue;           /* invisible */
            char *u = tv_to_utf8(dwg, at->text_value);
            if (u && u[0]) {
                double px = at->ins_pt.x, py = at->ins_pt.y;
                affine_point(&px, &py, sx, sy, rot, tx, ty);
                double rdeg = (at->rotation + rot) * 180.0 / 3.14159265358979323846;
                write_entity_header(w, dwg, ao, DWGB_TYPE_TEXT);
                w_f64(w, px); w_f64(w, py);
                w_f64(w, at->height * fabs(scale_avg));
                w_f64(w, rdeg);
                w_i32(w, 0); w_i32(w, 0);   /* 정렬: 좌측+베이스라인 (ATTRIB 현 동작 유지) */
                w_string_utf8(w, u);
                (*count_ptr)++;
            }
            free(u);
        }
    }

    /* g_clip 복원 */
    g_clip_on = saved_on;
    g_clx0 = svx0; g_cly0 = svy0; g_clx1 = svx1; g_cly1 = svy1;
}

/* ---- 9b-2: MINSERT (블록 배열) ----
 * INSERT 블록을 num_cols×num_rows 격자로 반복 전개. 셀 (r,c) 삽입점 =
 * ins_pt + Rot(rotation)·(c·col_spacing, r·row_spacing). XCLIP 은 MINSERT 에 거의 없어 생략. */
static void write_minsert(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                          double tx, double ty, double sx, double sy, double rot,
                          int depth, int *count_ptr) {
    if (depth >= DWGB_MAX_INSERT_DEPTH) return;
    Dwg_Entity_MINSERT *e = obj->tio.entity->tio.MINSERT;
    if (!e->block_header || !e->block_header->obj) return;
    Dwg_Object_BLOCK_HEADER *bh = e->block_header->obj->tio.object->tio.BLOCK_HEADER;
    if (!bh) return;
    int ncols = e->num_cols < 1 ? 1 : (int)e->num_cols;
    int nrows = e->num_rows < 1 ? 1 : (int)e->num_rows;
    if ((long)ncols * nrows > 10000) return;   /* 폭주 방지 */

    double new_sx = sx * e->scale.x, new_sy = sy * e->scale.y;
    double new_rot = rot + e->rotation;
    double bpx = bh->base_pt.x, bpy = bh->base_pt.y;
    affine_point(&bpx, &bpy, new_sx, new_sy, new_rot, 0.0, 0.0);
    double crot = cos(e->rotation), srot = sin(e->rotation);

    for (int r = 0; r < nrows; ++r) {
        for (int c = 0; c < ncols; ++c) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) return;
            double ox = c * e->col_spacing, oy = r * e->row_spacing;
            double lx = e->ins_pt.x + ox*crot - oy*srot;
            double ly = e->ins_pt.y + ox*srot + oy*crot;
            affine_point(&lx, &ly, sx, sy, rot, tx, ty);
            double child_tx = lx - bpx, child_ty = ly - bpy;
            if (bh->entities && bh->num_owned > 0) {
                for (BITCODE_BL i = 0; i < bh->num_owned; ++i) {
                    if (*count_ptr >= DWGB_MAX_ENTITIES) return;
                    if (!bh->entities[i] || !bh->entities[i]->obj) continue;
                    write_entity(w, dwg, bh->entities[i]->obj,
                                 child_tx, child_ty, new_sx, new_sy, new_rot,
                                 depth + 1, count_ptr);
                }
            }
        }
    }
}

static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (*count_ptr >= DWGB_MAX_ENTITIES) return;

    /* XCLIP 활성 시 클립 처리:
     *  - LINE/LWPOLYLINE/POLYLINE/INSERT/DIMENSION: 통과(leaf writer 가 잘라내거나 재귀가 처리)
     *  - HATCH: 완전 밖이면 컬링, 부분 침범은 write_hatch 가 루프를 기하 클리핑
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
                    int outside  = (wmxx<g_clx0||wmnx>g_clx1||wmxy<g_cly0||wmny>g_cly1);
                    /* overflow(클립크기 이상 초과) 통째 컬링은 제거 — write_hatch 의
                     * 루프 기하 클리핑이 대체한다. 클립창 안 부분은 정상 표시. */
                    if (outside) return;
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
        case DWG_TYPE_POINT:
            write_point(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
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
        case DWG_TYPE_MULTILEADER:
            write_multileader(w, dwg, obj, tx, ty, sx, sy, rot, count_ptr); break;
        case DWG_TYPE_MINSERT:
            write_minsert(w, dwg, obj, tx, ty, sx, sy, rot, depth, count_ptr); break;
        case DWG_TYPE_IMAGE: {
            Dwg_Entity_IMAGE *e = obj->tio.entity->tio.IMAGE;
            write_raster_frame(w, dwg, obj, e->pt0.x, e->pt0.y, e->uvec.x, e->uvec.y,
                               e->vvec.x, e->vvec.y, e->image_size.x, e->image_size.y,
                               tx, ty, sx, sy, rot); (*count_ptr)++; break;
        }
        /* WIPEOUT 프레임은 의도적으로 미렌더. pt0+uvec*size+vvec*size 는 클립 폴리곤을
         * 감싸는 "이미지 정사각형"(image_size.x==y)이라 실제 마스크 영역보다 훨씬 크고,
         * AutoCAD/ZWCAD 도 기본값(WIPEOUTVARIABLES.display_frame=0)에서 테두리를 그리지 않는다.
         * 마스킹 자체를 구현하지 않으므로 프레임만 그리면 도면을 뚫는 큰 네모만 남는다 → skip. */
        case DWG_TYPE_OLE2FRAME: {
            Dwg_Entity_OLE2FRAME *e = obj->tio.entity->tio.OLE2FRAME;
            double xs[4] = { e->pt1.x, e->pt2.x, e->pt2.x, e->pt1.x };
            double ys[4] = { e->pt1.y, e->pt1.y, e->pt2.y, e->pt2.y };
            emit_quad_frame(w, dwg, obj, xs, ys, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        }
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
