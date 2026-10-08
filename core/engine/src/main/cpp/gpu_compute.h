#pragma once

// Headless Vulkan compute (GPU develop): one instance and device without any surface, and a synchronous
// "run this SPIR-V over these buffers" call. It is what the develop shaders run on; the on-screen canvas keeps its own device
// (`VulkanCanvas`). Buffers are host-visible and coherent, which is the right trade on phones (unified memory) and keeps the
// call simple; a device-local staging path is a later optimisation.
//
// Vulkan types stay in gpu_compute.cpp. Works on Android and on any host with a Vulkan driver (Mesa lavapipe included), which is how
// the shaders are tested against the CPU reference.

#include <cstdint>
#include <memory>
#include <span>
#include <string>

namespace croptop::gpu {

struct CreateOptions final {
    /** Accept CPU rasterisers (lavapipe, SwiftShader). Off in production so a phone never silently renders on the CPU through Vulkan. */
    bool allow_software = false;
};

/** One storage buffer of a dispatch. `input` is uploaded before the run, `output` is filled after it (both may be set: in/out). */
struct Binding final {
    const void* input = nullptr;
    void* output = nullptr;
    std::size_t bytes = 0;
};

class Compute {
public:
    static std::unique_ptr<Compute> create(const CreateOptions& options, std::string& error);
    ~Compute();
    Compute(const Compute&) = delete;
    Compute& operator=(const Compute&) = delete;

    const std::string& deviceName() const;
    bool isSoftware() const;

    /**
     * Runs `spirv` (a compute shader whose bindings 0..N-1 in set 0 are storage buffers) with `push_constants` (may be empty) over
     * gx × gy × gz workgroups. false with `error` on any failure, including device loss (the object is then unusable).
     */
    bool dispatch(std::span<const std::uint32_t> spirv, std::span<const Binding> bindings, std::span<const std::uint8_t> push_constants,
                  std::uint32_t gx, std::uint32_t gy, std::uint32_t gz, std::string& error);

private:
    struct Impl;
    explicit Compute(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace croptop::gpu
