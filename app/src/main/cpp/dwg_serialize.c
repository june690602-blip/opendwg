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

/* ---- 9a-1: POLYLINE 계열 ---- */

static void write_lwpolyline(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                             double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LWPOLYLINE *e = obj->tio.entity->tio.LWPOLYLINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
    uint8_t closed = ((e->flag & 1) || (e->flag & 512)) ? 1 : 0;
    w_u8(w, closed);
    w_i32(w, (int32_t)e->num_points);
    int do_transform = (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0);
    for (BITCODE_BL i = 0; i < e->num_points; ++i) {
        double px = e->points[i].x, py = e->points[i].y;
        if (do_transform) affine_point(&px, &py, sx, sy, rot, tx, ty);
        w_f64(w, px); w_f64(w, py);
    }
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

static void write_dimension(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                            double tx, double ty, double sx, double sy, double rot) {
    /* 모든 DIMENSION_* sub-type은 DIMENSION_COMMON 매크로를 공유.
     * ALIGNED로 캐스팅하여 공통 필드 접근.
     * def_pt: BITCODE_3BD, text_midpt: BITCODE_2RD */
    Dwg_Entity_DIMENSION_ALIGNED *e = obj->tio.entity->tio.DIMENSION_ALIGNED;
    write_entity_header(w, dwg, obj, DWGB_TYPE_DIMENSION);
    double dpx = e->def_pt.x, dpy = e->def_pt.y;
    double tmpx = e->text_midpt.x, tmpy = e->text_midpt.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&dpx, &dpy, sx, sy, rot, tx, ty);
        affine_point(&tmpx, &tmpy, sx, sy, rot, tx, ty);
    }
    w_f64(w, dpx); w_f64(w, dpy);
    w_f64(w, tmpx); w_f64(w, tmpy);
    w_i32(w, 0);  /* dimType — 단순화 */
    char *utf8 = tv_to_utf8(dwg, e->user_text);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
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
        memcpy(w->buf + pos_num_verts, &nv, 4);
        if (nv > 0) actual_paths++;
    }
    if (!w->ok) return;
    memcpy(w->buf + pos_num_paths, &actual_paths, 4);
}

/* ---- Basic entities ---- */

static void write_line(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                       double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LINE *e = obj->tio.entity->tio.LINE;
    double x1 = e->start.x, y1 = e->start.y;
    double x2 = e->end.x,   y2 = e->end.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&x1, &y1, sx, sy, rot, tx, ty);
        affine_point(&x2, &y2, sx, sy, rot, tx, ty);
    }
    write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
    w_f64(w, x1); w_f64(w, y1); w_f64(w, x2); w_f64(w, y2);
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

/* ---- 9b: INSERT 재귀 전개 ---- */

#define DWGB_MAX_INSERT_DEPTH 5
#define DWGB_MAX_ENTITIES 100000

/* Forward declaration */
static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr);

static void write_insert(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (depth >= DWGB_MAX_INSERT_DEPTH) return;
    Dwg_Entity_INSERT *e = obj->tio.entity->tio.INSERT;
    if (!e->block_header || !e->block_header->obj) return;
    Dwg_Object_BLOCK_HEADER *bh = e->block_header->obj->tio.object->tio.BLOCK_HEADER;
    if (!bh) return;

    /* INSERT 자체의 변환: parent 변환과 합성 */
    double ix = e->ins_pt.x, iy = e->ins_pt.y;
    affine_point(&ix, &iy, sx, sy, rot, tx, ty);
    double new_sx = sx * e->scale.x;
    double new_sy = sy * e->scale.y;
    double new_rot = rot + e->rotation;

    /* BLOCK_HEADER의 자식 엔티티 순회 */
    if (bh->entities && bh->num_owned > 0) {
        for (BITCODE_BL i = 0; i < bh->num_owned; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            if (!bh->entities[i] || !bh->entities[i]->obj) continue;
            write_entity(w, dwg, bh->entities[i]->obj,
                         ix, iy, new_sx, new_sy, new_rot, depth + 1, count_ptr);
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
            write_entity(w, dwg, child, ix, iy, new_sx, new_sy, new_rot,
                         depth + 1, count_ptr);
        }
    }
}

static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (*count_ptr >= DWGB_MAX_ENTITIES) return;
    switch (obj->fixedtype) {
        case DWG_TYPE_LINE:
            write_line(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_CIRCLE:
            write_circle(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_ARC:
            write_arc(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_LWPOLYLINE:
            write_lwpolyline(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
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
            write_dimension(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
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
        write_entity(&w, dwg, obj, 0.0, 0.0, 1.0, 1.0, 0.0, 0, &n_entities);
        if (!w.ok || n_entities >= DWGB_MAX_ENTITIES) break;
    }
    if (w.ok) {
        memcpy(w.buf + pos_num_entities, &n_entities, 4);
    }

    if (!w.ok) { free(w.buf); return NULL; }
    *out_len = w.len;
    return w.buf;
}
