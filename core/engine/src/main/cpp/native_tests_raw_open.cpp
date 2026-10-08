// Tests for the camera raw → editor layer bridge. Synthetic checks always run; real-camera
// checks use the raws in CROPTOP_RAW_SAMPLES (see native_tests_raw_decode.cpp) and skip without it.
#include "native_tests_synthetic_dng.h"
#include "lens_profile.h"
#include "raw_open.h"
#include "native_tests_stress_gate.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <vector>

namespace {

namespace raw = croptop::raw;
using croptop::PixelFormat;
using croptop::TileStore;

TEST(RawOpen, SrgbEncodeMatchesTheStandard) {
    EXPECT_FLOAT_EQ(raw::srgbEncode(0.0F), 0.0F);
    EXPECT_FLOAT_EQ(raw::srgbEncode(1.0F), 1.0F);
    EXPECT_NEAR(raw::srgbEncode(0.0031308F), 12.92F * 0.0031308F, 1e-6F);
    EXPECT_NEAR(raw::srgbEncode(0.18F), 0.4614F, 1e-3F);
    EXPECT_NEAR(raw::srgbEncode(0.5F), 0.7354F, 1e-3F);
    EXPECT_FLOAT_EQ(raw::srgbEncode(-3.0F), 0.0F);
    EXPECT_FLOAT_EQ(raw::srgbEncode(7.0F), 1.0F);
    EXPECT_FLOAT_EQ(raw::srgbEncode(std::nanf("")), 0.0F);
}

// A 3×2 image whose pixels are numbered 0..5 in row-major order (the red channel carries the number).
TileStore numbered() {
    TileStore store(PixelFormat::RGBA8, 3, 2);
    croptop::TileBuffer tile(store.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 2; ++y) {
        for (std::uint32_t x = 0; x < 3; ++x) {
            const std::size_t at = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4;
            tile[at] = static_cast<std::uint8_t>(y * 3 + x);
            tile[at + 3] = 255;
        }
    }
    EXPECT_TRUE(store.writeTile({0, 0}, tile));
    return store;
}

std::vector<int> readNumbers(const TileStore& store) {
    std::vector<int> values;
    const auto tile = store.readTile({0, 0});
    for (std::uint32_t y = 0; y < store.height(); ++y) {
        for (std::uint32_t x = 0; x < store.width(); ++x) {
            values.push_back((*tile)[(static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4]);
        }
    }
    return values;
}

TEST(RawOpen, OrientationTableMatchesExif) {
    // Displayed upright, row-major, for the stored numbering 0 1 2 / 3 4 5, per EXIF orientation 1..8.
    const std::vector<std::pair<int, std::pair<std::pair<std::uint32_t, std::uint32_t>, std::vector<int>>>> expected = {
        {1, {{3, 2}, {0, 1, 2, 3, 4, 5}}},
        {2, {{3, 2}, {2, 1, 0, 5, 4, 3}}},
        {3, {{3, 2}, {5, 4, 3, 2, 1, 0}}},
        {4, {{3, 2}, {3, 4, 5, 0, 1, 2}}},
        {5, {{2, 3}, {0, 3, 1, 4, 2, 5}}},
        {6, {{2, 3}, {3, 0, 4, 1, 5, 2}}},
        {7, {{2, 3}, {5, 2, 4, 1, 3, 0}}},
        {8, {{2, 3}, {2, 5, 1, 4, 0, 3}}},
    };
    const auto source = numbered();
    for (const auto& [orientation, result] : expected) {
        SCOPED_TRACE(orientation);
        const auto oriented = raw::orient(source, orientation);
        EXPECT_EQ(oriented.width(), result.first.first);
        EXPECT_EQ(oriented.height(), result.first.second);
        EXPECT_EQ(readNumbers(oriented), result.second);
    }
    EXPECT_EQ(readNumbers(raw::orient(source, 0)), readNumbers(source));
    EXPECT_EQ(readNumbers(raw::orient(source, 9)), readNumbers(source));
}

TEST(RawOpen, SidecarRoundTripsTheSettingsThePipelineCanCarry) {
    raw::OpenOptions options;
    auto& s = options.settings;
    s.basic.exposure = 0.75F; s.basic.contrast = 12.0F; s.basic.highlights = -30.0F; s.basic.shadows = 25.0F;
    s.basic.whites = 5.0F; s.basic.blacks = -8.0F; s.basic.texture = 10.0F; s.basic.clarity = 15.0F;
    s.basic.dehaze = 4.0F; s.basic.vibrance = 20.0F; s.basic.saturation = -6.0F;
    s.profile = croptop::develop::pipeline::Profile::Landscape;
    s.mixer[3] = {10.0F, -20.0F, 5.0F};
    s.grading.shadows = {220.0F, 30.0F, -10.0F};
    s.grading.blending = 60.0F;
    s.detail.sharpen_amount = 55.0F; s.detail.luminance_noise = 12.0F; s.detail.color_noise = 30.0F;
    s.effects.vignette_amount = -20.0F; s.effects.grain_amount = 8.0F;
    s.tone_curve.points = {{0.0F, 0.0F}, {0.4F, 0.35F}, {1.0F, 1.0F}};
    options.camera_white_balance = std::array<float, 3>{2.1F, 1.0F, 1.6F};

    const auto xmp = raw::sidecarXmp(options);
    EXPECT_NE(xmp.find("crs:Exposure2012"), std::string::npos) << "Lightroom must be able to read it";
    std::string error;
    const auto back = raw::optionsFromXmp(xmp, error);
    ASSERT_TRUE(back) << error;
    const auto& b = back->settings;
    EXPECT_NEAR(b.basic.exposure, 0.75F, 1e-3F);
    EXPECT_NEAR(b.basic.contrast, 12.0F, 1e-3F);
    EXPECT_NEAR(b.basic.highlights, -30.0F, 1e-3F);
    EXPECT_NEAR(b.basic.dehaze, 4.0F, 1e-3F);
    EXPECT_NEAR(b.basic.saturation, -6.0F, 1e-3F);
    EXPECT_EQ(b.profile, croptop::develop::pipeline::Profile::Landscape);
    EXPECT_NEAR(b.mixer[3].saturation, -20.0F, 1e-3F);
    EXPECT_NEAR(b.grading.shadows.hue, 220.0F, 1e-2F);
    EXPECT_NEAR(b.grading.blending, 60.0F, 1e-3F);
    EXPECT_NEAR(b.detail.sharpen_amount, 55.0F, 1e-3F);
    EXPECT_NEAR(b.effects.vignette_amount, -20.0F, 1e-3F);
    ASSERT_EQ(b.tone_curve.points.size(), 3U);
    EXPECT_NEAR(b.tone_curve.points[1].second, 0.35F, 1e-3F);
    ASSERT_TRUE(back->camera_white_balance);
    EXPECT_NEAR((*back->camera_white_balance)[0], 2.1F, 1e-3F);
    EXPECT_NEAR((*back->camera_white_balance)[2], 1.6F, 1e-3F);
}

TEST(RawOpen, ImportsALightroomStyleSidecar) {
    const std::string xmp = R"(<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+1.25" crs:Contrast2012="+18"
  crs:Vibrance="+30" crs:Sharpness="60"/></rdf:RDF></x:xmpmeta>)";
    std::string error;
    const auto options = raw::optionsFromXmp(xmp, error);
    ASSERT_TRUE(options) << error;
    EXPECT_NEAR(options->settings.basic.exposure, 1.25F, 1e-3F);
    EXPECT_NEAR(options->settings.basic.contrast, 18.0F, 1e-3F);
    EXPECT_NEAR(options->settings.basic.vibrance, 30.0F, 1e-3F);
    EXPECT_FALSE(options->camera_white_balance) << "a Lightroom sidecar carries no camera multipliers";
    EXPECT_FALSE(raw::optionsFromXmp("not xmp at all", error));
}

TEST(RawOpen, TheDemosaicMethodCanBeOverriddenForAQuickFirstView) {
    const auto file = croptop::testing::syntheticDng();
    std::string error;
    raw::OpenOptions quick;
    quick.demosaic = raw::Demosaic::Bilinear;
    const auto fast = raw::openFlattened(file, quick, error);
    ASSERT_TRUE(fast) << error;
    const auto quality = raw::openFlattened(file, {}, error);
    ASSERT_TRUE(quality) << error;
    EXPECT_EQ(fast->pixels.width(), quality->pixels.width());
    // Both methods reproduce a flat neutral scene: the centre pixels agree closely.
    std::array<std::uint16_t, 4> a{}, b{};
    const std::size_t at = (static_cast<std::size_t>(24) * croptop::kTileEdge + 32) * 8;
    std::memcpy(a.data(), fast->pixels.readTile({0, 0})->data() + at, 8);
    std::memcpy(b.data(), quality->pixels.readTile({0, 0})->data() + at, 8);
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(a[static_cast<std::size_t>(c)], b[static_cast<std::size_t>(c)], 1500.0);
}

TEST(RawOpen, RejectsFilesThatAreNotRaws) {
    const std::vector<std::uint8_t> garbage(2048, 0x33);
    std::string error;
    EXPECT_FALSE(raw::openFlattened(garbage, {}, error));
    EXPECT_FALSE(error.empty());
}

TEST(RawOpen, SampleRawsOpenUprightAndOpaque) {
    CROPTOP_REQUIRE_STRESS();
    const char* directory = std::getenv("CROPTOP_RAW_SAMPLES");
    if (directory == nullptr || !std::filesystem::is_directory(directory)) GTEST_SKIP() << "set CROPTOP_RAW_SAMPLES";
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        SCOPED_TRACE(entry.path().filename().string());
        std::ifstream file(entry.path(), std::ios::binary);
        const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        std::string error;
        float last_progress = 0.0F;
        raw::OpenOptions options;
        options.progress = [&](float f) {
            EXPECT_GE(f, last_progress - 1e-4F);
            last_progress = f;
            return true;
        };
        const auto opened = raw::openFlattened(bytes, options, error);
        ASSERT_TRUE(opened) << error;
        EXPECT_EQ(opened->pixels.format(), PixelFormat::RGBA16);
        const bool swapped = opened->metadata.orientation >= 5;
        EXPECT_EQ(opened->pixels.width(), swapped ? opened->sensor_height : opened->sensor_width);
        EXPECT_EQ(opened->pixels.height(), swapped ? opened->sensor_width : opened->sensor_height);
        // Sample the centre tile: opaque, and neither black nor blown out on average.
        const auto tile = opened->pixels.readTile({static_cast<std::int32_t>(opened->pixels.width() / 2 / croptop::kTileEdge),
                                                   static_cast<std::int32_t>(opened->pixels.height() / 2 / croptop::kTileEdge)});
        ASSERT_NE(tile, nullptr);
        double sum = 0;
        for (std::size_t p = 0; p < static_cast<std::size_t>(croptop::kTileEdge) * croptop::kTileEdge; ++p) {
            std::uint16_t r, a;
            std::memcpy(&r, tile->data() + p * 8, 2);
            std::memcpy(&a, tile->data() + p * 8 + 6, 2);
            EXPECT_EQ(a, 0xFFFF);
            sum += r / 65535.0;
        }
        const double mean = sum / (static_cast<double>(croptop::kTileEdge) * croptop::kTileEdge);
        EXPECT_GT(mean, 0.03);
        EXPECT_LT(mean, 0.97);
        EXPECT_GE(last_progress, 0.99F);
    }
}

TEST(RawOpen, DevelopSessionRendersBoundedPreviewsWithCropAndLocalMasks) {
    CROPTOP_REQUIRE_STRESS();
    const char* directory = std::getenv("CROPTOP_RAW_SAMPLES");
    if (directory == nullptr || !std::filesystem::is_directory(directory)) GTEST_SKIP() << "set CROPTOP_RAW_SAMPLES";
    const auto entry = *std::filesystem::directory_iterator(directory);
    std::ifstream file(entry.path(), std::ios::binary);
    const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    std::string error;
    auto session = raw::DevelopSession::open(bytes, {}, error);
    ASSERT_TRUE(session) << error;
    croptop::develop::pipeline::Settings settings;
    const auto neutral = session->render(settings, 512, 0, error);
    ASSERT_TRUE(neutral) << error;
    EXPECT_EQ(neutral->format(), PixelFormat::RGBA8);
    EXPECT_LE(std::max(neutral->width(), neutral->height()), 512U);
    const double aspect = static_cast<double>(session->width()) / session->height();
    EXPECT_NEAR(static_cast<double>(neutral->width()) / neutral->height(), aspect, 0.02);

    settings.crop.left = 0.25F;
    settings.crop.right = 0.75F;
    const auto cropped = session->render(settings, 512, 0, error);
    ASSERT_TRUE(cropped) << error;
    EXPECT_NEAR(static_cast<double>(cropped->width()) / cropped->height(), aspect / 2.0, 0.05) << "half the width of the frame";

    settings = {};
    croptop::develop::pipeline::LocalMask mask;
    mask.adjust.exposure = 2.0F;
    croptop::develop::pipeline::MaskComponent circle;
    circle.kind = croptop::develop::pipeline::MaskKind::Radial;
    circle.p = {0.5F, 0.5F, 0.15F, 0.15F, 0.0F, 0.3F, 0.0F, 0.0F};
    mask.components.push_back(circle);
    settings.masks.push_back(mask);
    const auto local = session->render(settings, 512, 0, error);
    ASSERT_TRUE(local) << error;
    const auto at = [](const TileStore& s, std::uint32_t x, std::uint32_t y) {
        const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
        return static_cast<int>(tile->data()[(static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4]);
    };
    const auto cx = neutral->width() / 2, cy = neutral->height() / 2;
    EXPECT_GT(at(*local, cx, cy), at(*neutral, cx, cy)) << "the masked centre is brighter";
    EXPECT_EQ(at(*local, 3, 3), at(*neutral, 3, 3)) << "the corner is untouched";

    const auto overlay = session->maskOverlay(settings, 0, 512, 0, error);
    ASSERT_TRUE(overlay) << error;
    EXPECT_EQ(overlay->width(), local->width());
    EXPECT_EQ(overlay->height(), local->height());
    const auto alpha = [&](std::uint32_t x, std::uint32_t y) {
        const auto tile = overlay->readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
        return tile == nullptr ? 0 : static_cast<int>(tile->data()[(static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4 + 3]);
    };
    EXPECT_GT(alpha(cx, cy), 100) << "the circle is drawn at its centre";
    EXPECT_EQ(alpha(3, 3), 0);
    EXPECT_FALSE(session->maskOverlay(settings, 5, 512, 0, error));
}

/** The pinned lensfun XML bundle shipped in the app assets (CROPTOP_LENSFUN_DB overrides the directory). */
std::filesystem::path lensfunDirectory() {
    if (const char* env = std::getenv("CROPTOP_LENSFUN_DB")) return env;
    return std::filesystem::path(__FILE__).parent_path().parent_path() / "assets" / "lensfun";
}

struct LensDiff {
    double all = 0, centre = 0, edge = 0;  // mean absolute RGB difference, 0–1
    double brightnessRatio = 1;            // mean(b) / mean(a)
};

/** Differences between two same-sized RGBA16 frames over the whole frame, its middle half and its outer rim. */
LensDiff compareFrames(const TileStore& a, const TileStore& b) {
    LensDiff out;
    double sumAll = 0, sumCentre = 0, sumEdge = 0, sumA = 0, sumB = 0;
    std::uint64_t nAll = 0, nCentre = 0, nEdge = 0;
    const auto bpp = a.bytes_per_pixel();
    const double w = a.width(), h = a.height();
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < a.height(); ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < a.width(); ++tx) {
            const auto ta = a.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            const auto tb = b.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            if (ta == nullptr || tb == nullptr) continue;
            const auto tw = std::min<std::uint32_t>(croptop::kTileEdge, a.width() - tx * croptop::kTileEdge);
            const auto th = std::min<std::uint32_t>(croptop::kTileEdge, a.height() - ty * croptop::kTileEdge);
            for (std::uint32_t y = 0; y < th; ++y) {
                for (std::uint32_t x = 0; x < tw; ++x) {
                    const auto at = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * bpp;
                    const double fx = (tx * croptop::kTileEdge + x) / w, fy = (ty * croptop::kTileEdge + y) / h;
                    const bool inCentre = fx > 0.25 && fx < 0.75 && fy > 0.25 && fy < 0.75;
                    const bool inEdge = fx < 0.08 || fx > 0.92 || fy < 0.08 || fy > 0.92;
                    for (int c = 0; c < 3; ++c) {
                        std::uint16_t va, vb;
                        std::memcpy(&va, ta->data() + at + c * 2, 2);
                        std::memcpy(&vb, tb->data() + at + c * 2, 2);
                        const double d = std::abs(static_cast<double>(va) - vb) / 65535.0;
                        sumAll += d;
                        ++nAll;
                        sumA += va / 65535.0;
                        sumB += vb / 65535.0;
                        if (inCentre) { sumCentre += d; ++nCentre; }
                        if (inEdge) { sumEdge += d; ++nEdge; }
                    }
                }
            }
        }
    }
    out.all = nAll ? sumAll / static_cast<double>(nAll) : 0.0;
    out.centre = nCentre ? sumCentre / static_cast<double>(nCentre) : 0.0;
    out.edge = nEdge ? sumEdge / static_cast<double>(nEdge) : 0.0;
    out.brightnessRatio = sumA > 0 ? sumB / sumA : 1.0;
    return out;
}

TEST(RawOpen, RealLensfunProfilesCorrectRealCameraFiles) {
    CROPTOP_REQUIRE_STRESS();
    const char* directory = std::getenv("CROPTOP_RAW_SAMPLES");
    if (directory == nullptr || !std::filesystem::is_directory(directory)) GTEST_SKIP() << "set CROPTOP_RAW_SAMPLES";
    const auto db_dir = lensfunDirectory();
    if (!std::filesystem::is_directory(db_dir)) GTEST_SKIP() << "no lensfun directory at " << db_dir;
    auto database = std::make_shared<croptop::lens::Database>();
    std::size_t files = 0;
    for (const auto& xml : std::filesystem::directory_iterator(db_dir)) {
        if (xml.path().extension() != ".xml") continue;
        std::ifstream in(xml.path(), std::ios::binary);
        const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        std::string error;
        ASSERT_TRUE(database->addXml(text, error)) << xml.path() << ": " << error;
        ++files;
    }
    ASSERT_GT(files, 20U);
    ASSERT_GT(database->lenses.size(), 1000U);

    std::size_t corrected = 0, skipped = 0;
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        SCOPED_TRACE(entry.path().filename().string());
        std::ifstream file(entry.path(), std::ios::binary);
        const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        std::string error;
        raw::OpenOptions plain;
        const auto without = raw::openFlattened(bytes, plain, error);
        ASSERT_TRUE(without) << error;
        raw::OpenOptions with = plain;
        with.lens_database = database;
        const auto withLens = raw::openFlattened(bytes, with, error);
        ASSERT_TRUE(withLens) << error;
        ASSERT_EQ(without->pixels.width(), withLens->pixels.width());
        const bool skippedWarning = std::any_of(withLens->warnings.begin(), withLens->warnings.end(),
                                                [](const std::string& w) { return w.find("lens correction skipped") != std::string::npos; });
        const auto diff = compareFrames(without->pixels, withLens->pixels);
        const double difference = diff.all;
        std::printf("[lens] %s: %s, difference all %.5f centre %.5f edge %.5f, brightness x%.4f\n", entry.path().filename().string().c_str(), skippedWarning ? "no profile" : "corrected", diff.all, diff.centre, diff.edge, diff.brightnessRatio);
        if (skippedWarning) {
            ++skipped;
            EXPECT_EQ(difference, 0.0) << "a file without a profile must be untouched";
        } else {
            ++corrected;
            EXPECT_GT(difference, 1e-4) << "a matched profile must change the pixels (distortion, vignetting and/or TCA)";
            EXPECT_LT(difference, 0.2) << "lens correction moves detail and lifts corners; it must not be a different picture";
            EXPECT_GT(diff.brightnessRatio, 0.9);
            EXPECT_LT(diff.brightnessRatio, 1.1) << "vignetting and distortion shift the mean brightness by a few percent at most";
        }
    }
    EXPECT_GE(corrected, 3U) << "Canon, Fuji, Olympus and Sony samples resolve in the pinned database";

    // The Develop Optics toggles decide what is corrected: all off = identical to no database; each flag alone changes a corrected file.
    const auto canon = std::filesystem::path(directory) / "canon_450d.cr2";
    if (std::filesystem::exists(canon)) {
        std::ifstream file(canon, std::ios::binary);
        const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        std::string error;
        raw::OpenOptions base;
        base.lens_database = database;
        const auto openWith = [&](bool profile, bool tca) {
            auto options = base;
            options.settings.optics.lens_profile = profile;
            options.settings.optics.remove_chromatic_aberration = tca;
            auto opened = raw::openFlattened(bytes, options, error);
            EXPECT_TRUE(opened) << error;
            return opened;
        };
        raw::OpenOptions none;
        const auto uncorrected = raw::openFlattened(bytes, none, error);
        ASSERT_TRUE(uncorrected) << error;
        const auto off = openWith(false, false);
        const auto distortionOnly = openWith(true, false);
        const auto tcaOnly = openWith(false, true);
        ASSERT_TRUE(off && distortionOnly && tcaOnly);
        EXPECT_EQ(compareFrames(uncorrected->pixels, off->pixels).all, 0.0) << "Optics flags off: the profile must not be applied";
        EXPECT_GT(compareFrames(uncorrected->pixels, distortionOnly->pixels).all, 1e-4) << "distortion and vignetting alone change the picture";
        EXPECT_GT(compareFrames(uncorrected->pixels, tcaOnly->pixels).all, 1e-5) << "chromatic aberration removal alone changes the picture";
        EXPECT_GT(compareFrames(tcaOnly->pixels, distortionOnly->pixels).all, 1e-4);
    }
    (void)skipped;
}

}  // namespace
