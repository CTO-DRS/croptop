#pragma once

// Adapts NativeEngine's compact slider-preview block to the catalogue
// ParamMap, then maps and bakes it using the canonical adjustment APIs.
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "lut.h"

namespace croptop::adjust::bridge {

/**
 * Existing NativeEngine block layouts (all values finite; counts are integral):
 * brightnessContrast [brightness,contrast,legacy]; exposure [exposure,offset,gamma];
 * vibrance [vibrance,saturation]; invert []; posterize [levels]; threshold [level];
 * levels [inBlack,inWhite,gamma,outBlack,outWhite]; curves: four repetitions of
 * [pointCount,x0,y0,...] in rgb,red,green,blue order; hueSaturation [master h,s,l,
 * colorize, 6*(h,s,l), 6*(outerStart,innerStart,innerEnd,outerEnd)]; colorBalance
 * [3 shifts for shadows,midtones,highlights,preserveLuminosity]; blackWhite
 * [6 channel weights,tint,tintR,tintG,tintB]; photoFilter [preset(0 warming/1 cooling/
 * 2 sepia/3 custom),r,g,b,density,preserveLuminosity]; channelMixer [output(0..2),
 * red,green,blue,constant,monochrome]; gradientMap [reverse,dither,stopCount,
 * repeated(position,r,g,b,a)]; selectiveColor [range(0..8),cyan,magenta,yellow,black,
 * absolute]; shadowsHighlights [shadows,highlights] (spatial, not LUT-bakeable).
 */
std::optional<color::Lut> bakeBlock(const std::string& spec_id, const std::vector<float>& block,
                                    std::uint32_t lut_size, std::string& error);

}  // namespace croptop::adjust::bridge
