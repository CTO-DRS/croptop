// Develop settings ↔ XMP recipe bridge; see develop_recipe_bridge.h.
#include "develop_recipe_bridge.h"

namespace croptop::develop {
namespace {

pipeline::Profile profileOf(Look look) {
    switch (look) {
        case Look::Vivid: return pipeline::Profile::Vivid;
        case Look::Portrait: return pipeline::Profile::Portrait;
        case Look::Landscape: return pipeline::Profile::Landscape;
        case Look::Monochrome: return pipeline::Profile::Monochrome;
        case Look::None:
        case Look::Neutral: return pipeline::Profile::Neutral;
    }
    return pipeline::Profile::Neutral;
}

Look lookOf(pipeline::Profile profile) {
    switch (profile) {
        case pipeline::Profile::Vivid: return Look::Vivid;
        case pipeline::Profile::Portrait: return Look::Portrait;
        case pipeline::Profile::Landscape: return Look::Landscape;
        case pipeline::Profile::Monochrome: return Look::Monochrome;
        case pipeline::Profile::Neutral: return Look::Neutral;
    }
    return Look::Neutral;
}

pipeline::GradeWheel wheelOf(const GradeWheel& w) { return {w.hue, w.saturation, w.luminance}; }
GradeWheel wheelOf(const pipeline::GradeWheel& w) { return {w.hue, w.saturation, w.luminance}; }

}  // namespace

RecipeSettings settingsFromRecipe(const DevelopRecipe& recipe) {
    RecipeSettings out;
    auto& s = out.settings;
    const auto& t = recipe.tone;
    s.basic.exposure = t.exposure; s.basic.contrast = t.contrast; s.basic.highlights = t.highlights; s.basic.shadows = t.shadows;
    s.basic.whites = t.whites; s.basic.blacks = t.blacks; s.basic.texture = t.texture; s.basic.clarity = t.clarity;
    s.basic.dehaze = t.dehaze; s.basic.vibrance = t.vibrance; s.basic.saturation = t.saturation;
    if (!t.point_curve.points.empty()) s.tone_curve.points = t.point_curve.points;
    s.profile = profileOf(recipe.color.look);
    for (std::size_t i = 0; i < s.mixer.size(); ++i) {
        s.mixer[i] = {recipe.color.mixer.hue[i], recipe.color.mixer.saturation[i], recipe.color.mixer.luminance[i]};
    }
    s.grading.shadows = wheelOf(recipe.color.grading.shadows);
    s.grading.midtones = wheelOf(recipe.color.grading.midtones);
    s.grading.highlights = wheelOf(recipe.color.grading.highlights);
    s.grading.global = wheelOf(recipe.color.grading.global);
    s.grading.blending = recipe.color.grading.blending;
    s.grading.balance = recipe.color.grading.balance;
    const auto& d = recipe.detail;
    s.detail.sharpen_amount = d.sharpening.amount; s.detail.sharpen_radius = d.sharpening.radius;
    s.detail.sharpen_detail = d.sharpening.detail; s.detail.sharpen_masking = d.sharpening.masking;
    s.detail.luminance_noise = d.noise.luminance; s.detail.color_noise = d.noise.color;
    s.effects.vignette_amount = d.vignette.amount; s.effects.vignette_midpoint = d.vignette.midpoint;
    s.effects.grain_amount = d.grain.amount; s.effects.grain_size = d.grain.size;
    out.camera_white_balance = recipe.white_balance;
    return out;
}

DevelopRecipe recipeFromSettings(const pipeline::Settings& s) {
    DevelopRecipe r;
    r.tone.exposure = s.basic.exposure; r.tone.contrast = s.basic.contrast; r.tone.highlights = s.basic.highlights;
    r.tone.shadows = s.basic.shadows; r.tone.whites = s.basic.whites; r.tone.blacks = s.basic.blacks;
    r.tone.texture = s.basic.texture; r.tone.clarity = s.basic.clarity; r.tone.dehaze = s.basic.dehaze;
    r.tone.vibrance = s.basic.vibrance; r.tone.saturation = s.basic.saturation;
    r.tone.point_curve.points = s.tone_curve.points;
    r.color.look = lookOf(s.profile);
    for (std::size_t i = 0; i < s.mixer.size(); ++i) {
        r.color.mixer.hue[i] = s.mixer[i].hue;
        r.color.mixer.saturation[i] = s.mixer[i].saturation;
        r.color.mixer.luminance[i] = s.mixer[i].luminance;
    }
    r.color.grading.shadows = wheelOf(s.grading.shadows);
    r.color.grading.midtones = wheelOf(s.grading.midtones);
    r.color.grading.highlights = wheelOf(s.grading.highlights);
    r.color.grading.global = wheelOf(s.grading.global);
    r.color.grading.blending = s.grading.blending;
    r.color.grading.balance = s.grading.balance;
    r.detail.sharpening = {s.detail.sharpen_amount, s.detail.sharpen_radius, s.detail.sharpen_detail, s.detail.sharpen_masking};
    r.detail.noise.luminance = s.detail.luminance_noise;
    r.detail.noise.color = s.detail.color_noise;
    r.detail.vignette.amount = s.effects.vignette_amount;
    r.detail.vignette.midpoint = s.effects.vignette_midpoint;
    r.detail.grain.amount = s.effects.grain_amount;
    r.detail.grain.size = s.effects.grain_size;
    return r;
}

}  // namespace croptop::develop
