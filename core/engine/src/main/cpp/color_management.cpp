// Colour management over Little CMS 2.
#include "color_management.h"

#include <lcms2.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>
#include <type_traits>
#include <utility>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::cms {
namespace {

constexpr std::size_t kMaxIccBytes = 64U << 20U;

cmsUInt32Number lcmsIntent(Intent intent) {
    switch (intent) {
        case Intent::Perceptual: return INTENT_PERCEPTUAL;
        case Intent::RelativeColorimetric: return INTENT_RELATIVE_COLORIMETRIC;
        case Intent::Saturation: return INTENT_SATURATION;
        case Intent::AbsoluteColorimetric: return INTENT_ABSOLUTE_COLORIMETRIC;
    }
    return INTENT_RELATIVE_COLORIMETRIC;
}

std::optional<cmsUInt32Number> rgbaType(PixelFormat format) {
    switch (format) {
        case PixelFormat::RGBA8: return TYPE_RGBA_8;
        case PixelFormat::RGBA16: return TYPE_RGBA_16;
        case PixelFormat::RGBA32F: return TYPE_RGBA_FLT;
        default: return std::nullopt;
    }
}

void setDescription(cmsHPROFILE h, const char* text) {
    cmsMLU* mlu = cmsMLUalloc(nullptr, 1);
    cmsMLUsetASCII(mlu, "en", "US", text);
    cmsWriteTag(h, cmsSigProfileDescriptionTag, mlu);
    cmsMLUsetASCII(mlu, "en", "US", "No copyright, use freely");
    cmsWriteTag(h, cmsSigCopyrightTag, mlu);
    cmsMLUfree(mlu);
}

cmsHPROFILE rgbProfile(const char* name, cmsCIExyY white, cmsCIExyYTRIPLE primaries, int curve_type, const double* params) {
    cmsToneCurve* curve = cmsBuildParametricToneCurve(nullptr, curve_type, params);
    cmsToneCurve* curves[3] = {curve, curve, curve};
    cmsHPROFILE h = cmsCreateRGBProfile(&white, &primaries, curves);
    cmsFreeToneCurve(curve);
    if (h != nullptr) setDescription(h, name);
    return h;
}

cmsHPROFILE makeBuiltin(WorkingSpace space) {
    const cmsCIExyY d65{0.3127, 0.3290, 1.0};
    const cmsCIExyY d50{0.3457, 0.3585, 1.0};
    const cmsCIExyYTRIPLE srgb{{0.640, 0.330, 1.0}, {0.300, 0.600, 1.0}, {0.150, 0.060, 1.0}};
    const cmsCIExyYTRIPLE p3{{0.680, 0.320, 1.0}, {0.265, 0.690, 1.0}, {0.150, 0.060, 1.0}};
    const double srgb_curve[5] = {2.4, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.04045};
    const double linear[1] = {1.0};
    switch (space) {
        case WorkingSpace::SRGB: {
            cmsHPROFILE h = cmsCreate_sRGBProfile();
            if (h != nullptr) setDescription(h, "sRGB IEC61966-2.1");
            return h;
        }
        case WorkingSpace::DisplayP3: return rgbProfile("Display P3", d65, p3, 4, srgb_curve);
        case WorkingSpace::AdobeRGB: {
            const double gamma[1] = {563.0 / 256.0};
            return rgbProfile("Adobe RGB (1998) compatible", d65, {{0.640, 0.330, 1.0}, {0.210, 0.710, 1.0}, {0.150, 0.060, 1.0}}, 1,
                              gamma);
        }
        case WorkingSpace::ProPhoto: {
            // ROMM RGB: linear segment (slope 1/16) below 1/32 encoded, gamma 1.8 above.
            const double romm[5] = {1.8, 1.0, 0.0, 1.0 / 16.0, 1.0 / 32.0};
            return rgbProfile("ProPhoto RGB (ROMM) compatible", d50,
                              {{0.7347, 0.2653, 1.0}, {0.1596, 0.8404, 1.0}, {0.0366, 0.0001, 1.0}}, 4, romm);
        }
        case WorkingSpace::LinearSRGB: return rgbProfile("Linear sRGB", d65, srgb, 1, linear);
        case WorkingSpace::LinearP3: return rgbProfile("Linear Display P3", d65, p3, 1, linear);
        case WorkingSpace::Rec2020: {
            // BT.709/BT.2020 transfer (inverse OETF): linear segment 1/4.5 below 0.081, else ((V+0.099)/1.099)^(1/0.45).
            const double bt[5] = {1.0 / 0.45, 1.0 / 1.099, 0.099 / 1.099, 1.0 / 4.5, 0.081};
            return rgbProfile("ITU-R BT.2020", d65, {{0.708, 0.292, 1.0}, {0.170, 0.797, 1.0}, {0.131, 0.046, 1.0}}, 4, bt);
        }
    }
    return nullptr;
}

// ── Synthetic CMYK: a subtractive ink model ────────────────────────────────────────────────────
// Linear-sRGB reflectance = paper × Π(1 − coverage × absorption) per channel. The absorptions are
// chosen so the process primaries are duller than sRGB's secondaries, as with real inks.
constexpr std::array<double, 3> kPaper{0.93, 0.93, 0.91};
constexpr std::array<std::array<double, 3>, 4> kAbsorb{{
    {0.93, 0.36, 0.10},  // cyan
    {0.10, 0.88, 0.42},  // magenta
    {0.02, 0.10, 0.90},  // yellow
    {0.93, 0.93, 0.92},  // black
}};

std::array<double, 3> inkToLinear(const std::array<double, 4>& ink) {
    std::array<double, 3> r = kPaper;
    for (int i = 0; i < 4; ++i) {
        // Dot gain: printed dots spread, so mid coverages absorb more than their nominal area.
        const double x = std::clamp(ink[static_cast<std::size_t>(i)], 0.0, 1.0);
        const double effective = 1.0 - std::pow(1.0 - x, 1.6);
        for (int c = 0; c < 3; ++c) r[static_cast<std::size_t>(c)] *= 1.0 - effective * kAbsorb[static_cast<std::size_t>(i)][static_cast<std::size_t>(c)];
    }
    return r;
}

struct CmykModel {
    double ink_limit = 3.0;
};

/** Ink coverage → CIE L*a*b* (D50): linear-sRGB reflectance → XYZ (Bradford-adapted to D50) → Lab. */
std::array<double, 3> labOf(const CmykModel&, const std::array<double, 4>& ink) {
    const auto rgb = inkToLinear(ink);
    const double x = 0.4360747 * rgb[0] + 0.3850649 * rgb[1] + 0.1430804 * rgb[2];
    const double y = 0.2225045 * rgb[0] + 0.7168786 * rgb[1] + 0.0606169 * rgb[2];
    const double z = 0.0139322 * rgb[0] + 0.0971045 * rgb[1] + 0.7141733 * rgb[2];
    const auto f = [](double t) {
        constexpr double kEpsilon = 216.0 / 24389.0, kKappa = 24389.0 / 27.0;
        return t > kEpsilon ? std::cbrt(t) : (kKappa * t + 16.0) / 116.0;
    };
    const double fx = f(x / 0.96422), fy = f(y), fz = f(z / 0.82521);
    return {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
}

cmsInt32Number sampleAToB(const cmsUInt16Number in[], cmsUInt16Number out[], void* cargo) {
    const auto& m = *static_cast<const CmykModel*>(cargo);
    const std::array<double, 4> ink{in[0] / 65535.0, in[1] / 65535.0, in[2] / 65535.0, in[3] / 65535.0};
    const auto lab = labOf(m, ink);
    cmsCIELab value{lab[0], lab[1], lab[2]};
    cmsFloat2LabEncoded(out, &value);
    return 1;
}

/**
 * Lab → CMYK by damped Gauss–Newton on all four inks: minimise ΔE² to the target plus a pull of K
 * towards a grey-component-replacement curve (so neutrals and shadows use black, and K rises further
 * where CMY alone cannot reach the target), within the total ink limit.
 */
cmsInt32Number sampleBToA(const cmsUInt16Number in[], cmsUInt16Number out[], void* cargo) {
    const auto& m = *static_cast<const CmykModel*>(cargo);
    cmsCIELab target{};
    cmsLabEncoded2Float(&target, in);
    const double darkness = std::clamp((78.0 - target.L) / 78.0, 0.0, 1.0);
    const double chroma = std::hypot(target.a, target.b);
    // Medium GCR: black follows darkness, less of it for saturated colours.
    const double k_target = 0.95 * std::pow(darkness, 1.5) * std::clamp(1.0 - chroma / 120.0, 0.3, 1.0);
    constexpr double kPull = 3.0;  // ΔE units per unit of K away from the GCR target
    std::array<double, 4> ink{0.3, 0.3, 0.3, k_target};
    const auto clampInk = [&](std::array<double, 4>& v) {
        for (auto& x : v) x = std::clamp(x, 0.0, 1.0);
        const double total = v[0] + v[1] + v[2] + v[3];
        if (total > m.ink_limit) {
            const double excess = total - m.ink_limit;
            const double cmy = v[0] + v[1] + v[2];
            if (cmy > 0) for (int i = 0; i < 3; ++i) v[static_cast<std::size_t>(i)] -= excess * v[static_cast<std::size_t>(i)] / cmy;
        }
    };
    const auto residual = [&](const std::array<double, 4>& v) {
        const auto lab = labOf(m, v);
        return std::array<double, 4>{lab[0] - target.L, lab[1] - target.a, lab[2] - target.b, kPull * (v[3] - k_target)};
    };
    for (int iteration = 0; iteration < 40; ++iteration) {
        const auto e = residual(ink);
        if (e[0] * e[0] + e[1] * e[1] + e[2] * e[2] < 1e-4) break;
        double j[4][4];
        for (int k = 0; k < 4; ++k) {
            auto probe = ink;
            const double h = probe[static_cast<std::size_t>(k)] < 0.99 ? 0.005 : -0.005;
            probe[static_cast<std::size_t>(k)] += h;
            const auto ep = residual(probe);
            for (int r = 0; r < 4; ++r) j[r][k] = (ep[static_cast<std::size_t>(r)] - e[static_cast<std::size_t>(r)]) / h;
        }
        double a[4][4], b[4];
        for (int r = 0; r < 4; ++r) {
            b[r] = 0;
            for (int c = 0; c < 4; ++c) {
                a[r][c] = r == c ? 1e-3 : 0.0;
                for (int k = 0; k < 4; ++k) a[r][c] += j[k][r] * j[k][c];
            }
            for (int k = 0; k < 4; ++k) b[r] -= j[k][r] * e[static_cast<std::size_t>(k)];
        }
        for (int p = 0; p < 4; ++p) {
            for (int r = p + 1; r < 4; ++r) {
                const double f = a[r][p] / a[p][p];
                for (int c = p; c < 4; ++c) a[r][c] -= f * a[p][c];
                b[r] -= f * b[p];
            }
        }
        double d[4];
        for (int r = 3; r >= 0; --r) {
            d[r] = b[r];
            for (int c = r + 1; c < 4; ++c) d[r] -= a[r][c] * d[c];
            d[r] /= a[r][r];
        }
        for (int k = 0; k < 4; ++k) ink[static_cast<std::size_t>(k)] += std::clamp(d[k], -0.2, 0.2);
        clampInk(ink);
    }
    for (int i = 0; i < 4; ++i) out[i] = static_cast<cmsUInt16Number>(std::lround(ink[static_cast<std::size_t>(i)] * 65535.0));
    return 1;
}

std::vector<std::uint8_t> buildGenericCmyk(double ink_limit) {
    CmykModel model;
    model.ink_limit = ink_limit;
    cmsHPROFILE h = cmsCreateProfilePlaceholder(nullptr);
    cmsSetProfileVersion(h, 4.3);
    cmsSetDeviceClass(h, cmsSigOutputClass);
    cmsSetColorSpace(h, cmsSigCmykData);
    cmsSetPCS(h, cmsSigLabData);
    setDescription(h, "Generic CMYK (CropTop approximation)");
    cmsWriteTag(h, cmsSigMediaWhitePointTag, cmsD50_XYZ());

    // v4 lutAtoB/lutBtoA tags need curve stages around the CLUT; identity curves satisfy that.
    constexpr cmsUInt32Number kAToBGrid = 11, kBToAGrid = 17;
    cmsPipeline* a2b = cmsPipelineAlloc(nullptr, 4, 3);
    cmsStage* a2b_clut = cmsStageAllocCLut16bit(nullptr, kAToBGrid, 4, 3, nullptr);
    cmsStageSampleCLut16bit(a2b_clut, sampleAToB, &model, 0);
    cmsPipelineInsertStage(a2b, cmsAT_END, cmsStageAllocToneCurves(nullptr, 4, nullptr));
    cmsPipelineInsertStage(a2b, cmsAT_END, a2b_clut);
    cmsPipelineInsertStage(a2b, cmsAT_END, cmsStageAllocToneCurves(nullptr, 3, nullptr));
    cmsPipeline* b2a = cmsPipelineAlloc(nullptr, 3, 4);
    cmsStage* b2a_clut = cmsStageAllocCLut16bit(nullptr, kBToAGrid, 3, 4, nullptr);
    cmsStageSampleCLut16bit(b2a_clut, sampleBToA, &model, 0);
    cmsPipelineInsertStage(b2a, cmsAT_END, cmsStageAllocToneCurves(nullptr, 3, nullptr));
    cmsPipelineInsertStage(b2a, cmsAT_END, b2a_clut);
    cmsPipelineInsertStage(b2a, cmsAT_END, cmsStageAllocToneCurves(nullptr, 4, nullptr));
    for (const auto tag : {cmsSigAToB0Tag, cmsSigAToB1Tag, cmsSigAToB2Tag}) cmsWriteTag(h, tag, a2b);
    for (const auto tag : {cmsSigBToA0Tag, cmsSigBToA1Tag, cmsSigBToA2Tag}) cmsWriteTag(h, tag, b2a);
    cmsPipelineFree(a2b);
    cmsPipelineFree(b2a);
    cmsUInt32Number size = 0;
    std::vector<std::uint8_t> bytes;
    if (cmsSaveProfileToMem(h, nullptr, &size) && size > 0) {
        bytes.resize(size);
        if (!cmsSaveProfileToMem(h, bytes.data(), &size)) bytes.clear();
    }
    cmsCloseProfile(h);
    return bytes;
}

/** Building the profile inverts the ink model on a grid (tens of ms), so each ink limit is built once. */
cmsHPROFILE makeGenericCmyk(double ink_limit_percent) {
    static std::mutex mutex;
    static std::map<int, std::vector<std::uint8_t>> cache;
    const double safe_ink_limit = std::isfinite(ink_limit_percent) ? std::clamp(ink_limit_percent, 200.0, 400.0) : 300.0;
    const int key = static_cast<int>(std::lround(safe_ink_limit));
    std::lock_guard lock(mutex);
    auto& bytes = cache[key];
    if (bytes.empty()) bytes = buildGenericCmyk(key / 100.0);
    return bytes.empty() ? nullptr : cmsOpenProfileFromMem(bytes.data(), static_cast<cmsUInt32Number>(bytes.size()));
}

ColorModel modelOf(cmsHPROFILE h) {
    switch (cmsGetColorSpace(h)) {
        case cmsSigRgbData: return ColorModel::RGB;
        case cmsSigGrayData: return ColorModel::Gray;
        case cmsSigCmykData: return ColorModel::CMYK;
        case cmsSigLabData: return ColorModel::Lab;
        default: return ColorModel::Other;
    }
}

struct TransformDeleter {
    void operator()(void* t) const {
        if (t != nullptr) cmsDeleteTransform(static_cast<cmsHTRANSFORM>(t));
    }
};
using TransformPtr = std::unique_ptr<void, TransformDeleter>;

struct ContextDeleter {
    void operator()(cmsContext c) const {
        if (c != nullptr) cmsDeleteContext(c);
    }
};
using ContextPtr = std::unique_ptr<std::remove_pointer_t<cmsContext>, ContextDeleter>;

/** Gamut-check transform: document → Lab16, with out-of-gamut pixels replaced by an impossible Lab. */
struct GamutCheck {
    ContextPtr context;
    TransformPtr transform;
};
constexpr cmsUInt16Number kAlarm[3] = {0, 0, 0};  // L*=0 with a*=b*=−128: no real colour encodes there

bool isAlarm(const cmsUInt16Number* lab) { return lab[0] == kAlarm[0] && lab[1] == kAlarm[1] && lab[2] == kAlarm[2]; }

std::optional<GamutCheck> gamutCheck(const Profile& document, cmsUInt32Number input_type, const Profile& proof, Intent intent,
                                     std::string& error) {
    GamutCheck g;
    g.context.reset(cmsCreateContext(nullptr, nullptr));
    if (!g.context) {
        error = "could not create a colour context";
        return std::nullopt;
    }
    cmsUInt16Number codes[cmsMAXCHANNELS] = {};
    std::copy(std::begin(kAlarm), std::end(kAlarm), codes);
    cmsSetAlarmCodesTHR(g.context.get(), codes);
    cmsHPROFILE lab = cmsCreateLab4ProfileTHR(g.context.get(), nullptr);
    g.transform.reset(cmsCreateProofingTransformTHR(g.context.get(), document.handle(), input_type, lab, TYPE_Lab_16,
                                                    proof.handle(), INTENT_RELATIVE_COLORIMETRIC, lcmsIntent(intent),
                                                    cmsFLAGS_GAMUTCHECK | cmsFLAGS_NOCACHE));
    cmsCloseProfile(lab);
    if (!g.transform) {
        error = "could not build the gamut check (unsupported profiles?)";
        return std::nullopt;
    }
    return g;
}

/**
 * Exact RGB→RGB conversion between two matrix-shaper profiles: decode each channel through its TRC,
 * multiply by dst⁻¹·src (colorants are D50-adapted in ICC), encode through the inverse TRC. Curves are
 * tabulated on 65 537 points over [0, 1] with linear interpolation (far finer than lcms's 33-point
 * precalculation) and evaluated analytically outside it, so float layers stay unbounded. Alpha is copied.
 */
class FastRgb final {
public:
    FastRgb(const FastRgb&) = delete;
    FastRgb& operator=(const FastRgb&) = delete;
    FastRgb(FastRgb&& o) noexcept : decode_(std::move(o.decode_)), encode_(std::move(o.encode_)), matrix_(o.matrix_) {
        for (int c = 0; c < 3; ++c) {
            src_[c] = std::exchange(o.src_[c], nullptr);
            inv_[c] = std::exchange(o.inv_[c], nullptr);
        }
    }
    ~FastRgb() {
        for (int c = 0; c < 3; ++c) {
            if (src_[c] != nullptr) cmsFreeToneCurve(src_[c]);
            if (inv_[c] != nullptr) cmsFreeToneCurve(inv_[c]);
        }
    }

    /**
     * Without intent tables, LCMS itself uses the matrix/TRC for every non-absolute intent, so the intent
     * does not change the result. With `bpc`, the fast path is still exact when both black points are
     * zero (TRC(0) = 0 on all channels), because black point compensation is then the identity.
     */
    static std::optional<FastRgb> build(cmsHPROFILE from, cmsHPROFILE to, bool bpc) {
        // A matrix-shaper profile may also carry intent-specific A2B/B2A tables. The ICC intent
        // selects those tables, so using the matrix path would silently ignore the profile's data.
        constexpr cmsTagSignature transform_tags[] = {cmsSigAToB0Tag, cmsSigAToB1Tag, cmsSigAToB2Tag,
                                                        cmsSigBToA0Tag, cmsSigBToA1Tag, cmsSigBToA2Tag};
        for (const auto tag : transform_tags) {
            if (cmsIsTag(from, tag) || cmsIsTag(to, tag)) return std::nullopt;
        }
        std::array<double, 9> m_from{}, m_to{};
        std::array<cmsToneCurve*, 3> c_from{}, c_to{};
        if (!read(from, m_from, c_from) || !read(to, m_to, c_to)) return std::nullopt;
        if (bpc) {
            for (const auto* curve : {c_from[0], c_from[1], c_from[2], c_to[0], c_to[1], c_to[2]}) {
                if (std::abs(cmsEvalToneCurveFloat(curve, 0.0F)) > 1e-6F) return std::nullopt;
            }
        }
        const auto inverse = invert(m_to);
        if (!inverse) return std::nullopt;
        FastRgb f;
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c) {
                double v = 0;
                for (int k = 0; k < 3; ++k) v += (*inverse)[static_cast<std::size_t>(r * 3 + k)] * m_from[static_cast<std::size_t>(k * 3 + c)];
                f.matrix_[static_cast<std::size_t>(r * 3 + c)] = static_cast<float>(v);
            }
        for (int c = 0; c < 3; ++c) {
            f.src_[c] = cmsDupToneCurve(c_from[static_cast<std::size_t>(c)]);
            f.inv_[c] = cmsReverseToneCurveEx(65536, c_to[static_cast<std::size_t>(c)]);
            if (f.src_[c] == nullptr || f.inv_[c] == nullptr) return std::nullopt;
            f.decode_[static_cast<std::size_t>(c)].resize(kTable + 1);
            f.encode_[static_cast<std::size_t>(c)].resize(kTable + 1);
            for (std::size_t i = 0; i <= kTable; ++i) {
                const float x = static_cast<float>(i) / static_cast<float>(kTable);
                f.decode_[static_cast<std::size_t>(c)][i] = cmsEvalToneCurveFloat(f.src_[c], x);
                f.encode_[static_cast<std::size_t>(c)][i] = cmsEvalToneCurveFloat(f.inv_[c], x);
            }
        }
        return f;
    }

    void run(const float* in, float* out, std::size_t pixels) const {
        for (std::size_t p = 0; p < pixels; ++p, in += 4, out += 4) {
            const float r = lookup(decode_[0], src_[0], in[0]);
            const float g = lookup(decode_[1], src_[1], in[1]);
            const float b = lookup(decode_[2], src_[2], in[2]);
            for (int c = 0; c < 3; ++c) {
                const float* m = matrix_.data() + c * 3;
                out[c] = lookup(encode_[static_cast<std::size_t>(c)], inv_[c], m[0] * r + m[1] * g + m[2] * b);
            }
            out[3] = in[3];
        }
    }

private:
    static constexpr std::size_t kTable = 65536;
    FastRgb() = default;

    static bool read(cmsHPROFILE h, std::array<double, 9>& m, std::array<cmsToneCurve*, 3>& curves) {
        if (cmsGetColorSpace(h) != cmsSigRgbData || !cmsIsMatrixShaper(h)) return false;
        const cmsTagSignature colorants[3] = {cmsSigRedColorantTag, cmsSigGreenColorantTag, cmsSigBlueColorantTag};
        const cmsTagSignature trcs[3] = {cmsSigRedTRCTag, cmsSigGreenTRCTag, cmsSigBlueTRCTag};
        for (int c = 0; c < 3; ++c) {
            const auto* xyz = static_cast<const cmsCIEXYZ*>(cmsReadTag(h, colorants[c]));
            curves[static_cast<std::size_t>(c)] = static_cast<cmsToneCurve*>(cmsReadTag(h, trcs[c]));
            if (xyz == nullptr || curves[static_cast<std::size_t>(c)] == nullptr) return false;
            m[static_cast<std::size_t>(c)] = xyz->X;  // column c
            m[static_cast<std::size_t>(3 + c)] = xyz->Y;
            m[static_cast<std::size_t>(6 + c)] = xyz->Z;
        }
        return true;
    }

    static std::optional<std::array<double, 9>> invert(const std::array<double, 9>& a) {
        const double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (!std::isfinite(det) || std::abs(det) < 1e-12) return std::nullopt;
        const double k = 1.0 / det;
        return std::array<double, 9>{(a[4] * a[8] - a[5] * a[7]) * k, (a[2] * a[7] - a[1] * a[8]) * k, (a[1] * a[5] - a[2] * a[4]) * k,
                                     (a[5] * a[6] - a[3] * a[8]) * k, (a[0] * a[8] - a[2] * a[6]) * k, (a[2] * a[3] - a[0] * a[5]) * k,
                                     (a[3] * a[7] - a[4] * a[6]) * k, (a[1] * a[6] - a[0] * a[7]) * k, (a[0] * a[4] - a[1] * a[3]) * k};
    }

    static float lookup(const std::vector<float>& table, const cmsToneCurve* curve, float v) {
        if (!(v >= 0.0F && v <= 1.0F)) return std::isfinite(v) ? cmsEvalToneCurveFloat(curve, v) : 0.0F;
        const float x = v * static_cast<float>(kTable);
        const auto i = std::min(static_cast<std::size_t>(x), kTable - 1);
        const float f = x - static_cast<float>(i);
        return table[i] + (table[i + 1] - table[i]) * f;
    }

    std::array<std::vector<float>, 3> decode_, encode_;
    std::array<float, 9> matrix_{};
    std::array<cmsToneCurve*, 3> src_{}, inv_{};
};

std::vector<TileCoordinate> tilesOf(const TileStore& store) { return store.tileCoordinates(); }

}  // namespace

// ── Profile ────────────────────────────────────────────────────────────────────────────────────

Profile::Profile(Profile&& other) noexcept : handle_(std::exchange(other.handle_, nullptr)) {}
Profile& Profile::operator=(Profile&& other) noexcept {
    if (this != &other) {
        if (handle_ != nullptr) cmsCloseProfile(handle_);
        handle_ = std::exchange(other.handle_, nullptr);
    }
    return *this;
}
Profile::~Profile() {
    if (handle_ != nullptr) cmsCloseProfile(handle_);
}

std::optional<Profile> Profile::fromIcc(std::span<const std::uint8_t> bytes, std::string& error) {
    if (bytes.size() < 132U || bytes.size() > kMaxIccBytes) {
        error = "ICC profile is truncated or too large";
        return std::nullopt;
    }
    cmsHPROFILE h = cmsOpenProfileFromMem(bytes.data(), static_cast<cmsUInt32Number>(bytes.size()));
    if (h == nullptr) {
        error = "not a valid ICC profile";
        return std::nullopt;
    }
    Profile profile(h);
    // Opening is lazy; make sure the profile can actually drive a transform to Lab.
    cmsHPROFILE lab = cmsCreateLab4Profile(nullptr);
    const auto model = modelOf(h);
    const cmsUInt32Number type = model == ColorModel::RGB    ? TYPE_RGB_16
                                 : model == ColorModel::Gray ? TYPE_GRAY_16
                                 : model == ColorModel::CMYK ? TYPE_CMYK_16
                                 : model == ColorModel::Lab  ? TYPE_Lab_16
                                                             : 0U;
    cmsHTRANSFORM probe = type != 0U ? cmsCreateTransform(h, type, lab, TYPE_Lab_16, INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOCACHE) : nullptr;
    cmsCloseProfile(lab);
    if (probe == nullptr) {
        error = "ICC profile is unsupported or damaged";
        return std::nullopt;
    }
    cmsDeleteTransform(probe);
    return profile;
}

Profile Profile::builtin(WorkingSpace space) { return Profile(makeBuiltin(space)); }
Profile Profile::lab() { return Profile(cmsCreateLab4Profile(nullptr)); }
Profile Profile::genericCmyk(float total_ink_percent) { return Profile(makeGenericCmyk(total_ink_percent)); }

ColorModel Profile::model() const { return handle_ != nullptr ? modelOf(handle_) : ColorModel::Other; }

std::string Profile::description() const {
    if (handle_ == nullptr) return {};
    char buffer[256] = {};
    cmsGetProfileInfoASCII(handle_, cmsInfoDescription, "en", "US", buffer, sizeof(buffer));
    return buffer;
}

std::vector<std::uint8_t> Profile::iccBytes() const {
    if (handle_ == nullptr) return {};
    cmsUInt32Number size = 0;
    if (!cmsSaveProfileToMem(handle_, nullptr, &size) || size == 0) return {};
    std::vector<std::uint8_t> bytes(size);
    if (!cmsSaveProfileToMem(handle_, bytes.data(), &size)) return {};
    bytes.resize(size);
    return bytes;
}

// ── Convert ────────────────────────────────────────────────────────────────────────────────────

std::optional<TileStore> convert(const TileStore& layer, const Profile& from, const Profile& to, PixelFormat output_format,
                                 const ConvertOptions& options, std::string& error) {
    const auto in_type = rgbaType(layer.format());
    const auto out_type = rgbaType(output_format);
    if (!in_type || !out_type) {
        error = "profile conversion needs RGBA layers";
        return std::nullopt;
    }
    if (from.model() != ColorModel::RGB || to.model() != ColorModel::RGB) {
        error = "profile conversion needs RGB source and destination profiles";
        return std::nullopt;
    }
    // Matrix-shaper pairs (all built-in spaces, most embedded camera/monitor profiles) take an exact fast
    // path. Otherwise lcms runs the transform: in float for float layers, else in 16-bit with its
    // high-resolution precalculation (the default 33-point grid is several code values off near black).
    const auto fast = options.intent != Intent::AbsoluteColorimetric
                          ? FastRgb::build(from.handle(), to.handle(), options.black_point_compensation)
                          : std::nullopt;
    TransformPtr transform;
    // ICC LUT/BPC/absolute paths need LCMS to honour the profile transform. Run those paths
    // through its float pipeline even for integer TileStores; the 16-bit HIGHRESPRECALC path can
    // lose several 8-bit code values in steep wide-gamut TRCs near black.
    constexpr bool float_path = true;
    if (!fast) {
        cmsUInt32Number flags = cmsFLAGS_NOCACHE | cmsFLAGS_COPY_ALPHA | cmsFLAGS_HIGHRESPRECALC;
        if (options.black_point_compensation) flags |= cmsFLAGS_BLACKPOINTCOMPENSATION;
        const auto type = float_path ? TYPE_RGBA_FLT : TYPE_RGBA_16;
        transform.reset(cmsCreateTransform(from.handle(), type, to.handle(), type, lcmsIntent(options.intent), flags));
        if (!transform) {
            error = "could not build the colour transform";
            return std::nullopt;
        }
    }
    // Derived document pixels must stay attached to the source document allocator so writes share its
    // TilePool and spill/restore budget rather than silently creating an unmanaged store.
    TileStore out = layer.emptyLike(output_format);
    const auto coordinates = tilesOf(layer);
    const auto out_tile_bytes = out.tile_byte_size();
    const auto in_bpp = layer.bytes_per_pixel(), out_bpp = out.bytes_per_pixel();
    constexpr std::size_t kPixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;
    const auto workers = parallel::workerCount(options.worker_threads);
    // Bound transient input references and converted tile buffers to the worker count. In particular,
    // do not pin every source tile or hold a full-image converted vector outside the document budget.
    for (std::size_t first = 0; first < coordinates.size(); first += workers) {
        const auto batch_size = std::min<std::size_t>(workers, coordinates.size() - first);
        std::vector<std::shared_ptr<const TileBuffer>> inputs(batch_size);
        std::vector<TileBuffer> converted(batch_size);
        parallel::parallelFor(batch_size, workers, [&](std::size_t local, std::size_t) {
            inputs[local] = layer.readTile(coordinates[first + local]);
            if (inputs[local] == nullptr) return;
            std::vector<float> in(kPixels * 4U), result(kPixels * 4U);
            for (std::size_t p = 0; p < kPixels; ++p) {
                paint::detail::readPixel(layer.format(), inputs[local]->data() + p * in_bpp, in.data() + p * 4U);
            }
            if (fast) {
                fast->run(in.data(), result.data(), kPixels);
            } else if (float_path) {
                cmsDoTransform(transform.get(), in.data(), result.data(), static_cast<cmsUInt32Number>(kPixels));
            } else {
                std::vector<std::uint16_t> in16(kPixels * 4U), out16(kPixels * 4U);
                for (std::size_t k = 0; k < in16.size(); ++k) {
                    in16[k] = static_cast<std::uint16_t>(std::lround(std::clamp(in[k], 0.0F, 1.0F) * 65535.0F));
                }
                cmsDoTransform(transform.get(), in16.data(), out16.data(), static_cast<cmsUInt32Number>(kPixels));
                for (std::size_t k = 0; k < out16.size(); ++k) result[k] = static_cast<float>(out16[k]) / 65535.0F;
            }
            converted[local].assign(out_tile_bytes, 0);
            for (std::size_t p = 0; p < kPixels; ++p) {
                paint::detail::writePixel(output_format, converted[local].data() + p * out_bpp, result.data() + p * 4U);
            }
        });
        inputs.clear();  // Unpin source tiles before output writes trigger document-budget eviction.
        // Write sequentially after joining workers so TileStore and its shared budget see bounded output.
        for (std::size_t local = 0; local < batch_size; ++local) {
            if (converted[local].empty()) continue;
            if (!out.writeTile(coordinates[first + local], converted[local])) {
                error = "could not store converted pixels";
                return std::nullopt;
            }
        }
    }
    return out;
}

// ── Display LUT / soft proof ───────────────────────────────────────────────────────────────────

std::optional<color::Lut> bakeDisplayLut(const Profile& document, const Profile& display, std::uint32_t size,
                                         const DisplayOptions& options, std::string& error) {
    if (size < 2U || size > 65U) {
        error = "display LUT size must be 2–65";
        return std::nullopt;
    }
    if (document.model() != ColorModel::RGB || display.model() != ColorModel::RGB) {
        error = "display transform needs RGB document and display profiles";
        return std::nullopt;
    }
    if (options.gamut_warning &&
        !std::all_of(options.gamut_warning_rgb.begin(), options.gamut_warning_rgb.end(), [](float c) { return std::isfinite(c); })) {
        error = "gamut-warning colour must be finite";
        return std::nullopt;
    }
    ContextPtr context(cmsCreateContext(nullptr, nullptr));
    cmsUInt32Number flags = cmsFLAGS_NOCACHE;
    if (options.black_point_compensation) flags |= cmsFLAGS_BLACKPOINTCOMPENSATION;
    TransformPtr transform;
    if (options.proof != nullptr) {
        flags |= cmsFLAGS_SOFTPROOFING;
        if (options.gamut_warning) {
            flags |= cmsFLAGS_GAMUTCHECK;
            cmsUInt16Number codes[cmsMAXCHANNELS] = {};
            for (int c = 0; c < 3; ++c) {
                codes[c] = static_cast<cmsUInt16Number>(std::lround(std::clamp(options.gamut_warning_rgb[static_cast<std::size_t>(c)], 0.0F, 1.0F) * 65535.0F));
            }
            cmsSetAlarmCodesTHR(context.get(), codes);
        }
        transform.reset(cmsCreateProofingTransformTHR(context.get(), document.handle(), TYPE_RGB_16, display.handle(), TYPE_RGB_16,
                                                      options.proof->handle(), lcmsIntent(options.intent),
                                                      lcmsIntent(options.proof_intent), flags));
    } else {
        transform.reset(cmsCreateTransformTHR(context.get(), document.handle(), TYPE_RGB_16, display.handle(), TYPE_RGB_16,
                                              lcmsIntent(options.intent), flags));
    }
    if (!transform) {
        error = "could not build the display transform";
        return std::nullopt;
    }
    const std::size_t count = static_cast<std::size_t>(size) * size * size;
    std::vector<cmsUInt16Number> in(count * 3U), out(count * 3U);
    for (std::uint32_t b = 0, k = 0; b < size; ++b)
        for (std::uint32_t g = 0; g < size; ++g)
            for (std::uint32_t r = 0; r < size; ++r, ++k) {
                const auto node = [&](std::uint32_t v) { return static_cast<cmsUInt16Number>((v * 65535U + (size - 1U) / 2U) / (size - 1U)); };
                in[k * 3U] = node(r);
                in[k * 3U + 1U] = node(g);
                in[k * 3U + 2U] = node(b);
            }
    cmsDoTransform(transform.get(), in.data(), out.data(), static_cast<cmsUInt32Number>(count));
    color::Lut lut;
    lut.title = options.proof != nullptr ? "Soft proof: " + options.proof->description() : "Display: " + display.description();
    lut.size_3d = size;
    lut.table_3d.resize(count * 3U);
    for (std::size_t i = 0; i < out.size(); ++i) lut.table_3d[i] = static_cast<float>(out[i]) / 65535.0F;
    return lut;
}

void applyLutToArgb(std::uint32_t* pixels, std::size_t count, const color::Lut& lut, unsigned threads) {
    if (pixels == nullptr || count == 0 || lut.size_3d < 2U) return;
    const auto work = [&](std::size_t begin, std::size_t end) {
        std::uint32_t last_in = 0, last_out = 0;
        bool have_last = false;
        for (std::size_t i = begin; i < end; ++i) {
            const std::uint32_t p = pixels[i];
            const std::uint32_t rgb = p & 0x00FFFFFFU;
            if (!have_last || rgb != last_in) {
                const auto out = lut.apply({static_cast<float>((p >> 16) & 0xFFU) / 255.0F, static_cast<float>((p >> 8) & 0xFFU) / 255.0F,
                                            static_cast<float>(p & 0xFFU) / 255.0F});
                const auto q = [](float v) { return static_cast<std::uint32_t>(std::lround(std::clamp(v, 0.0F, 1.0F) * 255.0F)); };
                last_out = (q(out[0]) << 16) | (q(out[1]) << 8) | q(out[2]);
                last_in = rgb;
                have_last = true;
            }
            pixels[i] = (p & 0xFF000000U) | last_out;
        }
    };
    unsigned workers = threads != 0 ? threads : std::min(4U, std::max(1U, std::thread::hardware_concurrency()));
    if (count < 65536U) workers = 1;
    if (workers == 1) {
        work(0, count);
        return;
    }
    std::vector<std::thread> pool;
    const std::size_t chunk = (count + workers - 1U) / workers;
    for (unsigned w = 0; w < workers; ++w) {
        const std::size_t begin = w * chunk, end = std::min(count, begin + chunk);
        if (begin < end) pool.emplace_back(work, begin, end);
    }
    for (auto& t : pool) t.join();
}

// ── Gamut warning ──────────────────────────────────────────────────────────────────────────────

std::optional<TileStore> gamutMask(const TileStore& layer, const Profile& document, const Profile& proof, Intent intent,
                                   std::string& error, unsigned worker_threads) {
    const auto in_type = rgbaType(layer.format());
    if (!in_type) {
        error = "gamut warning needs an RGBA layer";
        return std::nullopt;
    }
    if (document.model() != ColorModel::RGB) {
        error = "gamut warning needs an RGB document profile";
        return std::nullopt;
    }
    // The Lab output has 3 channels; the input's alpha is an extra channel that is simply skipped.
    auto check = gamutCheck(document, *in_type, proof, intent, error);
    if (!check) return std::nullopt;
    TileStore mask(PixelFormat::A16, layer.width(), layer.height());
    const auto coordinates = tilesOf(layer);
    std::vector<std::shared_ptr<const TileBuffer>> inputs(coordinates.size());
    for (std::size_t i = 0; i < coordinates.size(); ++i) inputs[i] = layer.readTile(coordinates[i]);
    std::vector<TileBuffer> masks(coordinates.size());
    const auto bpp = layer.bytes_per_pixel();
    parallel::parallelFor(coordinates.size(), parallel::workerCount(worker_threads), [&](std::size_t i, std::size_t) {
        if (inputs[i] == nullptr) return;
        std::vector<cmsUInt16Number> lab(static_cast<std::size_t>(kTileEdge) * kTileEdge * 3U);
        cmsDoTransform(check->transform.get(), inputs[i]->data(), lab.data(), kTileEdge * kTileEdge);
        TileBuffer tile(mask.tile_byte_size(), 0);
        bool any = false;
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            // Fully transparent pixels show nothing, so they carry no warning.
            const std::uint8_t* px = inputs[i]->data() + p * bpp;
            bool transparent = false;
            switch (layer.format()) {
                case PixelFormat::RGBA8: transparent = px[3] == 0; break;
                case PixelFormat::RGBA16: {
                    std::uint16_t a;
                    std::memcpy(&a, px + 6, 2);
                    transparent = a == 0;
                    break;
                }
                default: {
                    float a;
                    std::memcpy(&a, px + 12, 4);
                    transparent = !(a > 0.0F);
                    break;
                }
            }
            if (transparent || !isAlarm(lab.data() + p * 3U)) continue;
            const std::uint16_t full = 65535;
            std::memcpy(tile.data() + p * 2U, &full, 2);
            any = true;
        }
        if (any) masks[i] = std::move(tile);
    });
    for (std::size_t i = 0; i < coordinates.size(); ++i) {
        if (masks[i].empty()) continue;
        if (!mask.writeTile(coordinates[i], masks[i])) {
            error = "could not store the gamut mask";
            return std::nullopt;
        }
    }
    return mask;
}

// ── Readouts ───────────────────────────────────────────────────────────────────────────────────

struct ColorReadout::State {
    TransformPtr to_lab, to_cmyk;
    GamutCheck gamut;
};

ColorReadout::ColorReadout(std::unique_ptr<State> state) : state_(std::move(state)) {}
ColorReadout::ColorReadout(ColorReadout&&) noexcept = default;
ColorReadout& ColorReadout::operator=(ColorReadout&&) noexcept = default;
ColorReadout::~ColorReadout() = default;

std::optional<ColorReadout> ColorReadout::create(const Profile& document, const Profile& cmyk, std::string& error) {
    if (document.model() != ColorModel::RGB || cmyk.model() != ColorModel::CMYK) {
        error = "readout needs an RGB document profile and a CMYK profile";
        return std::nullopt;
    }
    auto state = std::make_unique<State>();
    const Profile lab_profile = Profile::lab();
    state->to_lab.reset(cmsCreateTransform(document.handle(), TYPE_RGB_FLT, lab_profile.handle(), TYPE_Lab_FLT,
                                           INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOCACHE));
    state->to_cmyk.reset(cmsCreateTransform(document.handle(), TYPE_RGB_FLT, cmyk.handle(), TYPE_CMYK_FLT, INTENT_RELATIVE_COLORIMETRIC,
                                            cmsFLAGS_NOCACHE | cmsFLAGS_BLACKPOINTCOMPENSATION));
    if (!state->to_lab || !state->to_cmyk) {
        error = "could not build the readout transforms";
        return std::nullopt;
    }
    auto gamut = gamutCheck(document, TYPE_RGB_16, cmyk, Intent::RelativeColorimetric, error);
    if (!gamut) return std::nullopt;
    state->gamut = std::move(*gamut);
    return ColorReadout(std::move(state));
}

Readout ColorReadout::read(std::array<float, 3> rgb) const {
    for (auto& v : rgb) v = std::isfinite(v) ? std::clamp(v, 0.0F, 1.0F) : 0.0F;
    Readout r;
    cmsDoTransform(state_->to_lab.get(), rgb.data(), r.lab.data(), 1);
    cmsDoTransform(state_->to_cmyk.get(), rgb.data(), r.cmyk.data(), 1);
    const cmsUInt16Number in16[3] = {static_cast<cmsUInt16Number>(std::lround(rgb[0] * 65535.0F)),
                                     static_cast<cmsUInt16Number>(std::lround(rgb[1] * 65535.0F)),
                                     static_cast<cmsUInt16Number>(std::lround(rgb[2] * 65535.0F))};
    cmsUInt16Number lab16[3];
    cmsDoTransform(state_->gamut.transform.get(), in16, lab16, 1);
    r.out_of_gamut = isAlarm(lab16);
    return r;
}

}  // namespace croptop::cms
