// JNI surface for geometric path operations (class app.croptop.core.engine.NativePathOpsBridge): Unite / Subtract / Intersect / Exclude,
// simplify, offset (grow/shrink) and the bezier re-fit that turns the polygon results back into cubic curves. Paths cross as verbs + points:
// verb codes Move 0, Line 1, Quad 2, Cubic 3, Close 4; points are x, y pairs consumed in order (Move 1, Line 1, Quad 2, Cubic 3, Close 0).
// A result is one DoubleArray: [verbCount, verb…, x, y, …].
#if defined(__ANDROID__)
#include <jni.h>

#include <stdexcept>
#include <string>
#include <vector>

#include "curve_fit.h"
#include "vector_boolean.h"

namespace {

using croptop::vector::Path;
using croptop::vector::Verb;
thread_local std::string g_path_error;

Path decodePath(JNIEnv* env, jbyteArray verbs, jdoubleArray points) {
    if (verbs == nullptr || points == nullptr) throw std::invalid_argument("path arrays are null");
    const jsize nv = env->GetArrayLength(verbs), np = env->GetArrayLength(points);
    if (nv < 0 || nv > 200000 || np < 0 || np > 800000 || np % 2 != 0) throw std::invalid_argument("path is too large or malformed");
    std::vector<jbyte> v(static_cast<std::size_t>(nv));
    std::vector<jdouble> p(static_cast<std::size_t>(np));
    if (nv > 0) env->GetByteArrayRegion(verbs, 0, nv, v.data());
    if (np > 0) env->GetDoubleArrayRegion(points, 0, np, p.data());
    Path path;
    std::size_t at = 0;
    const auto take = [&](std::size_t n) {
        if (at + 2 * n > p.size()) throw std::invalid_argument("path has fewer points than its verbs need");
        const std::size_t base = at;
        at += 2 * n;
        return base;
    };
    bool open = false;
    for (const jbyte code : v) {
        switch (code) {
            case 0: { const auto b = take(1); path.moveTo({p[b], p[b + 1]}); open = true; break; }
            case 1: { if (!open) throw std::invalid_argument("a segment precedes the first move"); const auto b = take(1); path.lineTo({p[b], p[b + 1]}); break; }
            case 2: { if (!open) throw std::invalid_argument("a segment precedes the first move"); const auto b = take(2); path.quadTo({p[b], p[b + 1]}, {p[b + 2], p[b + 3]}); break; }
            case 3: { if (!open) throw std::invalid_argument("a segment precedes the first move"); const auto b = take(3); path.cubicTo({p[b], p[b + 1]}, {p[b + 2], p[b + 3]}, {p[b + 4], p[b + 5]}); break; }
            case 4: path.close(); break;
            default: throw std::invalid_argument("unknown path verb");
        }
    }
    if (at != p.size()) throw std::invalid_argument("path has more points than its verbs use");
    return path;
}

jdoubleArray encodePath(JNIEnv* env, const Path& path) {
    std::vector<jdouble> out;
    out.reserve(1 + path.verbs().size() + path.points().size() * 2U);
    out.push_back(static_cast<jdouble>(path.verbs().size()));
    for (const auto v : path.verbs()) out.push_back(static_cast<jdouble>(static_cast<int>(v)));
    for (const auto& pt : path.points()) {
        out.push_back(pt.x);
        out.push_back(pt.y);
    }
    auto array = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetDoubleArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

croptop::vector::FillRule rule(jint r) {
    if (r != 0 && r != 1) throw std::invalid_argument("fill rule must be 0 (non-zero) or 1 (even-odd)");
    return r == 0 ? croptop::vector::FillRule::NonZero : croptop::vector::FillRule::EvenOdd;
}

template <typename Fn>
jdoubleArray guarded(Fn&& fn) {
    g_path_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_path_error = e.what();
    } catch (...) {
        g_path_error = "unknown path operation failure";
    }
    return nullptr;
}

Path maybeRefit(const Path& p, jboolean refit, jdouble refit_tolerance) {
    if (refit != JNI_TRUE) return p;
    croptop::vector::RefitOptions options;
    if (refit_tolerance > 0.0) options.tolerance = refit_tolerance;
    return croptop::vector::refit(p, options);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativePathOpsBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_path_error.c_str());
}

/** op: 0 unite, 1 subtract, 2 intersect, 3 exclude. */
extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativePathOpsBridge_nativeCombine(JNIEnv* env, jobject, jbyteArray sv, jdoubleArray sp, jint srule, jbyteArray cv, jdoubleArray cp,
                                                                                                          jint crule, jint op, jdouble tolerance, jboolean refit, jdouble refit_tolerance) {
    return guarded([&]() -> jdoubleArray {
        if (op < 0 || op > 3 || !(tolerance > 0.0)) throw std::invalid_argument("invalid path operation settings");
        std::string error;
        const auto result = croptop::vector::combine(decodePath(env, sv, sp), rule(srule), decodePath(env, cv, cp), rule(crule), static_cast<croptop::vector::BoolOp>(op), error, tolerance);
        if (!result) throw std::invalid_argument(error);
        return encodePath(env, maybeRefit(*result, refit, refit_tolerance));
    });
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativePathOpsBridge_nativeSimplify(JNIEnv* env, jobject, jbyteArray verbs, jdoubleArray points, jint fill_rule, jdouble tolerance,
                                                                                                           jboolean refit, jdouble refit_tolerance) {
    return guarded([&]() -> jdoubleArray {
        if (!(tolerance > 0.0)) throw std::invalid_argument("tolerance must be positive");
        std::string error;
        const auto result = croptop::vector::simplify(decodePath(env, verbs, points), rule(fill_rule), error, tolerance);
        if (!result) throw std::invalid_argument(error);
        return encodePath(env, maybeRefit(*result, refit, refit_tolerance));
    });
}

/** join: 0 miter, 1 round, 2 bevel. */
extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativePathOpsBridge_nativeOffset(JNIEnv* env, jobject, jbyteArray verbs, jdoubleArray points, jint fill_rule, jdouble delta, jint join,
                                                                                                         jdouble miter_limit, jdouble tolerance, jboolean refit, jdouble refit_tolerance) {
    return guarded([&]() -> jdoubleArray {
        if (join < 0 || join > 2 || !(tolerance > 0.0)) throw std::invalid_argument("invalid offset settings");
        std::string error;
        const auto result = croptop::vector::offset(decodePath(env, verbs, points), rule(fill_rule), delta, static_cast<croptop::vector::Join>(join), miter_limit, error, tolerance);
        if (!result) throw std::invalid_argument(error);
        return encodePath(env, maybeRefit(*result, refit, refit_tolerance));
    });
}

/** Net signed area of a path (px²) after flattening; NaN on invalid input. */
extern "C" JNIEXPORT jdouble JNICALL Java_app_croptop_core_engine_NativePathOpsBridge_nativeArea(JNIEnv* env, jobject, jbyteArray verbs, jdoubleArray points, jdouble tolerance) {
    g_path_error.clear();
    try {
        if (!(tolerance > 0.0)) throw std::invalid_argument("tolerance must be positive");
        return croptop::vector::netArea(decodePath(env, verbs, points), tolerance);
    } catch (const std::exception& e) {
        g_path_error = e.what();
    } catch (...) {
        g_path_error = "unknown path operation failure";
    }
    return std::nan("");
}
#endif  // __ANDROID__
