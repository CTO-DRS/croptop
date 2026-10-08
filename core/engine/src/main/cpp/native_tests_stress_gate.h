#pragma once

// Opt-in gate for slow native checks (24 MP timings, 200 MP / 500-layer stress, large hostile
// inputs). They skip in the default suite and run with CROPTOP_STRESS=1.

#include <gtest/gtest.h>

#include <cstdlib>
#include <string_view>

namespace croptop::testing {

inline bool stressEnabled() {
    const char* value = std::getenv("CROPTOP_STRESS");
    return value != nullptr && std::string_view(value) == "1";
}

}  // namespace croptop::testing

#define CROPTOP_REQUIRE_STRESS()                   \
    if (!::croptop::testing::stressEnabled())      \
    GTEST_SKIP() << "slow check; set CROPTOP_STRESS=1 to run it"
