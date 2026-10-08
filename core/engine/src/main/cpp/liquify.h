#pragma once

// Liquify engine CPU core: a displacement-field mesh plus full-resolution render.
//
// The mesh stores a backward displacement d at grid nodes every `spacing` px (node (i, j) sits at document
// point (i·spacing, j·spacing)); between nodes it's bilinear. The rendered pixel at p samples the source at
// p + d(p) (bicubic Catmull-Rom, premultiplied alpha), so the identity field reproduces the source exactly.
// Brush tools edit the field by composing a local point map p → p′ with it: d′(p) = (p′ − p) + d(p′), which
// keeps successive dabs consistent (content moves with the brush instead of smearing).
//   ForwardWarp:  p′ = p − w·δ, where δ is the brush motion since the last dab (content follows the brush).
//   PushLeft:     p′ = p − w·δ⊥, with δ⊥ = (δy, −δx) (content slides left of the stroke direction; right
//                 when moving the other way).
//   TwirlCW/CCW:  rotation about the brush centre by ±w·0.2 rad per dab (clockwise on screen, y down).
//   Pucker/Bloat: scale about the brush centre by (1 ± w·0.1) per dab (pucker pulls content inwards).
//   Reconstruct:  d′ = d·(1 − w).    Smooth: d′ = mix(d, 3×3 node average of d, w).
// The weight w = pressure·rate·falloff(r / radius)·(1 − frozen), falloff(t) = (1 − t²)^(1 + 2·density), where
// `frozen` is the freeze mask's coverage at the node. render() re-samples only tiles whose field is non-zero;
// every other tile is shared with the source (copy-on-write). RGBA8/16/32F layers.

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::paint {

enum class LiquifyTool : std::uint8_t {
    ForwardWarp,
    Reconstruct,
    Smooth,
    TwirlClockwise,
    TwirlCounterClockwise,
    Pucker,
    Bloat,
    PushLeft,
};

struct LiquifyBrush final {
    double x = 0.0;           // brush centre, document px
    double y = 0.0;
    double size = 100.0;      // diameter px, 1–15000
    float density = 0.5F;     // 0–1: edge falloff
    float pressure = 1.0F;    // 0–1
    float rate = 0.8F;        // 0–1: strength per dab
    double dx = 0.0;          // motion since the previous dab (ForwardWarp, PushLeft)
    double dy = 0.0;
};

class LiquifyMesh final {
public:
    /** width × height document; `spacing` 1–64 px between nodes (4 keeps brush edges crisp). */
    LiquifyMesh(std::uint32_t width, std::uint32_t height, std::uint32_t spacing = 4);

    bool apply(LiquifyTool tool, const LiquifyBrush& brush, std::string& error);
    /** Reconstruct All by `amount` (0–1; 1 restores the identity). */
    void reconstructAll(float amount);
    /** A16 mask of the document's size (coverage = how frozen); nullptr clears it. */
    bool setFreezeMask(const TileStore* mask, std::string& error);

    /**
     * Replaces the displacement field (interleaved dx, dy per node, `columns() * rows() * 2` floats) with an externally computed
     * one, for example the inverse of a mesh warp. Non-finite values are treated as 0. False when the size is wrong.
     */
    bool setField(std::vector<float> field);

    /** Bilinear displacement at a document point (px). */
    std::array<float, 2> displacementAt(double x, double y) const;
    bool isIdentity() const;

    std::uint32_t columns() const { return columns_; }
    std::uint32_t rows() const { return rows_; }
    std::uint32_t spacing() const { return spacing_; }
    /** Interleaved (dx, dy) per node, row-major: upload as an RG32F texture for the GPU preview. */
    const std::vector<float>& field() const { return field_; }

    /** Full-resolution result for `source` (the layer the mesh was built for). */
    std::optional<TileStore> render(const TileStore& source, std::string& error, unsigned worker_threads = 0,
                                    const TileStore* selection_mask = nullptr) const;

private:
    std::array<float, 2> node(std::int64_t i, std::int64_t j) const;
    static std::array<float, 2> sample(const std::vector<float>& field, std::uint32_t columns, std::uint32_t rows,
                                       std::uint32_t spacing, double x, double y);

    std::uint32_t width_;
    std::uint32_t height_;
    std::uint32_t spacing_;
    std::uint32_t columns_;
    std::uint32_t rows_;
    std::vector<float> field_;
    std::vector<float> frozen_;
};

}  // namespace croptop::paint
