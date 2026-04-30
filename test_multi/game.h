#pragma once
#include "ktc_runtime.h"

#include "math.h"

typedef struct {
    int32_t a;
} game_Test;

game_Test game_Test_create(void);
typedef struct {
    float x;
    float y;
    float z;
} game_Vec3;

game_Vec3 game_Vec3_create(float x, float y, float z);
bool game_Vec3_equals(game_Vec3 a, game_Vec3 b);
void game_Vec3_toString(game_Vec3 $self, kt_StrBuf* sb);
int main(void);
float game_dot(game_Vec3 a, game_Vec3 b);
