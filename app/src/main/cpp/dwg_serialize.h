#ifndef CLEANCAD_DWG_SERIALIZE_H
#define CLEANCAD_DWG_SERIALIZE_H

#include <dwg.h>
#include <stddef.h>
#include <stdint.h>

#define DWGB_MAGIC            0x42475744u
#define DWGB_PROTOCOL_VERSION 1

#define DWGB_TYPE_LINE        1
#define DWGB_TYPE_CIRCLE      2
#define DWGB_TYPE_ARC         3
#define DWGB_TYPE_LWPOLYLINE  4
#define DWGB_TYPE_POLYLINE_2D 5
#define DWGB_TYPE_POLYLINE_3D 6
#define DWGB_TYPE_TEXT        7
#define DWGB_TYPE_MTEXT       8
#define DWGB_TYPE_HATCH       10
#define DWGB_TYPE_DIMENSION   11
#define DWGB_TYPE_LEADER      12
#define DWGB_TYPE_ELLIPSE     13
#define DWGB_TYPE_SPLINE      14
#define DWGB_TYPE_3DFACE      15
#define DWGB_TYPE_SOLID       16
#define DWGB_TYPE_UNKNOWN     0xFF

/**
 * Dwg_Data 전체를 동적 바이트 버퍼로 직렬화한다.
 * 반환: 호출자가 free() 해야 하는 malloc된 버퍼. 실패 시 NULL.
 * out_len: 버퍼 크기 (바이트).
 */
uint8_t *dwgb_serialize(const Dwg_Data *dwg, size_t *out_len);

#endif
