// Tests for the GPU quirks table.
#include "gpu_quirks.h"

#include <gtest/gtest.h>

using namespace croptop::gpu;

TEST(GpuQuirks, SoftwareRastersAreRecognisedByNameOrType) {
    GpuInfo a;
    a.name = "SwiftShader Device (Subzero)";
    EXPECT_TRUE(quirksFor(a).software_renderer);
    GpuInfo b;
    b.name = "Something";
    b.type = DeviceType::Cpu;
    EXPECT_TRUE(quirksFor(b).software_renderer);
    GpuInfo c;
    c.name = "Adreno (TM) 740";
    const auto q = quirksFor(c);
    EXPECT_FALSE(q.software_renderer);
    EXPECT_FALSE(q.blocked);
}

TEST(GpuQuirks, RulesMatchVendorNameAndDriverRange) {
    std::vector<std::string> warnings;
    const auto rules = parseRules(
        "# comment\n"
        "vendor=0x13B5 name=mali-g52 driver_below=0x2000 flags=no_compute_blend,single_frame_in_flight\n"
        "vendor=0x5143 device=7 flags=block  # trailing comment\n",
        &warnings);
    ASSERT_EQ(rules.size(), 2U);
    EXPECT_TRUE(warnings.empty());
    GpuInfo mali{0x13B5, 1, 0x1000, 0, DeviceType::Integrated, "Mali-G52 MC2"};
    auto q = quirksFor(mali, rules);
    EXPECT_TRUE(q.no_compute_blend);
    EXPECT_TRUE(q.single_frame_in_flight);
    EXPECT_FALSE(q.blocked);
    mali.driver_version = 0x2000;  // fixed driver: rule no longer applies
    EXPECT_FALSE(quirksFor(mali, rules).no_compute_blend);
    GpuInfo adreno{0x5143, 7, 0, 0, DeviceType::Integrated, "Adreno"};
    EXPECT_TRUE(quirksFor(adreno, rules).blocked);
    adreno.device_id = 8;
    EXPECT_FALSE(quirksFor(adreno, rules).blocked);
}

TEST(GpuQuirks, MalformedLinesAreSkippedWithWarnings) {
    std::vector<std::string> warnings;
    const auto rules = parseRules(
        "vendor=zzz flags=block\n"
        "vendor=1 flags=unknown_flag\n"
        "vendor=1\n"
        "garbage\n"
        "vendor=2 flags=block\n",
        &warnings);
    EXPECT_EQ(rules.size(), 1U);
    EXPECT_EQ(warnings.size(), 4U);
}
