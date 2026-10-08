#pragma once

// Puppet Warp: a triangle mesh over the layer's content is deformed by pins and
// the layer is re-rendered through it.
//   Mesh: a regular triangular grid (spacing from `density`) keeping the triangles that touch content
//         (alpha above 1/255 after dilating by `expansion` px).
//   Solve: local/global "as-rigid-as-possible" iterations (Sorkine & Alexa 2007) on the mesh edges. Each
//          triangle's local fit is a rotation (Rigid), a rotation with uniform scale (Normal) or a general
//          linear map (Distort: local frames do not turn, so the sheet shears and stretches). The global step is a sparse
//          Laplacian solve (conjugate gradients, warm-started) with the pins as stiff constraints and a weak
//          pull to the rest pose so parts without pins stay put. Iterates until the pose settles.
//   Render: every deformed triangle is rasterised and inverse-mapped (barycentric) to its rest position,
//           sampled bilinearly in premultiplied alpha. Where the mesh folds over itself, the triangles nearest
//           the pin with the higher `depth` draw on top.
// Pins are attached where they are placed (barycentric in the containing triangle), not snapped to vertices.

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "selection_mask.h"
#include "tile_store.h"

namespace croptop::puppet {

enum class Mode : std::uint8_t { Rigid, Normal, Distort };
enum class Density : std::uint8_t { Fewer, Normal, More };

struct MeshSpec final {
    Density density = Density::Normal;
    float expansion = 2.0F;  // px beyond the content's edge, −32…64 (negative shrinks)
};

struct Pin final {
    selection::Point target;  // where the pin has been dragged to (layer pixels)
    int depth = 0;            // overlap order: triangles nearest a higher-depth pin draw on top
};

class PuppetMesh final {
public:
    /** Builds the mesh over `layer`'s content (RGBA). nullptr and `error` when the layer is empty or invalid. */
    static std::unique_ptr<PuppetMesh> build(const TileStore& layer, const MeshSpec& spec, std::string& error);

    std::size_t vertexCount() const { return rest_.size(); }
    std::size_t triangleCount() const { return triangles_.size(); }
    const std::vector<selection::Point>& restVertices() const { return rest_; }
    const std::vector<selection::Point>& deformedVertices() const { return deformed_; }
    const std::vector<std::array<std::uint32_t, 3>>& triangles() const { return triangles_; }

    /** Places a pin at `at` (must lie on the mesh); returns its index, or −1 when it is off the mesh. */
    int addPin(selection::Point at);
    bool removePin(int index);
    std::size_t pinCount() const { return pins_.size(); }
    Pin& pin(int index) { return pins_.at(static_cast<std::size_t>(index)).pin; }
    /** Rest position of a pin (where it was placed). */
    selection::Point pinRest(int index) const;

    /**
     * Deforms the mesh toward the pins' targets: local/global rounds, warm-started from the current pose,
     * until no vertex moves more than `tolerance` px in a round (or `max_iterations`). Returns the rounds
     * run. While dragging, each pointer move typically converges in a few rounds; a large jump from the
     * rest pose may take tens (rotations propagate through the mesh one round at a time).
     */
    int solve(Mode mode, int max_iterations = 64, double tolerance = 0.02);

    /** Renders the source layer through the current deformation into a new layer of the same size/format. */
    std::optional<TileStore> render(unsigned worker_threads, std::string& error) const;

private:
    struct Attached final {
        Pin pin;
        std::uint32_t triangle = 0;
        double u = 0, v = 0, w = 0;  // barycentric weights of the triangle's vertices
    };
    explicit PuppetMesh(const TileStore& layer) : source_(layer) {}
    TileStore source_;
    std::vector<selection::Point> rest_, deformed_;
    std::vector<std::array<std::uint32_t, 3>> triangles_;
    std::vector<Attached> pins_;
    // Grid geometry: vertex (i, j) sits at (x0 + i·s + (j odd ? s/2 : 0), y0 + j·h), h = s·√3/2.
    double x0_ = 0, y0_ = 0, spacing_ = 16, row_height_ = 16;
    int columns_ = 0, rows_ = 0;
    std::vector<std::int32_t> triangle_at_;  // grid triangle (band-major, 2·(columns − 1) per band) → mesh triangle or −1
    // Global-step system (depends on the pin set only): CSR over the vertex adjacency, including the diagonal.
    std::vector<std::uint32_t> row_start_, column_;
    std::vector<double> value_, diagonal_;
    bool system_dirty_ = true;
    int locate(selection::Point p, double& u, double& v, double& w) const;
    void buildSystem();
};

}  // namespace croptop::puppet
