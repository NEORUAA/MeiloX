#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#if !defined(LIQUID_MENU_STANDALONE)
#include <jni.h>
#endif

namespace {
constexpr float kExpansion = 1.5286649465560913f;
constexpr float kAdjustment = 0.5286649465560913f;
struct Normal {
    float x, y;
};
struct Sample {
    float distance, nx, ny;
};
inline float saturate(float value) { return std::clamp(value, 0.0f, 1.0f); }

// CASDF's continuous field and its independently ovalized normal, in isotropic coordinates.
struct RoundedField {
    float cx, cy, radius, inverseX, inverseY, halfWidth, halfHeight;
    float expanded, curvatureX, curvatureY, baseRadius;
    bool circular;
    explicit RoundedField(const float *g) {
        cx = g[0] + g[2] / 2.0f;
        cy = g[1] + g[3] / 2.0f;
        const float rx = std::max(g[4], 0.01f), ry = std::max(g[5], 0.01f);
        radius = std::min(rx, ry);
        inverseX = radius / rx;
        inverseY = radius / ry;
        halfWidth = g[2] / 2.0f * inverseX;
        halfHeight = g[3] / 2.0f * inverseY;
        expanded = radius * kExpansion;
        curvatureX = saturate(2.8915570351735034f - 1.4457785175867517f * (halfWidth * 2.0f) / expanded);
        curvatureY = saturate(2.8915570351735034f - 1.4457785175867517f * (halfHeight * 2.0f) / expanded);
        baseRadius = radius * (kExpansion - kAdjustment * std::max(curvatureX, curvatureY));
        circular = curvatureX == 1.0f && curvatureY == 1.0f;
    }
    float distance(float x, float y) const {
        const float qx = std::abs((x - cx) * inverseX) - halfWidth;
        const float qy = std::abs((y - cy) * inverseY) - halfHeight;
        const float bx = qx + baseRadius, by = qy + baseRadius;
        const float interior = std::min(std::max(bx, by), 0.0f);
        if (circular) {
            const float dx = std::max(bx, 0.0f), dy = std::max(by, 0.0f);
            return interior + std::sqrt(dx * dx + dy * dy) - radius;
        }
        const float ux = (qx + expanded) / expanded, uy = (qy + expanded) / expanded;
        const float zx = std::max(ux, 0.0f), zy = std::max(uy, 0.0f);
        const float maximum = std::max(zx, zy);
        const float t = maximum > 0.0f ? std::min(zx, zy) / maximum : 0.0f;
        const float length = std::sqrt(zx * zx + zy * zy);
        const float polynomial =
            ((((-0.9260540008544922f * t + 3.1560099124908447f) * t - 3.6412200927734375f) * t +
              1.268030047416687f) *
                 t +
             0.2685309946537018f) *
            t * t;
        const float continuous = length + 1.0f - 1.0f / (1.0f - polynomial * std::min(length, 1.0f));
        const float ax = std::max(kExpansion * ux - kAdjustment, 0.0f);
        const float ay = std::max(kExpansion * uy - kAdjustment, 0.0f);
        const float correction =
            0.3458344340324402f - continuous + 0.6541655659675598f * std::sqrt(ax * ax + ay * ay);
        const float weight = saturate((zx < zy ? 1.0f : -1.0f) * (t - 1.0f) + 0.5f);
        return interior + expanded * (continuous - 1.0f +
                                      correction * (curvatureX + (curvatureY - curvatureX) * weight));
    }
    Normal normal(float x, float y) const {
        const float lx = (x - cx) * inverseX, ly = (y - cy) * inverseY;
        const float qx = std::abs(lx) - halfWidth + baseRadius;
        const float qy = std::abs(ly) - halfHeight + baseRadius;
        float nx = std::max(qx, 0.0f), ny = std::max(qy, 0.0f);
        const float cornerLength = std::sqrt(nx * nx + ny * ny);
        if (cornerLength > 0.000001f) {
            nx /= cornerLength;
            ny /= cornerLength;
        } else if (qx > qy) {
            nx = 1.0f;
            ny = 0.0f;
        } else {
            nx = 0.0f;
            ny = 1.0f;
        }
        if (lx < 0.0f)
            nx = -nx;
        if (ly < 0.0f)
            ny = -ny;
        float ox = lx, oy = ly * halfWidth / std::max(halfHeight, 0.000001f);
        const float ovalLength = std::sqrt(ox * ox + oy * oy);
        if (ovalLength > 0.000001f) {
            ox /= ovalLength;
            oy /= ovalLength;
        } else {
            ox = nx;
            oy = ny;
        }
        nx += ox;
        ny += oy;
        const float length = std::sqrt(nx * nx + ny * ny);
        if (length <= 0.000001f)
            return {0.0f, 0.0f};
        return {nx / length * inverseX, ny / length * inverseY};
    }
};

Sample sample(const RoundedField &incoming, const RoundedField &outgoing, bool hasOutgoing, float smoothing,
              float x, float y) {
    const float a = incoming.distance(x, y);
    const float b = hasOutgoing ? outgoing.distance(x, y) : INFINITY;
    if (!hasOutgoing || std::abs(a - b) >= smoothing) {
        const Normal n = (!hasOutgoing || a <= b) ? incoming.normal(x, y) : outgoing.normal(x, y);
        return {std::min(a, b), n.x, n.y};
    }
    const Normal an = incoming.normal(x, y), bn = outgoing.normal(x, y);
    const float alignment = an.x * bn.x + an.y * bn.y;
    const float radius = smoothing * saturate(0.5f - 0.5f * alignment);
    const float weight =
        radius <= 0.000001f ? (a <= b ? 0.0f : 1.0f) : saturate(0.5f + 0.5f * (a - b) / radius);
    return {a + (b - a) * weight - radius * weight * (1.0f - weight), an.x + (bn.x - an.x) * weight,
            an.y + (bn.y - an.y) * weight};
}
struct Scratch {
    std::vector<float> raw, horizontal;
};
thread_local Scratch scratch;
} // namespace

// One call covers the entire field. Scratch capacity survives subsequent frames on this thread.
extern "C" void liquid_menu_sdf_build(const float *geometry, float originX, float originY, float step,
                                      int width, int height, const float *weights, int radius, float *rgba) {
    const RoundedField incoming(geometry), outgoing(geometry + 6);
    const bool hasOutgoing = geometry[13] != 0.0f;
    const float smoothing = std::max(geometry[12], 0.0f);
    const int paddedWidth = width + 2 * radius, paddedHeight = height + 2 * radius;
    const size_t rawSize = static_cast<size_t>(paddedWidth) * paddedHeight * 3;
    const size_t horizontalSize = static_cast<size_t>(width) * paddedHeight * 3;
    if (scratch.raw.size() < rawSize)
        scratch.raw.resize(rawSize);
    if (scratch.horizontal.size() < horizontalSize)
        scratch.horizontal.resize(horizontalSize);
    float *raw = scratch.raw.data();
    float *horizontal = scratch.horizontal.data();
    for (int row = 0; row < paddedHeight; row++) {
        const float y = originY + (row - radius) * step;
        for (int column = 0; column < paddedWidth; column++) {
            const Sample s =
                sample(incoming, outgoing, hasOutgoing, smoothing, originX + (column - radius) * step, y);
            const size_t output = (static_cast<size_t>(row) * paddedWidth + column) * 3;
            raw[output] = s.distance;
            raw[output + 1] = s.nx;
            raw[output + 2] = s.ny;
        }
    }
    for (int row = 0; row < paddedHeight; row++)
        for (int column = 0; column < width; column++) {
            const size_t center = (static_cast<size_t>(row) * paddedWidth + column + radius) * 3;
            float d = raw[center] * weights[0], nx = raw[center + 1] * weights[0],
                  ny = raw[center + 2] * weights[0];
            for (int offset = 1; offset <= radius; offset++) {
                const size_t left = center - offset * 3, right = center + offset * 3;
                const float weight = weights[offset];
                d += (raw[left] + raw[right]) * weight;
                nx += (raw[left + 1] + raw[right + 1]) * weight;
                ny += (raw[left + 2] + raw[right + 2]) * weight;
            }
            const size_t output = (static_cast<size_t>(row) * width + column) * 3;
            horizontal[output] = d;
            horizontal[output + 1] = nx;
            horizontal[output + 2] = ny;
        }
    for (int row = 0; row < height; row++)
        for (int column = 0; column < width; column++) {
            const size_t center = (static_cast<size_t>(row + radius) * width + column) * 3;
            float d = horizontal[center] * weights[0], nx = horizontal[center + 1] * weights[0],
                  ny = horizontal[center + 2] * weights[0];
            for (int offset = 1; offset <= radius; offset++) {
                const size_t above = center - offset * width * 3, below = center + offset * width * 3;
                const float weight = weights[offset];
                d += (horizontal[above] + horizontal[below]) * weight;
                nx += (horizontal[above + 1] + horizontal[below + 1]) * weight;
                ny += (horizontal[above + 2] + horizontal[below + 2]) * weight;
            }
            const float length = std::sqrt(nx * nx + ny * ny);
            const size_t output = (static_cast<size_t>(row) * width + column) * 4;
            rgba[output] = d;
            rgba[output + 1] = length > 0.000001f ? nx / length : 0.0f;
            rgba[output + 2] = length > 0.000001f ? ny / length : 0.0f;
            rgba[output + 3] = 1.0f;
        }
}

extern "C" void liquid_menu_sdf_pack(const float *rgba, int width, int height, int rowBytes, void *target) {
    for (int row = 0; row < height; row++) {
        const float *source = rgba + static_cast<size_t>(row) * width * 4;
        auto *output = reinterpret_cast<uint16_t *>(static_cast<uint8_t *>(target) +
                                                    static_cast<size_t>(row) * rowBytes);
        for (int index = 0; index < width * 4; index += 4) {
#if defined(__aarch64__)
            vst1_u16(output + index, vreinterpret_u16_f16(vcvt_f16_f32(vld1q_f32(source + index))));
#else
            auto *half = reinterpret_cast<_Float16 *>(output + index);
            for (int channel = 0; channel < 4; channel++)
                half[channel] = static_cast<_Float16>(source[index + channel]);
#endif
        }
    }
}

#if !defined(LIQUID_MENU_STANDALONE)
extern "C" JNIEXPORT jboolean JNICALL Java_com_ljyh_mei_ui_glass_LiquidMenuNativeField_buildNative(
    JNIEnv *env, jobject, jfloatArray geometry, jfloat originX, jfloat originY, jfloat step, jint width,
    jint height, jfloatArray weights, jint radius, jfloatArray output) {
    if (width < 2 || height < 2 || radius < 1 || radius > 16 || step <= 0 ||
        env->GetArrayLength(geometry) < 14 || env->GetArrayLength(weights) <= radius ||
        static_cast<int64_t>(width) * height * 4 > env->GetArrayLength(output))
        return JNI_FALSE;
    float g[14], w[17];
    env->GetFloatArrayRegion(geometry, 0, 14, g);
    env->GetFloatArrayRegion(weights, 0, radius + 1, w);
    auto *rgba = static_cast<float *>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!rgba)
        return JNI_FALSE;
    liquid_menu_sdf_build(g, originX, originY, step, width, height, w, radius, rgba);
    env->ReleasePrimitiveArrayCritical(output, rgba, 0);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_ljyh_mei_ui_glass_LiquidMenuNativeField_packNative(
    JNIEnv *env, jobject, jfloatArray source, jint width, jint height, jint rowBytes, jobject target) {
    if (width < 1 || height < 1 || rowBytes < static_cast<int64_t>(width) * 8 ||
        static_cast<int64_t>(width) * height * 4 > env->GetArrayLength(source) ||
        static_cast<int64_t>(rowBytes) * height > env->GetDirectBufferCapacity(target))
        return JNI_FALSE;
    void *output = env->GetDirectBufferAddress(target);
    if (!output)
        return JNI_FALSE;
    auto *rgba = static_cast<float *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!rgba)
        return JNI_FALSE;
    liquid_menu_sdf_pack(rgba, width, height, rowBytes, output);
    env->ReleasePrimitiveArrayCritical(source, rgba, JNI_ABORT);
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_ljyh_mei_ui_glass_LiquidMenuNativeField_releaseNative(JNIEnv *,
                                                                                                 jobject) {
    std::vector<float>().swap(scratch.raw);
    std::vector<float>().swap(scratch.horizontal);
}
#endif
