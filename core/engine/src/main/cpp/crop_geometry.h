#pragma once

// Crop geometry for perspective-crop homographies and straighten (rotate + largest
// inscribed crop). Pure math; the matrices are 3×3 row-major source → destination maps (like core:model
// Matrix3) meant for resample::warp. A plain rectangular crop is resample::canvasSize with a negative
// offset; "hide cropped pixels" is document state owned by NativeDocument.

#include <array>
#include <cstdint>
#include <optional>
#include <string>

namespace croptop::crop {

using Matrix = std::array<double, 9>;

struct Point final {
    double x = 0.0;
    double y = 0.0;
};

/**
 * Homography sending the quad's corners (top-left, top-right, bottom-right, bottom-left, in source
 * document pixels) to the corners of [0, width] × [0, height]. Rejects non-finite, degenerate or
 * non-convex quads.
 */
std::optional<Matrix> perspectiveCrop(const std::array<Point, 4>& quad, double width, double height, std::string& error);

/** Natural output size for a perspective crop: the mean lengths of opposite quad edges (at least 1). */
std::array<std::uint32_t, 2> perspectiveCropSize(const std::array<Point, 4>& quad);

struct Straighten final {
    Matrix matrix{};  // Source → cropped output.
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

/**
 * Rotates a width × height image by `degrees` about its center (positive turns clockwise on screen, as
 * core:model Matrix3.rotate in y-down document space) and crops to the largest centered rectangle with
 * the original aspect ratio that contains no area from outside the image.
 */
std::optional<Straighten> straighten(std::uint32_t width, std::uint32_t height, double degrees, std::string& error);

/** Maps a point through a 3×3 matrix (false if it lands on or beyond the horizon). */
bool apply(const Matrix& m, Point p, Point& out);

}  // namespace croptop::crop
